package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.contextstore.ContextSearchIndex;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.qa.OrionQaLoop;
import com.pegasuscorp.orbe.orion.search.FileLocation;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Construction du prompt Orion : contextes + RAG + projet actif + historique récent.
 */
public final class OrionPromptBuilder {

    public static final float RAG_MIN_SCORE = 0.75f;
    public static final int RAG_TOP_K = 3;
    /** Budget historique injecté (chars). */
    public static final int HISTORY_MAX_CHARS = 2800;
    public static final int HISTORY_MAX_TURNS = 4;
    /** Budget snippets fichiers projet (aperçu). */
    public static final int PROJECT_MAX_CHARS = 3200;
    public static final int PROJECT_MAX_FILES = 6;
    public static final int PROJECT_SNIPPET_CHARS = 700;
    /** Budget plus large pour une modif incrémentale (évite de réinventer le site). */
    public static final int PROJECT_EDIT_MAX_CHARS = 48_000;
    public static final int PROJECT_EDIT_MAX_FILES = 10;
    public static final int PROJECT_EDIT_FILE_CHARS = 16_000;
    /** Budget contextes .md chargés (évite d'écraser la demande). */
    public static final int LOADED_MAX_CHARS = 10_000;

    /** Repli seed : nom de fichier mentionné dans la demande brute. */
    private static final Pattern SEED_FILE_PATTERN = Pattern.compile(
            "(?i)\\b([\\w./\\\\-]+\\.(?:js|mjs|cjs|ts|tsx|jsx|html?|css|java|kt|json|md|py|xml))\\b");

    public static final class BuiltPrompt {
        public final String prompt;
        public final int contextChunksUsed;
        /** Blocs mesurés dans {@link #assemble} (hors tools schema API). */
        public final int systemChars;
        public final int historyChars;
        public final int contextChars;
        public final int missionChars;
        /** Sous-blocs de {@link #contextChars}. */
        public final int ragChars;
        public final int projectChars;
        public final int targetedChars;
        public final int riskChars;
        public final int docsMdChars;
        public final int attachedChars;
        /** Mode retenu pour l'assemblage (PATCH | FEATURE). */
        public final String mode;

        public BuiltPrompt(String prompt, int contextChunksUsed) {
            this(prompt, contextChunksUsed, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
        }

        public BuiltPrompt(String prompt, int contextChunksUsed,
                int systemChars, int historyChars, int contextChars, int missionChars,
                int ragChars, int projectChars, int targetedChars,
                int riskChars, int docsMdChars, int attachedChars, String mode) {
            this.prompt = prompt != null ? prompt : "";
            this.contextChunksUsed = Math.max(0, contextChunksUsed);
            this.systemChars = Math.max(0, systemChars);
            this.historyChars = Math.max(0, historyChars);
            this.contextChars = Math.max(0, contextChars);
            this.missionChars = Math.max(0, missionChars);
            this.ragChars = Math.max(0, ragChars);
            this.projectChars = Math.max(0, projectChars);
            this.targetedChars = Math.max(0, targetedChars);
            this.riskChars = Math.max(0, riskChars);
            this.docsMdChars = Math.max(0, docsMdChars);
            this.attachedChars = Math.max(0, attachedChars);
            this.mode = mode != null ? mode : "";
        }
    }

    private OrionPromptBuilder() {}

