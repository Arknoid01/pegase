package com.pegasuscorp.orbe.diag;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Façade diag : bilan / analyse / corrections.
 * IO → {@link DiagParser} ; NL → {@link DiagNlGenerator}.
 */
public final class DiagSynthesizer {

    /** Prefetch brief / archive 1 jour sans anomalie — jamais d'appel LLM. */
    public static final String NOTHING_TO_REPORT_YESTERDAY =
            "Rien à signaler hier — tout s'est bien passé.";

    /**
     * Zéro donnée ≠ succès (même motif que lint {@code ok:true} sans config).
     * Jamais « tout s'est bien passé » quand le jsonl est absent / vide.
     */
    public static final String NO_TRACES_AVAILABLE =
            "Aucune trace disponible.";

    private DiagSynthesizer() {}

    /** Bilan — aujourd'hui par défaut. */
    public static String summary(Context ctx) {
        return summary(ctx, java.time.LocalDate.now());
    }

    /**
     * Bilan pour une date civile.
     * Ordre : (1) agrégat journalier — même famille que brief ;
     * (2) traces détaillées si encore présentes ;
     * (3) « aucune donnée » seulement si les deux sont vides.
     */
    public static String summary(Context ctx, java.time.LocalDate day) {
        if (ctx != null) Trace.init(ctx);
        java.time.LocalDate target = day != null ? day : java.time.LocalDate.now();
        scheduleDiagVectorization(ctx);

        List<JSONObject> detail = loadDetailForDay(target);
        DiagDayAggregate agg = resolveAggregate(target, detail);

        // Les deux vides → aucune donnée pour cette date
        if (agg.isEmpty() && detail.isEmpty()) {
            return "Aucune donnée pour " + DiagDayAggregate.formatDayLabel(target) + ".";
        }

        // Agrégat seul → une ligne factuelle, sans excuse répétée
        if (!agg.isEmpty() && detail.isEmpty()) {
            return agg.factualNoDetailLine(target);
        }

        // Détail présent → synthèse ; réconciliation si l'agrégat est plus riche
        String coverage = coveragePreface(detail, agg);
        String reconcile = reconcileNote(detail.size(), agg);

        if (!DiagNlGenerator.statsOf(detail).hasIssues()) {
            // Jour passé ou détail partiel sans friction
            if (!target.equals(java.time.LocalDate.now()) && reconcile.isEmpty()) {
                // Archive complète sans friction — aligné brief RAS
                return NOTHING_TO_REPORT_YESTERDAY;
            }
            StringBuilder sb = new StringBuilder();
            if (!coverage.isEmpty()) sb.append(coverage).append(' ');
            sb.append("RAS sur les traces conservées (")
                    .append(detail.size())
                    .append(detail.size() > 1 ? " événements" : " événement")
                    .append(").");
            if (!reconcile.isEmpty()) {
                sb.append(' ').append(reconcile);
                if (agg.hasIssues()) {
                    sb.append(' ').append(agg.factualNoDetailLine(target));
                }
            }
            return sb.toString().trim();
        }

        JSONObject report = target.equals(java.time.LocalDate.now())
                ? DiagParser.ensureReport(ctx) : null;
        String body = DiagNlGenerator.synthesizeSummary(detail, report);
        return prependCoverage(coverage, reconcile, body);
    }

    /**
     * Récit des erreurs d'un jour depuis l'agrégat ({@code error_details}).
     * Langage clair — pas l'état du stockage.
     */
    public static String detail(Context ctx, java.time.LocalDate day) {
        if (ctx != null) Trace.init(ctx);
        java.time.LocalDate target = day != null ? day : java.time.LocalDate.now();
        DiagDayAggregate agg = DiagDayAggregate.load(target);
        if (agg == null || (agg.isEmpty() && agg.issueCount() <= 0
                && (agg.errorDetails == null || agg.errorDetails.isEmpty()))) {
            // Agrégat vide : tenter dérivation live uniquement pour ne pas mentir
            List<JSONObject> live = loadDetailForDay(target);
            if (live != null && !live.isEmpty()) {
                agg = DiagDayAggregate.fromDetailEvents(target, live);
                // fromDetailEvents n'a pas error_details — dire si compteurs > 0
                if (agg.issueCount() > 0) {
                    return agg.issueCount() + " erreur"
                            + (agg.issueCount() > 1 ? "s" : "")
                            + " enregistrée" + (agg.issueCount() > 1 ? "s" : "")
                            + " le " + DiagDayAggregate.formatDayLabel(target)
                            + ", détail non conservé";
                }
            }
            return "aucune erreur enregistrée le "
                    + DiagDayAggregate.formatDayLabel(target);
        }
        return agg.narrateErrorDetails(target);
    }

