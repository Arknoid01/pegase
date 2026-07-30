package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.bureau.BureauSessionStore;
import com.pegasuscorp.orbe.diag.CorrectionsStore;
import com.pegasuscorp.orbe.diag.DiagBehaviorIndex;
import com.pegasuscorp.orbe.diag.DiagSynthesizer;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.memory.IntentDetector;
import com.pegasuscorp.orbe.prefetch.PrefetchCache;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-diagnostic Pégase — lit les traces existantes (pas de nouveau format).
 * Répond à « comment tu vas ? », « bilan de session », « analyse tes traces »,
 * et recherche sémantique dans l'historique de comportement ({@code action=search}).
 */
public final class DiagTool implements Tool {

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final Pattern PROBLEM_HEADER = Pattern.compile(
            "(?m)^##\\s*Probl[eè]me\\s*[:：]\\s*(.+)$");

    /** Dernier bilan summary servi — pour « dis m'en plus » → detail. */
    public static final String KEY_DIAG_LAST_SUMMARY = "diag_last_summary_day";
    /** Fenêtre courte : une relance dans la même conversation. */
    public static final long TTL_DIAG_LAST_SUMMARY_MS = 2L * 60 * 60 * 1000;

    /** Injectable tests — si non null, remplace l'appel LLM. */
    static volatile AnalyzeLlm analyzeLlmOverride;

    interface AnalyzeLlm {
        String complete(Context ctx, String prompt) throws Exception;
    }

    @Override
    public String id() {
        return "diag";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.DIAG;
    }