    public static BuiltPrompt build(Context ctx, JSONObject params) {
        ContextualFileStore store = ContextualFileStore.getInstance(ctx);
        String userPrompt = extractUserPrompt(params);
        String rawDemand = params != null ? params.optString("raw_demand", "").trim() : "";
        OrionMode paramMode = parseModeParam(params);
        String extra = params != null ? params.optString("context", "").trim() : "";
        List<String> loaded = store.getLoadedContexts();
        List<ContextSearchIndex.Hit> relevant = TextUtils.isEmpty(userPrompt)
                ? new ArrayList<>()
                : store.search(userPrompt, RAG_TOP_K, RAG_MIN_SCORE);
        String history = OrionChatHistory.get()
                .formatRecentForPrompt(HISTORY_MAX_TURNS, HISTORY_MAX_CHARS);
        OrionMode modeForProject = resolveModeForPrompt(userPrompt, rawDemand, paramMode);
        boolean wantProjectFiles = looksLikeIncrementalEdit(userPrompt, modeForProject)
                || looksLikeCodeRequest(userPrompt)
                || modeForProject == OrionMode.FEATURE;

        // 1) fileLocation propagé depuis le plan — ne pas re-chercher
        FileLocation propagated = parsePropagatedFileLocation(params);
        // resolveTask : mode / risque / complexité (pas la source de vérité du seed)
        ResolvedTask task = resolveTask(ctx, userPrompt, rawDemand, paramMode);

        String seed = "";
        String targetedFileBlock = "";
        if (propagated != null && !TextUtils.isEmpty(propagated.filename)) {
            seed = basename(propagated.filename);
            targetedFileBlock = propagated.toPromptBlock();
        } else {
            // 2) Repli : nom de fichier explicite dans raw_demand / prompt
            seed = guessSeedFilename(rawDemand, userPrompt);
            if (TextUtils.isEmpty(seed)) {
                seed = seedFilename(task);
            }
            if (task != null && task.fileLocation != null) {
                targetedFileBlock = task.fileLocation.toPromptBlock();
            } else if (!TextUtils.isEmpty(seed)) {
                targetedFileBlock = "Fichier : " + seed + "\n";
            } else {
                targetedFileBlock = buildTargetedFileBlock(ctx, task);
            }
        }

        ProjectContextInject inject = buildProjectContext(ctx, wantProjectFiles, task, seed);
        String projectCtx = inject.text;
        String riskBlock = buildRiskBlock(task);
        if (task != null && (task.complexity == TaskComplexity.LARGE
                || task.complexity == TaskComplexity.MASSIVE)) {
            Trace.orionLargeTask(task.rawInput, task.complexity.name());
        }
        boolean hasLoc = !TextUtils.isEmpty(targetedFileBlock);
        int snippetTokens = hasLoc ? Math.max(1, targetedFileBlock.length() / 4) : 0;
        Trace.orionSandwich("execute", snippetTokens, hasLoc,
                task != null && task.complexity != null ? task.complexity.name() : "OK");
        Trace.orionGraphInject(inject.source, inject.fileCount, inject.chars, inject.seed);
        // Mode retenu = ResolvedTask.mode (déjà fixé) ; fallback detect prompt
        OrionMode retainedMode = (task != null && task.mode != null)
                ? task.mode
                : modeForProject;
        BuiltPrompt built = assemble(loaded, relevant, extra, userPrompt, history, projectCtx,
                targetedFileBlock, riskBlock, retainedMode);
        emitPromptBreakdown(built);
        return built;
    }

    /**
     * Trace tailles par bloc — system API (tool loop) + schema tools inclus.
     * Ne change pas le prompt, mesure seulement.
     */
    static void emitPromptBreakdown(BuiltPrompt built) {
        if (built == null) return;
        int loopSystem = 0;
        try {
            loopSystem = OrionToolLoop.SYSTEM_PROMPT.length();
        } catch (Exception ignored) {
        }
        int toolsSchema = 0;
        try {
            toolsSchema = OrionFileTools.toolSchemas().toString().length();
        } catch (Exception ignored) {
        }
        int system = built.systemChars + loopSystem;
        int total = system + toolsSchema + built.missionChars
                + built.contextChars + built.historyChars;
        try {
            Trace.orionPromptBreakdown(
                    system,
                    toolsSchema,
                    built.missionChars,
                    built.contextChars,
                    built.historyChars,
                    total,
                    built.mode);
        } catch (Exception ignored) {
        }
        try {
            Trace.orionContextBreakdown(
                    built.ragChars,
                    built.projectChars,
                    built.targetedChars,
                    built.riskChars,
                    built.docsMdChars,
                    built.attachedChars,
                    built.mode);
        } catch (Exception ignored) {
        }
    }

    /** Résultat d'injection projet (pour Trace / tests). */
    static final class ProjectContextInject {
        final String text;
        final String source; // related | seed_only | all | none
        final int fileCount;
        final int chars;
        final String seed;

        ProjectContextInject(String text, String source, int fileCount, int chars, String seed) {
            this.text = text != null ? text : "";
            this.source = source != null ? source : "none";
            this.fileCount = Math.max(0, fileCount);
            this.chars = Math.max(0, chars);
            this.seed = seed != null ? seed : "";
        }
    }

    /**
     * Phase 2 graphe : préférer les fichiers {@code /related} au dump massif.
     * Fallback : fichier cible seul, puis tout le projet.
     */
    static ProjectContextInject buildProjectContext(Context ctx, boolean wantFiles,
            ResolvedTask task) {
        return buildProjectContext(ctx, wantFiles, task, null);
    }