    /**
     * Agrégat : fichier journalier (ou snapshot daté) en priorité ;
     * si vide mais détail présent, compteurs dérivés du détail
     * (ne remplace pas le fichier agrégat).
     */
    static DiagDayAggregate resolveAggregate(java.time.LocalDate day,
            List<JSONObject> detail) {
        DiagDayAggregate agg = DiagDayAggregate.load(day);
        if (!agg.isEmpty()) return agg;
        if (detail != null && !detail.isEmpty()) {
            return DiagDayAggregate.fromDetailEvents(day, detail);
        }
        return DiagDayAggregate.empty(day);
    }

    /** Aujourd'hui = jsonl vivant ; jour passé = archive datée. */
    static List<JSONObject> loadDetailForDay(java.time.LocalDate day) {
        if (day == null) day = java.time.LocalDate.now();
        if (day.equals(java.time.LocalDate.now())) {
            return DiagParser.withoutStress(DiagParser.readTraceEvents());
        }
        java.io.File archive = Trace.archiveFile(day);
        return DiagParser.withoutStress(DiagParser.readEvents(archive));
    }

    static String summaryWhenNoLiveTraces(DiagDayAggregate agg) {
        if (agg == null || agg.isEmpty()) {
            return NO_TRACES_AVAILABLE;
        }
        java.time.LocalDate d;
        try {
            d = java.time.LocalDate.parse(agg.date);
        } catch (Exception e) {
            d = java.time.LocalDate.now();
        }
        return agg.factualNoDetailLine(d);
    }

    static String coveragePreface(List<JSONObject> events, DiagDayAggregate agg) {
        long t = DiagDayAggregate.earliestTs(events, agg);
        String hm = DiagDayAggregate.formatHm(t);
        if (hm.isEmpty()) return "";
        // Ne préfixer la fenêtre que si l'agrégat indique un trou (détail partiel)
        if (agg != null && agg.events > 0 && events != null
                && events.size() < agg.events) {
            return "Traces disponibles depuis " + hm + " seulement.";
        }
        return "";
    }

    static String reconcileNote(int liveCount, DiagDayAggregate agg) {
        if (agg == null) return "";
        String line = agg.reconcileLine(liveCount);
        if (line.isEmpty()) return "";
        return "(" + line + ")";
    }

    static String prependCoverage(String coverage, String reconcile, String body) {
        StringBuilder sb = new StringBuilder();
        if (coverage != null && !coverage.isEmpty()) sb.append(coverage);
        if (reconcile != null && !reconcile.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(reconcile);
        }
        if (sb.length() > 0) sb.append('\n').append('\n');
        sb.append(body == null ? "" : body);
        return sb.toString().trim();
    }

    /** Alias doc / tool — même logique que {@link #summary(Context)}. */
    public static String summarize(Context ctx) {
        return summary(ctx);
    }

    /** Moments d'hésitation / mauvaise interprétation (events existants). */
    public static String hesitations(Context ctx) {
        DiagParser.ensureReport(ctx);
        scheduleDiagVectorization(ctx);
        return DiagNlGenerator.synthesizeHesitations(DiagParser.readTraceEvents());
    }

    /** Échecs d'outils / erreurs LLM / fallbacks bureau. */
    public static String failures(Context ctx) {
        DiagParser.ensureReport(ctx);
        scheduleDiagVectorization(ctx);
        return DiagNlGenerator.synthesizeFailures(
                DiagParser.readTraceEvents(), DiagParser.loadReportQuiet());
    }

    /** Vectorise tool_hesitation / tool_failure_ctx → VectorStore ns diag. */
    private static void scheduleDiagVectorization(Context ctx) {
        if (ctx != null) DiagBehaviorIndex.indexFromTracesAsync(ctx);
    }