    @Override
    public String description() {
        return "diag(action:\"summary\"|\"detail\"|\"hesitations\"|\"failures\"|\"weekly\"|\"search\"|\"analyze\", "
                + "days?:int, date?:str, query?:str) — Auto-diagnostic de Pégase sur ses traces. "
                + "summary : bilan d'un jour (date=YYYY-MM-DD ou hier ; défaut = aujourd'hui). "
                + "Consulte d'abord l'agrégat journalier (comme brief), puis le détail jsonl. "
                + "detail : raconte les erreurs du jour (agrégat error_details), langage clair. "
                + "hesitations : fantômes bloqués, JSON malformé, étapes agentiques bloquées. "
                + "failures : échecs d'outils, erreurs LLM, replis bureau. "
                + "weekly : bilan sur les archives (7 jours, ou days). "
                + "search : recherche sémantique dans l'historique de comportement. "
                + "analyze : bilan → analyse QA Markdown (fichiers, causes, prompts Cursor) ; "
                + "demande confirmation pour noter dans le bureau. "
                + "Utilise detail pour « qu'est-ce qui a merdé », « explique l'erreur », "
                + "ou une relance (« dis m'en plus », « développe ») après un bilan summary. "
                + "Utilise analyze pour « analyse tes problèmes », « qu'est-ce qui ne va pas », "
                + "« comment tu t'améliores », « propose des corrections ».";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        JSONObject effective = maybeUpgradeFollowUpToDetail(ctx, params);
        String action = resolveAction(effective);
        try {
            switch (action) {
                case "hesitations":
                case "hesitation":
                    cb.onSuccess(ToolResult.text(DiagSynthesizer.hesitations(ctx)));
                    return;
                case "detail":
                case "details":
                case "raconte": {
                    java.time.LocalDate day = resolveSummaryDate(effective);
                    cb.onSuccess(ToolResult.text(DiagSynthesizer.detail(ctx, day)));
                    return;
                }
                case "failures":
                case "failure":
                case "errors":
                    cb.onSuccess(ToolResult.text(DiagSynthesizer.failures(ctx)));
                    return;
                case "weekly":
                case "week":
                case "archives":
                case "archive": {
                    int days = effective != null
                            ? effective.optInt("days", Trace.ARCHIVE_RETENTION_DAYS)
                            : Trace.ARCHIVE_RETENTION_DAYS;
                    if (days <= 0) days = Trace.ARCHIVE_RETENTION_DAYS;
                    cb.onSuccess(ToolResult.text(DiagSynthesizer.summarizeArchive(ctx, days)));
                    return;
                }
                case "search":
                case "find":
                case "lookup": {
                    String query = "";
                    if (effective != null) {
                        query = effective.optString("query", "").trim();
                        if (TextUtils.isEmpty(query)) query = effective.optString("q", "").trim();
                        if (TextUtils.isEmpty(query)) query = effective.optString("text", "").trim();
                        if (TextUtils.isEmpty(query)) {
                            query = effective.optString("utterance", "").trim();
                        }
                    }
                    cb.onSuccess(ToolResult.text(DiagBehaviorIndex.search(ctx, query)));
                    return;
                }
                case "analyze":
                case "analyse":
                case "corrections":
                case "correction":
                    BG.execute(() -> runAnalyze(ctx, cb));
                    return;
                case "summary":
                case "bilan":
                case "status":
                default: {
                    java.time.LocalDate day = resolveSummaryDate(effective);
                    String text = DiagSynthesizer.summary(ctx, day);
                    markRecentSummary(ctx, day);
                    cb.onSuccess(ToolResult.text(text));
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "diagnostic impossible" : e.getMessage();
            cb.onError("Je n'ai pas pu lire mes traces : " + msg);
        }
    }

    /**
     * Relance d'approfondissement après un summary → force {@code detail}
     * (même jour que le bilan).
     */
    static JSONObject maybeUpgradeFollowUpToDetail(Context ctx, JSONObject params) {
        if (ctx == null || !hasRecentSummary(ctx)) return params;
        String hint = firstHint(params);
        String action = params != null
                ? params.optString("action", "").trim().toLowerCase(Locale.ROOT) : "";
        String foldSrc = !hint.isEmpty() ? hint : action;
        if (foldSrc.isEmpty()) return params;
        String fold = SpeechInputNormalizer.fold(foldSrc).replace('\'', ' ')
                .replace('’', ' ').replaceAll("\\s+", " ").trim();
        if (!IntentDetector.looksLikeDiagDetailFollowUp(fold)) return params;
        // Ne pas écraser une action explicite autre que summary / vide
        if (!action.isEmpty() && !"summary".equals(action) && !"bilan".equals(action)
                && !"status".equals(action) && !"detail".equals(action)
                && !"details".equals(action)) {
            if (!IntentDetector.looksLikeDiagDetailFollowUp(action)) return params;
        }
        try {
            JSONObject out = params != null ? new JSONObject(params.toString()) : new JSONObject();
            out.put("action", "detail");
            String day = recentSummaryDayIso(ctx);
            if (day != null && out.optString("date", "").isEmpty()) {
                out.put("date", day);
            }
            return out;
        } catch (Exception e) {
            return params;
        }
    }

    public static void markRecentSummary(Context ctx, java.time.LocalDate day) {
        if (ctx == null) return;
        java.time.LocalDate d = day != null ? day : java.time.LocalDate.now();
        PrefetchCache.put(ctx, KEY_DIAG_LAST_SUMMARY, d.toString());
    }

    public static boolean hasRecentSummary(Context ctx) {
        return !TextUtils.isEmpty(
                PrefetchCache.get(ctx, KEY_DIAG_LAST_SUMMARY, TTL_DIAG_LAST_SUMMARY_MS));
    }

    /** Jour ISO du dernier summary, ou {@code null}. */
    public static String recentSummaryDayIso(Context ctx) {
        return PrefetchCache.get(ctx, KEY_DIAG_LAST_SUMMARY, TTL_DIAG_LAST_SUMMARY_MS);
    }

    /**
     * Normalise action / alias / texte libre (query, utterance).
     * Vide → summary (bilan « comment tu vas »), jamais une action inventée.
     */
    static String resolveAction(JSONObject params) {
        if (params == null) return "summary";
        String raw = params.optString("action", "").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            raw = firstHint(params).toLowerCase(Locale.ROOT);
        }
        switch (raw) {
            case "hesitations":
            case "hesitation":
                return "hesitations";
            case "detail":
            case "details":
            case "raconte":
                return "detail";
            case "failures":
            case "failure":
            case "errors":
                return "failures";
            case "weekly":
            case "week":
            case "archives":
            case "archive":
                return "weekly";
            case "search":
            case "find":
            case "lookup":
                return "search";
            case "analyze":
            case "analyse":
            case "corrections":
            case "correction":
                return "analyze";
            case "summary":
            case "bilan":
            case "status":
                return "summary";
            default:
                break;
        }
        if (raw.isEmpty()) return "summary";
        String fold = SpeechInputNormalizer.fold(raw).replace('\'', ' ')
                .replace('’', ' ').replaceAll("\\s+", " ").trim();
        if (IntentDetector.looksLikeDiagAnalyze(fold)) return "analyze";
        if (IntentDetector.looksLikeWeeklyDiag(fold)) return "weekly";
        if (IntentDetector.looksLikeDiagSearch(fold)) return "search";
        if (IntentDetector.looksLikeDiagDetail(fold)) return "detail";
        if (fold.contains("hesit") || fold.contains("fantome")) return "hesitations";
        if (fold.contains("echec") || fold.contains("erreur") || fold.contains("failure")) {
            return "failures";
        }
        return "summary";
    }

    private static String firstHint(JSONObject params) {
        if (params == null) return "";
        String[] keys = {"query", "q", "utterance", "text", "type"};
        for (String key : keys) {
            String v = params.optString(key, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /**
     * Date du bilan summary : param {@code date}, sinon indices dans query
     * (« hier », « 21/07 », ISO), sinon aujourd'hui.
     */
    static java.time.LocalDate resolveSummaryDate(JSONObject params) {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (params == null) return today;
        String raw = params.optString("date", "").trim();
        if (raw.isEmpty()) raw = params.optString("day", "").trim();
        if (raw.isEmpty()) {
            String hint = firstHint(params);
            if (!hint.isEmpty()) raw = hint;
        }
        if (raw.isEmpty()) return today;
        String fold = SpeechInputNormalizer.fold(raw).replace('\'', ' ')
                .replace('’', ' ').replaceAll("\\s+", " ").trim();
        if (fold.equals("hier") || fold.contains(" hier")
                || fold.startsWith("hier ") || fold.contains("la veille")) {
            return today.minusDays(1);
        }
        if (fold.equals("aujourd hui") || fold.equals("aujourdhui")
                || fold.contains("aujourd hui") || fold.equals("today")) {
            return today;
        }
        // ISO YYYY-MM-DD
        try {
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return java.time.LocalDate.parse(raw);
            }
        } catch (Exception ignored) {}
        // dd/MM ou dd/MM/yyyy
        Matcher m = Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?")
                .matcher(raw);
        if (m.find()) {
            try {
                int d = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int y = today.getYear();
                if (m.group(3) != null) {
                    y = Integer.parseInt(m.group(3));
                    if (y < 100) y += 2000;
                }
                return java.time.LocalDate.of(y, mo, d);
            } catch (Exception ignored) {}
        }
        return today;
    }

    /**
     * Bilan → si RAS / aucune donnée pas de LLM ; sinon analyse QA + confirmation.
     */
    static void runAnalyze(Context ctx, ToolCallback cb) {
        try {
            // Merge automatique → corrections.md (anomalies + hallucinations)
            String mergeMsg = DiagSynthesizer.analyze(ctx);
            String summary = DiagSynthesizer.summarize(ctx);
            if (isNothingToReport(summary)) {
                // Garder le libellé réel (aucune trace ≠ « tout s'est bien passé »)
                cb.onSuccess(ToolResult.text(summary));
                return;
            }

            String markdown;
            try {
                String prompt = buildAnalyzePrompt(summary);
                markdown = completeAnalyzeLlm(ctx, prompt);
            } catch (Exception e) {
                markdown = DiagSynthesizer.fallbackAnalyzeMarkdown(ctx);
            }
            if (markdown == null || markdown.trim().isEmpty()) {
                markdown = DiagSynthesizer.fallbackAnalyzeMarkdown(ctx);
            }
            markdown = markdown.trim();
            if (!looksLikeStructuredAnalyze(markdown)) {
                // LLM a renvoyé du prose libre → garder mais préférer fallback structuré
                String fallback = DiagSynthesizer.fallbackAnalyzeMarkdown(ctx);
                if (fallback != null && !fallback.isEmpty()) markdown = fallback;
            }

            int n = countProblems(markdown);
            final String md = markdown;
            StringBuilder ask = new StringBuilder();
            if (mergeMsg != null && mergeMsg.contains("ajouté")) {
                ask.append(mergeMsg).append(' ');
            }
            if (n <= 0) {
                ask.append("Analyse faite. Tu veux que je note ça dans le bureau ?");
            } else {
                ask.append("J'ai trouvé ").append(n).append(" problème")
                        .append(n > 1 ? "s" : "")
                        .append(". Tu veux que je les note dans le bureau ?");
            }

            cb.onConfirmNeeded(ask.toString(),
                    () -> {
                        try {
                            noteInBureau(ctx, md);
                            List<String> titles = problemTitles(md);
                            if (!titles.isEmpty()) {
                                CorrectionsStore.mergePending(ctx, titles);
                            }
                            cb.onSuccess(ToolResult.text(
                                    "C'est noté dans le bureau du jour.", md));
                        } catch (Exception e) {
                            cb.onError("Impossible d'écrire dans le bureau : "
                                    + (e.getMessage() == null ? "erreur" : e.getMessage()));
                        }
                    },
                    () -> cb.onSuccess(ToolResult.text(
                            "D'accord, je ne note rien dans le bureau."
                                    + (mergeMsg != null && mergeMsg.contains("corrections.md")
                                    ? " (corrections.md est déjà à jour.)"
                                    : ""),
                            md)));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "analyse impossible" : e.getMessage();
            cb.onError("Je n'ai pas pu analyser : " + msg);
        }
    }

    static boolean isNothingToReport(String summary) {
        if (summary == null || summary.trim().isEmpty()) return true;
        String s = summary.trim();
        String low = s.toLowerCase(Locale.ROOT);
        // Pas de LLM analyze : RAS, aucune donnée, ou agrégat sans détail (déjà factuel)
        if (s.equals(DiagSynthesizer.NO_TRACES_AVAILABLE)
                || low.startsWith("aucune trace disponible")
                || low.startsWith("aucune donnée pour")
                || low.contains("détail non conservé")
                || low.contains("detail non conserve")) {
            return true;
        }
        if (low.contains("pas un ras")
                || (low.contains("conservé") && low.contains(" sur ")
                && (low.contains("échec") || low.contains("erreur")
                || low.contains("friction") || low.contains("agrégat du jour")))) {
            return false;
        }
        return s.equals(DiagSynthesizer.NOTHING_TO_REPORT_YESTERDAY)
                || low.startsWith("rien à signaler")
                || low.startsWith("rien a signaler")
                || low.startsWith("ras sur les traces");
    }

    static String buildAnalyzePrompt(String summary) {
        return "Tu es l'ingénieur QA de Pégase (Android Java, ~53 000 lignes).\n"
                + "Voici son bilan de session :\n"
                + "[BILAN]" + (summary == null ? "" : summary.trim()) + "[/BILAN]\n\n"
                + "=== MAPPING FICHIERS ORBE ===\n"
                + "phantom_action / action fantôme    → ConversationManager.java (guardPhantom)\n"
                + "                                     + description de l'outil concerné\n"
                + "tool_failure notepad               → NotepadTool.java + NotepadEditor.java\n"
                + "tool_failure create_file           → CreateFileTool.java\n"
                + "tool_failure files / permission    → AndroidManifest.xml + permission runtime\n"
                + "tool_failure timer                 → TimerTool.java\n"
                + "tool_failure calculator            → CalculatorTool.java + BureauCalcHelper.java\n"
                + "tool_failure open_app              → OpenAppTool.java (alias manquant)\n"
                + "tool_failure search                → TavilySearchService.java\n"
                + "tool_failure orion_manager         → OrionManagerTool.java\n"
                + "repeated_action                    → description de l'outil + ContextAnalyzer\n"
                + "hallucination (context_chunks=0)   → BureauMarkdownBrain.buildQuestionPrompt()\n"
                + "latence > 5s                       → GroqChatBackend.java (timeout)\n"
                + "fallback groq → cerebras → openrouter → ProviderChain.java (GPT-OSS 120B)\n\n"
                + "Pour chaque problème, fournis EXACTEMENT ce format Markdown :\n\n"
                + "## Problème : [nom court]\n"
                + "**Fichier concerné** : [NomDuFichier.java — méthode()]\n"
                + "**Cause** : [explication précise en 1-2 phrases]\n"
                + "**Correction** : [ce qu'il faut faire concrètement]\n"
                + "**Prompt Cursor** :\n"
                + "```java\n"
                + "// Dans [NomDuFichier.java], méthode [nomMéthode()] :\n"
                + "// [description précise du changement à faire]\n"
                + "// Exemple : ajouter une vérification avant la ligne X\n"
                + "```\n"
                + "**Priorité** : 🔴 Haute / 🟡 Moyenne / 🟢 Basse\n\n"
                + "RÈGLES IMPORTANTES :\n"
                + "- Toujours identifier le fichier Java précis avec la méthode\n"
                + "- Le Prompt Cursor doit être assez précis pour que Cursor "
                + "sache exactement quoi modifier sans chercher\n"
                + "- Si c'est une permission Android → indiquer le fichier "
                + "AndroidManifest.xml ET les étapes manuelles dans Réglages\n"
                + "- Si c'est le même problème répété → une seule entrée, pas de doublons\n"
                + "- Si la cause est une formulation utilisateur → le dire clairement "
                + "et NE PAS générer de prompt Cursor "
                + "(écrire « (pas de prompt — cause utilisateur) » à la place)\n"
                + "- Priorité 🔴 = bloque une fonctionnalité\n"
                + "- Priorité 🟡 = dégrade l'expérience\n"
                + "- Priorité 🟢 = cosmétique / optionnel\n"
                + "- Utilise le MAPPING FICHIERS ORBE ci-dessus pour pointer le bon fichier\n"
                + "- Traite aussi les hallucinations (passé inventé sans source RAG) "
                + "et les cas où un outil n'a pas été utilisé alors qu'il aurait dû\n";
    }

    private static String completeAnalyzeLlm(Context ctx, String prompt) throws Exception {
        if (analyzeLlmOverride != null) {
            return analyzeLlmOverride.complete(ctx, prompt);
        }
        return PegaseSession.get(ctx).completeDiagAnalyzeSync(prompt);
    }

    static int countProblems(String markdown) {
        if (markdown == null || markdown.isEmpty()) return 0;
        Matcher m = PROBLEM_HEADER.matcher(markdown);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static List<String> problemTitles(String markdown) {
        List<String> out = new ArrayList<>();
        if (markdown == null) return out;
        Matcher m = PROBLEM_HEADER.matcher(markdown);
        while (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static boolean looksLikeStructuredAnalyze(String markdown) {
        return markdown != null && PROBLEM_HEADER.matcher(markdown).find();
    }

    /** Insère l'analyse dans la session bureau du jour. */
    static void noteInBureau(Context ctx, String markdown) {
        String doc = BureauSessionStore.loadToday(ctx);
        String block = "\n\n## Analyse Pégase\n\n"
                + (markdown == null ? "" : markdown.trim()) + "\n";
        String merged = trimTrailingNewlines(doc) + block;
        BureauSessionStore.saveSync(ctx, BureauSessionStore.todayFilename(), merged);
    }

    private static String trimTrailingNewlines(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