    /**
     * @param seedOverride seed déjà décidé (propagé / regex) — prioritaire sur task.
     */
    static ProjectContextInject buildProjectContext(Context ctx, boolean wantFiles,
            ResolvedTask task, String seedOverride) {
        if (!wantFiles) {
            String light = formatActiveProject(ctx, false);
            return new ProjectContextInject(light, TextUtils.isEmpty(light) ? "none" : "preview",
                    countFileHeaders(light), light.length(), "");
        }
        String seed = !TextUtils.isEmpty(seedOverride)
                ? basename(seedOverride.trim())
                : seedFilename(task);
        if (!TextUtils.isEmpty(seed)) {
            List<String> related = Collections.emptyList();
            try {
                related = OrionGraphClient.related(ctx, seed);
            } catch (Exception ignored) {
            }
            if (related != null && !related.isEmpty()) {
                String text = formatActiveProject(ctx, true, related);
                if (!TextUtils.isEmpty(text) && countFileHeaders(text) > 0) {
                    return new ProjectContextInject(text, "related",
                            countFileHeaders(text), text.length(), seed);
                }
            }
            // Hors ligne / graphe vide : au moins le fichier ciblé
            String seedOnly = formatActiveProject(ctx, true,
                    Collections.singletonList(seed));
            if (!TextUtils.isEmpty(seedOnly) && countFileHeaders(seedOnly) > 0) {
                return new ProjectContextInject(seedOnly, "seed_only",
                        countFileHeaders(seedOnly), seedOnly.length(), seed);
            }
        }
        String all = formatActiveProject(ctx, true);
        return new ProjectContextInject(all, TextUtils.isEmpty(all) ? "none" : "all",
                countFileHeaders(all), all.length(), seed != null ? seed : "");
    }

    static String seedFilename(ResolvedTask task) {
        if (task == null) return "";
        if (task.fileLocation != null && !TextUtils.isEmpty(task.fileLocation.filename)) {
            return basename(task.fileLocation.filename);
        }
        if (!TextUtils.isEmpty(task.extractedKeyword)
                && task.extractedKeyword.contains(".")) {
            return basename(task.extractedKeyword.trim());
        }
        return "";
    }