    /**
     * Bilan sur les N derniers jours via {@code diag/archives/trace-YYYY-MM-DD.jsonl}
     * (+ trace du jour courante). Utilisé par {@code diag action=weekly}.
     */
    public static String summarizeArchive(int daysBack) {
        return summarizeArchive(null, daysBack);
    }

    public static String summarizeArchive(Context ctx, int daysBack) {
        if (ctx != null) Trace.init(ctx);
        int days = Math.max(1, Math.min(daysBack, Trace.ARCHIVE_RETENTION_DAYS));
        List<DiagParser.DayBucket> daysFound = DiagParser.loadArchiveDays(days);
        scheduleDiagVectorization(ctx);
        // Aucune archive / trace → absence de données, pas un succès
        if (daysFound.isEmpty()) {
            return NO_TRACES_AVAILABLE;
        }
        // Jours présents mais 0 anomalie → RAS (brief / prefetch)
        if (!hasAnyIssues(daysFound)) {
            return NOTHING_TO_REPORT_YESTERDAY;
        }
        return DiagNlGenerator.synthesizeWeekly(daysFound, days);
    }

    /** true si au moins une friction / anomalie dans les buckets. */
    static boolean hasAnyIssues(List<? extends DiagParser.DayBucket> days) {
        if (days == null || days.isEmpty()) return false;
        for (DiagParser.DayBucket day : days) {
            if (day == null || day.events == null) continue;
            DiagNlGenerator.DayStats st = DiagNlGenerator.statsOf(day.events);
            if (st.hasIssues()) return true;
        }
        return false;
    }

    /** Raccourci tool : 7 jours. */
    public static String weekly(Context ctx) {
        return summarizeArchive(ctx, Trace.ARCHIVE_RETENTION_DAYS);
    }

    /**
     * Analyse déterministe → {@code corrections.md} (sans LLM).
     * Pour l'analyse QA vocale / outil, préférer {@link com.pegasuscorp.orbe.tools.knowledge.DiagTool}.
     */
    public static String analyze(Context ctx) {
        if (ctx != null) Trace.init(ctx);
        List<JSONObject> events = DiagParser.withoutStress(DiagParser.readTraceEvents());
        if (events.isEmpty()) {
            DiagDayAggregate agg = DiagDayAggregate.load();
            return summaryWhenNoLiveTraces(agg);
        }
        JSONObject report = DiagParser.ensureReport(ctx);
        scheduleDiagVectorization(ctx);
        List<String> problems = collectCorrectionProblems(events, report);
        if (problems.isEmpty()) {
            return NOTHING_TO_REPORT_YESTERDAY;
        }
        int added = CorrectionsStore.mergePending(ctx, problems);
        int pending = CorrectionsStore.countPending(ctx);
        if (added == 0) {
            return "Analyse terminée. Aucun nouveau problème "
                    + "(déjà " + pending + " en attente dans corrections.md).";
        }
        return "Analyse terminée. J'ai ajouté " + added + " problème"
                + (added > 1 ? "s" : "") + " à corrections.md. "
                + pending + " en attente au total.";
    }

    /**
     * Markdown structuré de repli (sans LLM) à partir des anomalies / hésitations.
     */
    public static String fallbackAnalyzeMarkdown(Context ctx) {
        if (ctx != null) Trace.init(ctx);
        JSONObject report = DiagParser.ensureReport(ctx);
        List<String> problems = collectCorrectionProblems(DiagParser.readTraceEvents(), report);
        return buildFallbackAnalyzeMarkdown(problems);
    }

    public static String buildFallbackAnalyzeMarkdown(List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seenKeys = new java.util.LinkedHashSet<>();
        int i = 0;
        for (String p : problems) {
            if (p == null || p.trim().isEmpty()) continue;
            String key = dedupeKey(p);
            if (!seenKeys.add(key)) continue; // pas de doublons
            i++;
            String title = shortProblemTitle(p);
            String priority = p.toLowerCase(Locale.ROOT).contains("high")
                    || p.contains("[high]") ? "🔴 Haute" : "🟡 Moyenne";
            FileHint hint = mapProblemToFile(p);
            sb.append("## Problème : ").append(title).append('\n')
                    .append("**Fichier concerné** : ").append(hint.fileLine).append('\n')
                    .append("**Cause** : ").append(hint.cause != null ? hint.cause : p.trim())
                    .append('\n')
                    .append("**Correction** : ").append(hint.correction).append('\n');
            if (hint.userFormulation) {
                sb.append("**Prompt Cursor** : (pas de prompt — cause utilisateur)\n");
            } else {
                sb.append("**Prompt Cursor** :\n```java\n")
                        .append(hint.cursorPrompt).append('\n')
                        .append("```\n");
            }
            sb.append("**Priorité** : ").append(priority).append("\n\n");
            if (i >= 8) break;
        }
        return sb.toString().trim();
    }

    private static String dedupeKey(String problem) {
        String t = problem.toLowerCase(Locale.ROOT);
        if (t.contains("phantom")) return "phantom";
        if (t.contains("notepad")) return "notepad";
        if (t.contains("create_file") || t.contains("create-file")) return "create_file";
        if (t.contains("files") || t.contains("permission") || t.contains("stockage")) {
            return "files";
        }
        if (t.contains("timer") || t.contains("minuteur")) return "timer";
        if (t.contains("calculator") || t.contains("calcul")) return "calculator";
        if (t.contains("open_app") || t.contains("ouvre")) return "open_app";
        if (t.contains("search") || t.contains("tavily")) return "search";
        if (t.contains("orion")) return "orion";
        if (t.contains("repeated") || t.contains("répét")) return "repeated";
        if (t.contains("hallucin")) return "hallucination";
        if (t.contains("latence") || t.contains("timeout")) return "latency";
        if (t.contains("fallback") || t.contains("scout")) return "fallback";
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static final class FileHint {
        final String fileLine;
        final String cause;
        final String correction;
        final String cursorPrompt;
        final boolean userFormulation;

        FileHint(String fileLine, String cause, String correction, String cursorPrompt,
                boolean userFormulation) {
            this.fileLine = fileLine;
            this.cause = cause;
            this.correction = correction;
            this.cursorPrompt = cursorPrompt;
            this.userFormulation = userFormulation;
        }
    }

    /** Mapping déterministe bilan → fichier (même table que le prompt LLM). */
    static FileHint mapProblemToFile(String problem) {
        String t = problem == null ? "" : problem.toLowerCase(Locale.ROOT);
        if (t.contains("phantom") || t.contains("fantôme") || t.contains("fantome")) {
            return new FileHint(
                    "ConversationManager.java — guardPhantom()",
                    problem.trim(),
                    "Appliquer / renforcer guardPhantom() sur toute prose d'action sans outil.",
                    "// Dans ConversationManager.java, méthode guardPhantom() :\n"
                            + "// Bloquer claimsAction (ex. « j'ai noté ») si !toolFired, "
                            + "tous backends (Groq / Cerebras / OpenRouter 120B).",
                    false);
        }
        if ((t.contains("notepad") || t.contains("bloc-notes") || t.contains("bloc notes"))
                && (t.contains("introuvable") || t.contains("formulation")
                || t.contains("utilisateur") || t.contains("ambiguous")
                || t.contains("ambigu"))) {
            return new FileHint(
                    "NotepadTool.java — description() / NotepadEditor.java",
                    "Cause probable : formulation utilisateur, pas un bug code.",
                    "Ne pas traiter comme un défaut code — clarifier la demande utilisateur.",
                    "",
                    true);
        }
        if (t.contains("notepad")) {
            return new FileHint(
                    "NotepadTool.java — execute() + NotepadEditor.java",
                    problem.trim(),
                    "Vérifier le routage add et la description anti-faux-positifs.",
                    "// Dans NotepadTool.java / NotepadEditor.java :\n"
                            + "// Corriger le cas : " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("create_file")) {
            return new FileHint("CreateFileTool.java — execute()", problem.trim(),
                    "Corriger la génération / le partage du fichier.",
                    "// Dans CreateFileTool.java, méthode execute() :\n"
                            + "// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("files") || (t.contains("permission") && t.contains("stock"))
                || t.contains("manage_external") || t.contains("tous les fichiers")) {
            return new FileHint(
                    "AndroidManifest.xml — MANAGE_EXTERNAL_STORAGE + permission runtime",
                    problem.trim(),
                    "Vérifier le manifest et guider l'utilisateur : Réglages → Apps → Orbe "
                            + "→ Accès à tous les fichiers.",
                    "// Dans AndroidManifest.xml : vérifier MANAGE_EXTERNAL_STORAGE.\n"
                            + "// Étapes manuelles : Réglages → Applications → Orbe → "
                            + "Autorisations → Accès à tous les fichiers = Autorisé.",
                    false);
        }
        if (t.contains("timer") || t.contains("minuteur")) {
            return new FileHint("TimerTool.java — execute()", problem.trim(),
                    "Vérifier anti-doublon et lancement AlarmClock.",
                    "// Dans TimerTool.java, méthode execute() :\n"
                            + "// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("calculator") || t.contains("calculatrice")) {
            return new FileHint(
                    "CalculatorTool.java — execute() + BureauCalcHelper.java",
                    problem.trim(),
                    "Corriger le parsing / l'évaluation de l'expression.",
                    "// Dans CalculatorTool.java / BureauCalcHelper.java :\n"
                            + "// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("open_app") || t.contains("openapp")) {
            return new FileHint("OpenAppTool.java — execute() (alias manquant)",
                    problem.trim(),
                    "Ajouter l'alias d'application manquant.",
                    "// Dans OpenAppTool.java : ajouter l'alias manquant.",
                    false);
        }
        if (t.contains("search") || t.contains("tavily")) {
            return new FileHint("TavilySearchService.java", problem.trim(),
                    "Vérifier clé API / requête / parsing réponse.",
                    "// Dans TavilySearchService.java :\n// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("orion")) {
            return new FileHint("OrionManagerTool.java — execute()", problem.trim(),
                    "Vérifier start/stop/status et confirmations.",
                    "// Dans OrionManagerTool.java :\n// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("repeated") || t.contains("répét") || t.contains("repet")) {
            return new FileHint("ContextAnalyzer.java + outil concerné",
                    problem.trim(),
                    "Désambiguïser l'intention ou bloquer la répétition d'outil.",
                    "// Dans ContextAnalyzer / description de l'outil :\n"
                            + "// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        if (t.contains("hallucin") || t.contains("context_chunks")) {
            return new FileHint(
                    "BureauMarkdownBrain.java — buildQuestionPrompt()",
                    problem.trim(),
                    "Injecter / exiger des context_chunks RAG avant une affirmation au passé.",
                    "// Dans BureauMarkdownBrain.buildQuestionPrompt() :\n"
                            + "// Empêcher une affirmation passée si context_chunks=0.",
                    false);
        }
        if (t.contains("latence") || t.contains("timeout") || t.contains("> 5")) {
            return new FileHint("GroqChatBackend.java — timeout",
                    problem.trim(),
                    "Ajuster connect/read timeout ou diagnostiquer la lenteur réseau.",
                    "// Dans GroqChatBackend.java : revoir les timeouts.",
                    false);
        }
        if (t.contains("fallback") || t.contains("cerebras") || t.contains("openrouter")
                || t.contains("120b") || t.contains("scout")) {
            return new FileHint(
                    "ProviderChain.java + CloudModelStore.groqFallbackChain()",
                    problem.trim(),
                    "Vérifier Groq → Cerebras → OpenRouter (même GPT-OSS 120B), sans Scout.",
                    "// Dans ProviderChain / CloudModelStore :\n"
                            + "// " + DiagNlGenerator.preview(problem, 80),
                    false);
        }
        return new FileHint("(à identifier dans le code)", problem.trim(),
                "Examiner les traces et corriger ce point.",
                "// Corriger dans Orbe : " + DiagNlGenerator.preview(problem, 80),
                false);
    }

    private static String shortProblemTitle(String problem) {
        String t = problem.trim();
        int dash = t.indexOf(" — ");
        if (dash < 0) dash = t.indexOf(" - ");
        if (dash > 0 && dash < 60) t = t.substring(0, dash).trim();
        if (t.length() > 60) t = t.substring(0, 57) + "…";
        return t;
    }

    /** Problèmes actionnables extraits du rapport + traces. */
    static List<String> collectCorrectionProblems(List<JSONObject> events, JSONObject report) {
        List<String> out = new ArrayList<>();
        if (report != null) {
            JSONArray anomalies = report.optJSONArray("anomalies");
            if (anomalies != null) {
                for (int i = 0; i < anomalies.length(); i++) {
                    JSONObject a = anomalies.optJSONObject(i);
                    if (a == null) continue;
                    String type = a.optString("type");
                    String expl = DiagNlGenerator.preview(a.optString("explanation"), 160);
                    String sev = a.optString("severity");
                    String label = DiagNlGenerator.humanAnomaly(type);
                    StringBuilder line = new StringBuilder(label);
                    if (!sev.isEmpty()) line.append(" [").append(sev).append(']');
                    if (!expl.isEmpty()) line.append(" — ").append(expl);
                    String problem = line.toString().trim();
                    if (!problem.isEmpty() && !DiagNlGenerator.alreadyMentions(out, problem)) {
                        out.add(problem);
                    }
                }
            }
        }
        if (events != null) {
            for (JSONObject e : events) {
                if (e == null) continue;
                String type = e.optString("type");
                if ("tool_hesitation".equals(type)) {
                    String tool = e.optString("tool");
                    String detail = DiagNlGenerator.preview(e.optString("detail"), 100);
                    if (detail.isEmpty()) {
                        detail = DiagNlGenerator.preview(e.optString("reason"), 100);
                    }
                    String line = "hésitation " + (tool.isEmpty() ? "outil" : tool)
                            + (detail.isEmpty() ? "" : " — " + detail);
                    if (!DiagNlGenerator.alreadyMentions(out, line)) out.add(line);
                } else if ("tool_failure_ctx".equals(type)
                        || ("tool_end".equals(type) && e.optBoolean("error", false))) {
                    String tool = e.optString("tool");
                    String detail = DiagNlGenerator.preview(
                            e.optString("detail", e.optString("reason")), 100);
                    String line = "échec " + (tool.isEmpty() ? "outil" : tool)
                            + (detail.isEmpty() ? "" : " — " + detail);
                    if (!DiagNlGenerator.alreadyMentions(out, line)) out.add(line);
                } else if ("reasoning_card".equals(type)
                        && e.optBoolean("potentialHallucination", false)) {
                    String line = hallucinationCorrectionLine(e);
                    if (!DiagNlGenerator.alreadyMentions(out, line)) out.add(line);
                } else if ("bureau_edit".equals(type)
                        && e.optBoolean("potentialHallucination", false)) {
                    String speak = DiagNlGenerator.preview(e.optString("speak"), 60);
                    String line = "Hallucination passé sans source [high] — bureau"
                            + (speak.isEmpty() ? "" : " (« " + speak + " »)")
                            + " — conditionner les références au passé au contexte RAG";
                    if (!DiagNlGenerator.alreadyMentions(out, line)) out.add(line);
                }
            }
        }
        return out;
    }

    private static String hallucinationCorrectionLine(JSONObject e) {
        String reason = DiagNlGenerator.preview(e.optString("hallucination_reason"), 80);
        String intent = e.optString("intent", "");
        StringBuilder line = new StringBuilder(
                "Hallucination passé sans source [high]");
        if (!intent.isEmpty()) line.append(" (").append(intent).append(')');
        if (!reason.isEmpty()) line.append(" — ").append(reason);
        else line.append(" — conditionner « on avait… » au contexte RAG "
                + "(HallucinationDetector / prompts)");
        return line.toString();
    }

    // ---- délégations package-private pour tests / DiagBehaviorIndex ----

    static String synthesizeSummary(List<JSONObject> events, JSONObject report) {
        return DiagNlGenerator.synthesizeSummary(events, report);
    }

    static String synthesizeWeekly(List<DiagParser.DayBucket> days, int requestedDays) {
        return DiagNlGenerator.synthesizeWeekly(days, requestedDays);
    }

    static String synthesizeHesitations(List<JSONObject> events) {
        return DiagNlGenerator.synthesizeHesitations(events);
    }

    static String synthesizeFailures(List<JSONObject> events, JSONObject report) {
        return DiagNlGenerator.synthesizeFailures(events, report);
    }

    static List<DiagParser.DayBucket> loadArchiveDays(int daysBack) {
        return DiagParser.loadArchiveDays(daysBack);
    }

    static List<JSONObject> readTraceEvents() {
        return DiagParser.readTraceEvents();
    }

    static List<JSONObject> readEvents(java.io.File f) {
        return DiagParser.readEvents(f);
    }
}