    /** file_location JSON propagé depuis OrionStreamView / plan. */
    static FileLocation parsePropagatedFileLocation(JSONObject params) {
        if (params == null || !params.has("file_location")) return null;
        try {
            JSONObject o = params.optJSONObject("file_location");
            if (o == null) return null;
            String filename = o.optString("filename", "").trim();
            if (filename.isEmpty()) return null;
            int line = o.optInt("line", -1);
            String snippet = o.optString("snippet", "");
            return new FileLocation(filename, line, snippet);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Repli : premier nom de fichier (.js/.html/.css/…) dans la demande brute.
     */
    static String guessSeedFilename(String rawDemand, String userPrompt) {
        String fromRaw = firstFilenameInText(rawDemand);
        if (!TextUtils.isEmpty(fromRaw)) return fromRaw;
        return firstFilenameInText(userPrompt);
    }

    static String firstFilenameInText(String text) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = SEED_FILE_PATTERN.matcher(text);
        if (!m.find()) return "";
        return basename(m.group(1));
    }

    static int countFileHeaders(String projectBlock) {
        if (projectBlock == null || projectBlock.isEmpty()) return 0;
        int n = 0;
        int i = 0;
        while (true) {
            int at = projectBlock.indexOf("--- ", i);
            if (at < 0) break;
            n++;
            i = at + 4;
        }
        return n;
    }

    private static String basename(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/').trim();
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** Assemblage pur — tests sans ONNX / stores. */
    public static BuiltPrompt assemble(List<String> loaded,
            List<ContextSearchIndex.Hit> relevant, String extra, String userPrompt) {
        return assemble(loaded, relevant, extra, userPrompt, null, null);
    }

    public static BuiltPrompt assemble(List<String> loaded,
            List<ContextSearchIndex.Hit> relevant, String extra, String userPrompt,
            String history, String projectFiles) {
        return assemble(loaded, relevant, extra, userPrompt, history, projectFiles, null, null);
    }

    static BuiltPrompt assemble(List<String> loaded,
            List<ContextSearchIndex.Hit> relevant, String extra, String userPrompt,
            String history, String projectFiles, String targetedFileBlock) {
        return assemble(loaded, relevant, extra, userPrompt, history, projectFiles,
                targetedFileBlock, null);
    }

    static BuiltPrompt assemble(List<String> loaded,
            List<ContextSearchIndex.Hit> relevant, String extra, String userPrompt,
            String history, String projectFiles, String targetedFileBlock, String riskBlock) {
        return assemble(loaded, relevant, extra, userPrompt, history, projectFiles,
                targetedFileBlock, riskBlock, null);
    }

    static BuiltPrompt assemble(List<String> loaded,
            List<ContextSearchIndex.Hit> relevant, String extra, String userPrompt,
            String history, String projectFiles, String targetedFileBlock, String riskBlock,
            OrionMode forcedMode) {
        // detect() uniquement sur demande brute — jamais sur mission compilée
        OrionMode mode = forcedMode != null
                ? forcedMode
                : resolveModeForPrompt(userPrompt, null, null);
        boolean feature = mode == OrionMode.FEATURE;
        boolean incremental = looksLikeIncrementalEdit(userPrompt, mode);
        boolean hasProject = !TextUtils.isEmpty(projectFiles)
                && projectFiles.contains("--- ");
        boolean greenfield = looksLikeGreenfieldRequest(userPrompt);
        boolean wantCode = looksLikeCodeRequest(userPrompt)
                || (incremental && hasProject)
                || greenfield
                || (feature && hasProject);
        StringBuilder sb = new StringBuilder();
        int chunks = 0;
        int systemChars = 0;
        int historyChars = 0;
        int ragChars = 0;
        int projectChars = 0;
        int targetedChars = 0;
        int riskChars = 0;
        int docsMdChars = 0;
        int attachedChars = 0;
        int missionChars = 0;

        int mark = sb.length();
        sb.append("=== Qui tu es ===\n")
                .append("Tu es Orion, assistant codeur de l'app Orbe (Pégase).\n")
                .append("Tu comprends le français. Tu suis la demande utilisateur en priorité.\n")
                .append("Stack par défaut : pages web HTML + CSS + JavaScript "
                        + "(pas d'app Android / Java / Kotlin sauf demande claire).\n")
                .append("Si on te parle (salut, question, explication) → réponds en conversation.\n")
                .append("Si on te demande d'écrire / modifier du code → produis des fichiers.\n")
                .append("Ne change pas de sujet. Ne réinvente pas un autre projet.\n")
                .append("Les sections ci-dessous sont du contexte : utilise-les seulement "
                        + "si elles aident la demande.\n\n");
        systemChars += sb.length() - mark;

        if (!TextUtils.isEmpty(history)) {
            mark = sb.length();
            sb.append("=== Historique récent ===\n")
                    .append("Suite la conversation ; « corrige / ajoute / change » "
                            + "porte sur ces tours et les fichiers projet.\n")
                    .append(history.trim()).append("\n\n");
            historyChars += sb.length() - mark;
        }

        if (relevant != null && !relevant.isEmpty()) {
            mark = sb.length();
            sb.append("=== Contexte pertinent ===\n");
            for (ContextSearchIndex.Hit r : relevant) {
                if (r == null || TextUtils.isEmpty(r.chunk)) continue;
                sb.append(r.chunk).append("\n");
                chunks++;
            }
            sb.append('\n');
            ragChars += sb.length() - mark;
        }

        if (!TextUtils.isEmpty(projectFiles)) {
            mark = sb.length();
            sb.append("=== Fichiers projet actif ===\n");
            if (feature && hasProject) {
                sb.append("Projet existant — étends-le pour la fonctionnalité demandée.\n")
                        .append("Plusieurs fichiers autorisés. Conserve ce qui marche déjà.\n");
            } else if (wantCode && hasProject && !greenfield) {
                sb.append("SOURCE DE VÉRITÉ — copie ces fichiers comme base.\n")
                        .append("INTERDIT de repartir de zéro ou de changer l'architecture "
                                + "si la demande est une petite retouche.\n")
                        .append("Change UNIQUEMENT ce qui est demandé ; "
                                + "garde le reste identique (structure, styles, noms, logique).\n");
            } else if (greenfield) {
                sb.append("Projet neuf / premier slice — crée les fichiers nécessaires "
                        + "au slice demandé (pas tout le produit d'un coup).\n");
            } else {
                sb.append("État actuel du projet — modifie / étends ces fichiers "
                        + "plutôt que tout recréer si la demande porte dessus.\n");
            }
            sb.append(projectFiles.trim()).append("\n\n");
            projectChars += sb.length() - mark;
        }

        if (!TextUtils.isEmpty(targetedFileBlock)) {
            mark = sb.length();
            sb.append("=== Fichier ciblé ===\n")
                    .append(targetedFileBlock.trim()).append('\n');
            if (feature) {
                sb.append("Point d'ancrage — tu peux toucher d'autres fichiers si la feature "
                        + "l'exige.\n\n");
            } else {
                sb.append("Modifier UNIQUEMENT ce qui est indiqué.\n")
                        .append("Ne pas toucher au reste du fichier.\n\n");
            }
            targetedChars += sb.length() - mark;
        }

        if (!TextUtils.isEmpty(riskBlock)) {
            mark = sb.length();
            sb.append(riskBlock.trim()).append("\n\n");
            riskChars += sb.length() - mark;
        }

        // Spec .md complète : FEATURE seulement (garde-fou anti-dérive).
        // PATCH : pas d'injection — une correction ciblée n'en a pas besoin.
        boolean hasLoadedDocs = feature && loaded != null && !loaded.isEmpty();
        if (hasLoadedDocs) {
            mark = sb.length();
            sb.append("=== Documents .md chargés ===\n");
            sb.append("Aligne-toi sur la spec ci-dessus ; n'implémente "
                    + "que ce qui y est prévu.\n");
            int used = 0;
            int budget = LOADED_MAX_CHARS;
            for (String c : loaded) {
                if (TextUtils.isEmpty(c)) continue;
                if (used + c.length() > budget) {
                    int remain = budget - used;
                    if (remain > 200) {
                        sb.append(c, 0, remain).append("\n…[tronqué]\n");
                    }
                    break;
                }
                sb.append(c).append("\n\n");
                used += c.length();
            }
            docsMdChars += sb.length() - mark;
        }

        // Pièces jointes du tour (Joindre) — juste avant la demande
        if (!TextUtils.isEmpty(extra)) {
            mark = sb.length();
            sb.append("=== Document joint à ce message ===\n")
                    .append("Lis ce fichier en priorité pour cette demande.\n")
                    .append(extra).append("\n\n");
            attachedChars += sb.length() - mark;
        }

        mark = sb.length();
        sb.append("=== Demande (à satisfaire maintenant) ===\n")
                .append(userPrompt == null ? "" : userPrompt)
                .append("\n\n");
        missionChars += sb.length() - mark;

        mark = sb.length();
        sb.append("=== Format de réponse ===\n");
        if (wantCode) {
            sb.append("Pour chaque fichier produit, utilise un bloc markdown avec le nom :\n")
                    .append("```lang:chemin/NomFichier.ext\n")
                    .append("…code…\n")
                    .append("```\n")
                    .append("Le code doit être récupérable tel quel (pas seulement décrit).\n");
            if (greenfield) {
                sb.append("MODE GREENFIELD (slice) :\n")
                        .append("- Une seule tâche / un slice — pas tout le plan.\n")
                        .append("- Quelques fichiers OK si nécessaires au slice.\n")
                        .append("- Stack par défaut : HTML + CSS + JS "
                                + "(index.html, style.css, app.js / script.js).\n")
                        .append("- Pas de Java / Kotlin / Android sauf demande explicite.\n")
                        .append("- Pas de patch minimal strict : création autorisée.\n");
            } else if (feature && hasProject) {
                sb.append("MODE FEATURE :\n")
                        .append("- Implémente la fonctionnalité demandée.\n")
                        .append("- Plusieurs fichiers autorisés.\n")
                        .append("- Étends le projet existant ; ne réécris pas tout.\n")
                        .append("- N'ajoute rien hors demande");
                if (hasLoadedDocs) {
                    sb.append(" ou hors spec / documents chargés");
                }
                sb.append(".\n");
            } else if (incremental && hasProject) {
                sb.append("MODE PATCH MINIMAL :\n")
                        .append("- Utilise write_file (outils) — pas de fences markdown.\n")
                        .append("- Renvoie le(s) fichier(s) modifié(s) EN ENTIER via write_file, "
                                + "en partant du contenu projet ci-dessus.\n")
                        .append("- Applique seulement la retouche demandée "
                                + "(ex. plus de particules = augmenter le nombre / densité).\n")
                        .append("- Ne casse pas le site : pas de refactor, pas de nouveau design, "
                                + "pas de suppression de sections qui marchaient.\n")
                        .append("- Si un seul fichier suffit (souvent le .js ou .html), "
                                + "n'en modifie qu'un.");
            } else {
                sb.append("Respecte la demande : un seul fichier si un seul est demandé.\n")
                        .append("Stack par défaut : HTML/CSS/JS "
                                + "(pas Java/Kotlin sauf demande explicite).");
            }
        } else {
            sb.append("Mode discussion : réponds en français, clair et utile.\n")
                    .append("Explique, conseille ou discute selon la demande.\n")
                    .append("N'invente pas de fichiers et n'oblige pas de blocs de code.\n")
                    .append("Tu peux montrer un court extrait seulement s'il aide vraiment.\n")
                    .append("Si la demande est ambiguë, pose une question courte.");
        }
        // Règles de format / mode = consignes système (pas la mission utilisateur)
        systemChars += sb.length() - mark;
        int contextChars = ragChars + projectChars + targetedChars
                + riskChars + docsMdChars + attachedChars;
        return new BuiltPrompt(sb.toString(), chunks,
                systemChars, historyChars, contextChars, missionChars,
                ragChars, projectChars, targetedChars,
                riskChars, docsMdChars, attachedChars,
                mode != null ? mode.name() : "");
    }

    /**
     * Création depuis Bureau / premier scaffold — pas le MODE PATCH « un fichier ».
     */
    public static boolean looksLikeGreenfieldRequest(String userPrompt) {
        if (TextUtils.isEmpty(userPrompt)) return false;
        String fold = foldForIntent(userPrompt);
        return fold.contains("mode greenfield")
                || fold.contains("greenfield")
                || fold.contains("projet neuf")
                || fold.contains("creation depuis bureau")
                || fold.contains("premiere tache")
                || fold.contains("un slice minimal")
                || fold.contains("plan ci-joint")
                || fold.contains("implemente le plan")
                || fold.contains("taches non coche")
                || fold.contains("commence par les taches");
    }

    /**
     * Petite retouche sur un projet existant (« plus de X », « ajoute », « augmente »…).
     * Retourne false en MODE FEATURE (comme greenfield).
     */
    public static boolean looksLikeIncrementalEdit(String userPrompt) {
        return looksLikeIncrementalEdit(userPrompt, resolveModeForPrompt(userPrompt, null, null));
    }

    public static boolean looksLikeIncrementalEdit(String userPrompt, OrionMode mode) {
        if (mode == OrionMode.FEATURE) return false;
        if (TextUtils.isEmpty(userPrompt)) return false;
        if (looksLikeGreenfieldRequest(userPrompt)) return false;
        String fold = foldForIntent(userPrompt);
        if (fold.isEmpty()) return false;
        return fold.contains("plus de ")
                || fold.contains("plus des ")
                || fold.contains("moins de ")
                || fold.contains("augmente")
                || fold.contains("diminue")
                || fold.contains("ajoute ")
                || fold.contains("rajoute")
                || fold.contains("modifie ")
                || fold.contains("change ")
                || fold.contains("change le ")
                || fold.contains("change la ")
                || fold.contains("mets plus")
                || fold.contains("met plus")
                || fold.contains("fais plus")
                || fold.contains("rend plus")
                || fold.contains("encore plus")
                || fold.contains("un peu plus")
                || fold.contains("corrige ")
                || fold.contains("repare ")
                || fold.contains("ajuste ")
                || fold.contains("tweake")
                || fold.contains("update ")
                || fold.startsWith("plus ")
                || fold.equals("plus");
    }

    /**
     * True si la demande vise clairement à produire / modifier du code.
     * Sinon → mode discussion (pas d'obligation de blocs fichiers).
     */
    public static boolean looksLikeCodeRequest(String userPrompt) {
        if (TextUtils.isEmpty(userPrompt)) return false;
        String fold = foldForIntent(userPrompt);
        if (fold.isEmpty()) return false;

        // Mot-clé « code » → format fichiers, sauf si c'est clairement une explication
        boolean explain = fold.contains("explique")
                || fold.contains("c est quoi")
                || fold.contains("cest quoi")
                || fold.contains("comment ca marche")
                || fold.contains("comment ca fonctionne")
                || fold.contains("qu est ce que")
                || fold.contains("pourquoi");

        if (containsWord(fold, "code") || containsWord(fold, "coder")
                || containsWord(fold, "coding")) {
            if (explain) return false;
            return true;
        }

        boolean strongCode = fold.contains("genere")
                || fold.contains("implement")
                || fold.contains("implemente")
                || fold.contains("ecris une")
                || fold.contains("ecris un ")
                || fold.contains("ecris le ")
                || fold.contains("ecris la ")
                || fold.contains("cree un fichier")
                || fold.contains("creer un fichier")
                || fold.contains("creer une")
                || fold.contains("cree une")
                || fold.contains("ajoute un fichier")
                || fold.contains("ajoute une fonction")
                || fold.contains("ajoute une classe")
                || fold.contains("refactor")
                || fold.contains("patch")
                || fold.contains("fix le bug")
                || fold.contains("corrige le bug")
                || fold.contains("produis")
                || fold.contains("```")
                || fold.contains("taches non coche")
                || fold.contains("plan ci-joint")
                || fold.contains("implemente le plan")
                || fold.contains("commence par les taches")
                || looksLikeIncrementalEdit(userPrompt);

        if (explain && !strongCode) return false;

        if (strongCode) return true;

        if (fold.contains(".html") || fold.contains(".js") || fold.contains(".css")
                || fold.contains(".java") || fold.contains(".kt") || fold.contains(".py")
                || fold.contains(".tsx") || fold.contains(".ts")) {
            return true;
        }

        if (fold.contains("fonction ") || fold.contains("classe ")
                || fold.contains("methode ") || fold.contains("composant ")) {
            return true;
        }

        if ((fold.contains("html") || fold.contains("css") || fold.contains("javascript"))
                && (fold.contains("page") || fold.contains("site") || fold.contains("fichier")
                || fold.contains("ecris") || fold.contains("fais") || fold.contains("creer")
                || fold.contains("cree"))) {
            return true;
        }

        // Salutations / petite conversation
        if (fold.equals("salut") || fold.equals("bonjour") || fold.equals("hey")
                || fold.equals("merci") || fold.equals("ok") || fold.equals("oui")
                || fold.equals("non") || fold.startsWith("ca va")
                || fold.startsWith("comment tu vas") || fold.startsWith("qui es tu")) {
            return false;
        }

        return false;
    }

    /** Mot entier (évite « encode », « decode »). */
    static boolean containsWord(String fold, String word) {
        if (fold == null || word == null || word.isEmpty()) return false;
        return (" " + fold + " ").contains(" " + word + " ");
    }

    static String foldForIntent(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(java.util.Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a')
                .replace('ô', 'o').replace('ù', 'u').replace('û', 'u')
                .replace('î', 'i').replace('ï', 'i')
                .replace('ç', 'c')
                .replace('’', '\'').replace('\'', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    /** Noms + snippets du projet Orion actif (local = source de vérité). */
    static String formatActiveProject(Context ctx) {
        return formatActiveProject(ctx, false, null);
    }

    /**
     * @param fullForEdit true = injecter le plus possible du fichier (modifs incrémentales)
     */
    static String formatActiveProject(Context ctx, boolean fullForEdit) {
        return formatActiveProject(ctx, fullForEdit, null);
    }

    /**
     * @param onlyFiles si non null / non vide : n'injecte que ces fichiers (ordre conservé).
     *                  Graphe L1 {@code /related} — phase 2.
     */
    static String formatActiveProject(Context ctx, boolean fullForEdit,
            List<String> onlyFiles) {
        if (ctx == null) return "";
        try {
            OrionProjectStore store = OrionProjectStore.get(ctx);
            String active = store.getActiveProject();
            if (TextUtils.isEmpty(active)) return "";
            List<OrionProjectStore.ProjectFile> all = store.getProjectFiles();
            if (all == null || all.isEmpty()) {
                return "Projet : " + active + " (dossier vide)\n";
            }
            List<OrionProjectStore.ProjectFile> files = selectProjectFiles(all, onlyFiles);
            if (files.isEmpty()) {
                return "Projet : " + active + " (aucun fichier lié)\n";
            }
            int maxFiles = fullForEdit ? PROJECT_EDIT_MAX_FILES : PROJECT_MAX_FILES;
            int maxChars = fullForEdit ? PROJECT_EDIT_MAX_CHARS : PROJECT_MAX_CHARS;
            int snipCap = fullForEdit ? PROJECT_EDIT_FILE_CHARS : PROJECT_SNIPPET_CHARS;
            StringBuilder sb = new StringBuilder();
            sb.append("Projet : ").append(active);
            if (onlyFiles != null && !onlyFiles.isEmpty()) {
                sb.append(" [graphe]");
            }
            sb.append('\n');
            int budget = maxChars;
            int n = 0;
            for (OrionProjectStore.ProjectFile pf : files) {
                if (pf == null || TextUtils.isEmpty(pf.name)) continue;
                if (n >= maxFiles || budget <= 80) {
                    sb.append("… +").append(files.size() - n).append(" autres fichiers\n");
                    break;
                }
                String content = store.readFile(pf.name);
                if (content == null) content = "";
                int snip = Math.min(content.length(), Math.min(snipCap, budget - 40));
                sb.append("--- ").append(pf.name)
                        .append(" (").append(pf.lineCount()).append(" l.) ---\n");
                if (snip > 0) {
                    sb.append(content, 0, snip);
                    if (content.length() > snip) sb.append("\n…[tronqué — fichier plus long]");
                    sb.append('\n');
                    budget -= snip;
                }
                n++;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Filtre / ordonne selon {@code onlyFiles} (basenames, ignore case). */
    static List<OrionProjectStore.ProjectFile> selectProjectFiles(
            List<OrionProjectStore.ProjectFile> all, List<String> onlyFiles) {
        if (all == null || all.isEmpty()) return Collections.emptyList();
        if (onlyFiles == null || onlyFiles.isEmpty()) return all;
        List<OrionProjectStore.ProjectFile> out = new ArrayList<>();
        for (String want : onlyFiles) {
            if (TextUtils.isEmpty(want)) continue;
            String key = basename(want).toLowerCase(Locale.ROOT);
            for (OrionProjectStore.ProjectFile pf : all) {
                if (pf == null || TextUtils.isEmpty(pf.name)) continue;
                if (basename(pf.name).toLowerCase(Locale.ROOT).equals(key)) {
                    out.add(pf);
                    break;
                }
            }
        }
        return out;
    }

    static String extractUserPrompt(JSONObject params) {
        if (params == null) return "";
        String userPrompt = params.optString("prompt", "").trim();
        if (userPrompt.isEmpty()) {
            userPrompt = params.optString("query", params.optString("text", "")).trim();
        }
        return userPrompt;
    }

    private static OrionMode parseModeParam(JSONObject params) {
        if (params == null) return null;
        String name = params.optString("orion_mode", "").trim();
        if (name.isEmpty()) return null;
        try {
            return OrionMode.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Mode pour un prompt Orion : param explicite &gt; raw_demand &gt; detect(brut)
     * — jamais detect() sur une mission compilée.
     */
    static OrionMode resolveModeForPrompt(String userPrompt, String rawDemand,
            OrionMode paramMode) {
        if (paramMode != null) return paramMode;
        if (!TextUtils.isEmpty(rawDemand)
                && !PromptCompiler.looksLikeCompiledMission(rawDemand)) {
            return OrionMode.detect(rawDemand);
        }
        if (PromptCompiler.looksLikeCompiledMission(userPrompt)
                || OrionQaLoop.looksLikeMission(userPrompt)) {
            return OrionMode.PATCH;
        }
        return OrionMode.detect(userPrompt);
    }

    private static ResolvedTask resolveTask(Context ctx, String userPrompt,
            String rawDemand, OrionMode paramMode) {
        if (ctx == null || TextUtils.isEmpty(userPrompt)) return null;
        String fold = userPrompt.toLowerCase(Locale.ROOT);
        if (!fold.contains("mission :") && !fold.contains("mission:")
                && !fold.contains("mot-clé ciblé") && !fold.contains("objectif principal")) {
            return null;
        }
        try {
            if (paramMode != null) {
                String source = !TextUtils.isEmpty(rawDemand) ? rawDemand : userPrompt;
                return PromptCompiler.resolve(ctx, userPrompt, source, paramMode);
            }
            if (!TextUtils.isEmpty(rawDemand)
                    && !PromptCompiler.looksLikeCompiledMission(rawDemand)) {
                return PromptCompiler.resolve(ctx, userPrompt, rawDemand);
            }
            // Mission sans origine connue — PATCH, pas de detect sur le compilé
            return PromptCompiler.resolve(ctx, userPrompt, userPrompt, OrionMode.PATCH);
        } catch (Exception e) {
            return null;
        }
    }

    static String buildRiskBlock(ResolvedTask task) {
        if (task == null || task.risk != TaskRisk.CRITICAL) return "";
        return "⚠️ FICHIER CRITIQUE — patch minimal absolu.\n"
                + "Ne modifier QUE ce qui est explicitement demandé.\n"
                + "Zéro refactoring, zéro déplacement de code.";
    }

    private static String buildTargetedFileBlock(Context ctx, ResolvedTask task) {
        if (ctx == null || task == null || TextUtils.isEmpty(task.extractedKeyword)) return "";
        try {
            com.pegasuscorp.orbe.orion.search.FileLocation loc =
                    PromptCompiler.findFileLocation(ctx, task);
            return loc != null ? loc.toPromptBlock() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
