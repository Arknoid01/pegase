package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.contextstore.ContextSearchIndex;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.DiagBehaviorIndex;
import com.pegasuscorp.orbe.llm.PersonalityGuide;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.tools.ToolDispatcher;
import com.pegasuscorp.orbe.tools.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Édition collaborative Markdown (téléphone) — délégué à {@link PegaseSession}.
 * Deux modes : question (réflexion enrichie RAG/mémoire/diag) vs édition.
 */
public final class BureauMarkdownBrain {

    private static final String TAG = "BureauMarkdownBrain";
    private static final int MAX_DOC_CHARS = 20_000;

    /** Actions structurantes du workspace planning. */
    public enum PlanAction {
        STRUCTURER,
        DESIGN_DOC,
        NOTE_TECHNIQUE,
        EXTRACTION_TACHES,
        CHALLENGER
    }

    /** Budget tokens cloud — cahier de conception = doc long. */
    public static final int DESIGN_DOC_MAX_TOKENS = 4000;

    /** null = défaut canal BUREAU (1500). */
    public static Integer maxTokensForAction(PlanAction action) {
        if (action == PlanAction.DESIGN_DOC) return DESIGN_DOC_MAX_TOKENS;
        if (action == PlanAction.NOTE_TECHNIQUE) return 2500;
        if (action == PlanAction.STRUCTURER) return 2500;
        return null;
    }

    private static final float CTX_MIN_SCORE = 0.70f;
    private static final float MEMORY_MIN_SCORE = 0.72f;
    private static final int CTX_TOP_K = 3;
    private static final int MEMORY_TOP_K = 2;
    private static final int DIAG_TOP_K = 2;

    /** Préfixe blockquote des réponses mode question — reste dans le .md. */
    public static final String QUESTION_ANSWER_PREFIX = "> 💡 Pégase : ";

    /** Prompt bureau + métadonnées d'injection (trace {@code context_chunks}). */
    public static final class BuiltPrompt {
        public final String text;
        public final int contextChunks;
        public final boolean questionMode;

        public BuiltPrompt(String text, int contextChunks, boolean questionMode) {
            this.text = text == null ? "" : text;
            this.contextChunks = Math.max(0, contextChunks);
            this.questionMode = questionMode;
        }
    }

    public static final class Result {
        public final BureauMarkdownParser.Parsed parsed;
        public final String raw;

        public Result(BureauMarkdownParser.Parsed parsed, String raw) {
            this.parsed = parsed;
            this.raw = raw == null ? "" : raw;
        }
    }

    public interface Callback {
        void onResult(Result result);
        void onError(String message);
    }

    /** Injecteable pour tests — null = APIs réelles. */
    interface QuestionContextProvider {
        List<String> projectDecisions(Context ctx, String userInput);
        List<String> memories(Context ctx, String userInput);
        List<String> pastApproaches(Context ctx, String userInput);
    }

    static volatile QuestionContextProvider contextProviderOverride = null;

    private BureauMarkdownBrain() {}

    /**
     * Mode question si la demande se termine par {@code ?} ou contient
     * comment / pourquoi / explique / qu'est-ce — avant tout appel LLM.
     */
    public static boolean isQuestion(String userInput) {
        if (userInput == null) return false;
        String trimmed = userInput.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.endsWith("?")) return true;
        String lower = trimmed.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('’', '\'');
        return lower.contains("comment")
                || lower.contains("pourquoi")
                || lower.contains("explique")
                || lower.contains("qu'est-ce")
                || lower.contains("quest-ce");
    }

    /** Alias — même heuristique que {@link #isQuestion(String)}. */
    public static boolean isQuestionMode(String userRequest) {
        return isQuestion(userRequest);
    }

    public static final String DEFAULT_PEGASE_EDIT_REQUEST =
            "Améliore ou structure ce document.";

    /**
     * Requête du bouton Pégase : dernière ligne utile du .md (hors lignes {@code >}),
     * si c'est une question ; sinon édition par défaut.
     */
    public static String resolvePegaseButtonRequest(String document) {
        String line = lastUsefulDocumentLine(document);
        if (line != null && isQuestion(line)) {
            return line;
        }
        return DEFAULT_PEGASE_EDIT_REQUEST;
    }

    /** Dernière ligne non vide qui ne commence pas par {@code >}. */
    static String lastUsefulDocumentLine(String document) {
        if (document == null || document.isEmpty()) return null;
        String[] lines = document.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (t.startsWith(">")) continue;
            return t;
        }
        return null;
    }

    public static String requestForAction(PlanAction action) {
        if (action == null) return DEFAULT_PEGASE_EDIT_REQUEST;
        switch (action) {
            case STRUCTURER:
                return "STRUCTURE ce plan selon le template Bureau : "
                        + "## Vision, ## Objectifs, ## Décisions, ## Tâches (avec - [ ] ), "
                        + "## Questions ouvertes, ## Notes / recherche, ## Historique Pégase. "
                        + "Réordonne le contenu existant dans ces sections sans perdre d'info. "
                        + "Réponds avec ---DOCUMENT--- puis le markdown complet.";
            case DESIGN_DOC:
                return "RÉDIGE un CAHIER DE CONCEPTION (design doc) dense et actionnable "
                        + "à partir de ce document — destiné à un LLM / une équipe de dev.\n"
                        + "Structure OBLIGATOIRE :\n"
                        + "# <titre du projet>\n"
                        + "> Encadré : destinataire, statut (concept / pré-prod / …)\n\n"
                        + "## 1. Pitch\n"
                        + "(1–3 paragraphes : fantasy joueur, ton, référence, points narratifs "
                        + "fixes si récit)\n\n"
                        + "## 2. Stack technique\n"
                        + "Tableau Markdown | Élément | Choix |\n\n"
                        + "## 3. Architecture\n"
                        + "Arbre de dossiers + règles de séparation (ex. logique pure ≠ rendu). "
                        + "Un diagramme ```mermaid``` si ça clarifie.\n\n"
                        + "## 4. Systèmes / mécaniques\n"
                        + "Sections numérotées (4, 5, …) : une par système clé "
                        + "(contraintes, algos, données, pièges).\n\n"
                        + "## Périmètre V1\n"
                        + "Ce qui EST dans la première version jouable — et ce qui est hors scope.\n\n"
                        + "## Jalons de développement\n"
                        + "Liste numérotée de livrables testables, dans l'ordre.\n\n"
                        + "## Points ouverts\n"
                        + "Questions non tranchées — ne les invente pas comme résolues.\n\n"
                        + "Règles :\n"
                        + "- Français, précis, utile — zéro remplissage marketing.\n"
                        + "- Tables pour choix techniques / comparaisons.\n"
                        + "- Énonce les contraintes (offline, perf, mobile, « ne pas faire »).\n"
                        + "- N'invente PAS de features absentes du document ou du contexte "
                        + "injecté : si un trou bloque, mets-le sous Points ouverts.\n"
                        + "- Réponds avec ---DOCUMENT--- puis le markdown complet.";
            case NOTE_TECHNIQUE:
                return "TRANSFORME ce document en NOTE TECHNIQUE pour Orion (modèle codeur). "
                        + "Tu es Pégase (GPT-OSS 120B) : tu sais coder — ajoute des précisions "
                        + "techniques utiles (fichiers, APIs, contraintes) sans inventer de features. "
                        + "Structure OBLIGATOIRE :\n"
                        + "# <titre court>\n"
                        + "## Objectif\n"
                        + "## Contexte technique\n"
                        + "## Fichiers concernés\n"
                        + "- path — rôle\n"
                        + "## À faire\n"
                        + "- [ ] tâches concrètes, une par ligne\n"
                        + "## Ne pas toucher\n"
                        + "- …\n"
                        + "## Critères OK\n"
                        + "- …\n"
                        + "## Notes techniques\n"
                        + "(détails d'implémentation, pièges, APIs)\n"
                        + "## Historique Pégase\n"
                        + "Réordonne le contenu existant sans perdre d'info. "
                        + "Cases - [ ] pour les tâches. Phrases courtes. "
                        + "Réponds avec ---DOCUMENT--- puis le markdown complet.";
            case EXTRACTION_TACHES:
                return "EXTRAIS les tâches implicites du document et liste-les sous ## Tâches "
                        + "en cases - [ ]. Ne duplique pas les tâches déjà présentes. "
                        + "Si besoin, préfixe ---DOCUMENT--- pour le markdown complet, "
                        + "sinon ajoute uniquement le bloc ## Tâches mis à jour.";
            case CHALLENGER:
                return "CHALLENGE ce plan : ajoute sous ## Décisions et ## Notes / recherche "
                        + "(ou ## Points ouverts si présent) des points de friction, risques "
                        + "et questions ouvertes (ton réflexion bureau, pas d'exécution). "
                        + "Garde le reste intact.";
            default:
                return DEFAULT_PEGASE_EDIT_REQUEST;
        }
    }

    public static String buildPromptMarkdown(String documentMarkdown, String userRequest) {
        return buildPromptMarkdown(null, documentMarkdown, userRequest).text;
    }

    public static BuiltPrompt buildPromptMarkdown(Context ctx, String documentMarkdown,
            String userRequest) {
        String doc = documentMarkdown == null ? "" : documentMarkdown.trim();
        if (doc.length() > MAX_DOC_CHARS) {
            doc = doc.substring(0, MAX_DOC_CHARS) + "\n\n…[document tronqué pour le prompt]";
        }
        String req = userRequest == null ? "" : userRequest.trim();
        // Diagramme / tableau / CSV = génération (édition), pas mode question oral
        if (isQuestion(req) && !wantsMermaid(req) && !wantsMarkdownTable(req)
                && !wantsCsv(req)) {
            return buildQuestionPrompt(ctx, doc, req);
        }
        return buildEditPrompt(ctx, doc, req);
    }

    /** Prompt fil Pégase (Q&A) — réponse courte, sans muter le .md côté modèle. */
    public static BuiltPrompt buildThreadPrompt(Context ctx, String documentMarkdown,
            String userRequest) {
        String doc = documentMarkdown == null ? "" : documentMarkdown.trim();
        if (doc.length() > MAX_DOC_CHARS) {
            doc = doc.substring(0, MAX_DOC_CHARS) + "\n\n…[document tronqué pour le prompt]";
        }
        String req = userRequest == null ? "" : userRequest.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("Tu es Pégase, assistante du bureau Markdown d'Yannick (Orbe).\n");
        appendPersonalityBlock(prompt, ctx);
        prompt.append("MODE FIL — conversation liée au plan ouvert.\n\n")
                .append("=== DOCUMENT ACTUEL (contexte) ===\n")
                .append(doc.isEmpty() ? "(vide)\n" : doc + "\n")
                .append("\n=== MESSAGE ===\n")
                .append(req).append("\n");
        int chunks = appendQuestionEnrichment(prompt, ctx, req);
        prompt.append("\nRègles :\n")
                .append("- Réponds en français, 2–6 phrases utiles.\n")
                .append("- Ne renvoie pas le document entier.\n")
                .append("- Si tu proposes du Markdown à insérer, mets-le dans un bloc ```md … ```.\n");
        appendPastReferenceRules(prompt, chunks);
        return new BuiltPrompt(prompt.toString(), chunks, true);
    }

    private static BuiltPrompt buildQuestionPrompt(Context ctx, String doc, String req) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Tu es Pégase, assistante du bureau Markdown d'Yannick (Orbe).\n");
        appendPersonalityBlock(prompt, ctx);
        prompt.append("MODE QUESTION — réflexion, pas juste exécution.\n\n")
                .append("=== DOCUMENT ACTUEL (contexte seul — ne pas le modifier) ===\n")
                .append(doc.isEmpty() ? "(vide)\n" : doc + "\n")
                .append("\n=== QUESTION ===\n")
                .append(req).append("\n");

        int chunks = appendQuestionEnrichment(prompt, ctx, req);

        prompt.append("\nIMPORTANT : Ne répète pas le document dans ta réponse.\n")
                .append("Ne commence pas par \"DOCUMENT\" ou par le contenu du .md.\n")
                .append("Réponds UNIQUEMENT à la question posée, en 2-3 phrases orales.\n")
                .append("Ta réponse sera insérée directement dans le document.\n")
                .append("\nRègles de réponse :\n")
                .append("- Réponds à cette question clairement et brièvement, en français oral.\n")
                .append("- Ne modifie pas le document.\n")
                .append("- Ne recopies jamais le document, ni un titre DOCUMENT, ni le corps du .md.\n")
                .append("- Tu es en mode réflexion dans le bureau — pas juste exécution.\n");
        appendPastReferenceRules(prompt, chunks);
        prompt.append("- Réponds UNIQUEMENT à la question, en 2–3 phrases orales.\n")
                .append("- Préfixe OBLIGATOIRE de ta seule ligne utile : « ")
                .append(QUESTION_ANSWER_PREFIX.trim()).append(" »\n")
                .append("- Pas de ---DOCUMENT---, pas de replace_all, pas de restructuration.\n")
                .append("- Orbe insérera ta ligne juste sous la question dans le .md.\n")
                .append("- Exemple :\n")
                .append(QUESTION_ANSWER_PREFIX);
        if (chunks > 0) {
            prompt.append("Attends — on avait décidé de garder le brief cache-only "
                    + "pour cette raison.\n");
        } else {
            prompt.append("Je propose Z parce que ça évite le conflit avec Y.\n");
        }

        return new BuiltPrompt(prompt.toString(), chunks, true);
    }

    private static void appendPersonalityBlock(StringBuilder prompt, Context ctx) {
        if (prompt == null || ctx == null) return;
        String block = PersonalityGuide.promptBlock(ctx);
        if (block != null && !block.isEmpty()) {
            prompt.append(block);
        }
    }

    /**
     * Règles de contradiction / passé : autorisées seulement si des chunks RAG
     * ont été injectés ; sinon interdiction explicite des formulations inventées.
     */
    static void appendPastReferenceRules(StringBuilder prompt, int contextChunks) {
        if (prompt == null) return;
        if (contextChunks > 0) {
            prompt.append("- Si la question contredit une décision passée → signale-le explicitement.\n")
                    .append("- Si une approche similaire a déjà été abandonnée → dis pourquoi.\n")
                    .append("- Si tu vois un risque ou une meilleure direction → propose-la.\n")
                    .append("- Commence par le doute si pertinent, pas par la réponse.\n")
                    .append("- Exemples : « Attends — on avait décidé X pour cette raison… », ")
                    .append("« Cette approche a déjà été essayée, elle posait un problème de… », ")
                    .append("« Tu es sûr ? Ça risque de conflicter avec Y. », ")
                    .append("« J'aurais plutôt fait Z parce que… ».\n");
        } else {
            prompt.append("- Aucun contexte passé n'est disponible (0 source RAG) — ")
                    .append("n'invente PAS de mémoire ni d'historique.\n")
                    .append("- INTERDIT d'écrire : « on avait essayé », « on a déjà fait », ")
                    .append("« j'avais noté », « tu m'avais dit », « la dernière fois ».\n")
                    .append("- Si tu vois un risque ou une meilleure direction → propose-la ")
                    .append("sans référencer un passé inventé.\n")
                    .append("- Exemples : « Tu es sûr ? Ça risque de conflicter avec Y. », ")
                    .append("« J'aurais plutôt fait Z parce que… ».\n");
        }
    }

    /**
     * Détecte une référence au passé inventée quand aucun chunk RAG n'a été injecté.
     * @see com.pegasuscorp.orbe.diag.HallucinationDetector
     */
    public static boolean isPotentialHallucination(String reply, int contextChunks) {
        return com.pegasuscorp.orbe.diag.HallucinationDetector
                .isPotentialHallucination(reply, contextChunks);
    }

    static String foldForPastPatterns(String text) {
        return com.pegasuscorp.orbe.diag.HallucinationDetector.fold(text);
    }

    /**
     * Injecte RAG contextes / mémoire / diag (questions et éditions).
     * @return nombre total de chunks injectés
     */
    static int appendQuestionEnrichment(StringBuilder prompt, Context ctx, String userInput) {
        if (prompt == null || ctx == null || TextUtils.isEmpty(userInput)) return 0;

        List<String> decisions;
        List<String> memories;
        List<String> past;
        QuestionContextProvider override = contextProviderOverride;
        if (override != null) {
            decisions = safeList(override.projectDecisions(ctx, userInput));
            memories = safeList(override.memories(ctx, userInput));
            past = safeList(override.pastApproaches(ctx, userInput));
        } else {
            decisions = fetchProjectDecisions(ctx, userInput);
            memories = fetchMemories(ctx, userInput);
            past = fetchPastApproaches(ctx, userInput);
        }

        int chunks = 0;
        if (!decisions.isEmpty()) {
            prompt.append("\n=== Décisions projet pertinentes ===\n");
            for (String r : decisions) {
                if (TextUtils.isEmpty(r)) continue;
                prompt.append("- ").append(r.trim().replace('\n', ' ')).append('\n');
                chunks++;
            }
        }
        if (!memories.isEmpty()) {
            prompt.append("\n=== Souvenirs pertinents ===\n");
            for (String m : memories) {
                if (TextUtils.isEmpty(m)) continue;
                prompt.append("- ").append(m.trim().replace('\n', ' ')).append('\n');
                chunks++;
            }
        }
        if (!past.isEmpty()) {
            prompt.append("\n=== Approches déjà essayées ===\n");
            for (String p : past) {
                if (TextUtils.isEmpty(p)) continue;
                prompt.append("- ").append(p.trim().replace('\n', ' ')).append('\n');
                chunks++;
            }
        }
        return chunks;
    }

    private static List<String> fetchProjectDecisions(Context ctx, String userInput) {
        List<String> out = new ArrayList<>();
        try {
            List<ContextSearchIndex.Hit> hits = ContextualFileStore.getInstance(ctx)
                    .search(userInput, CTX_TOP_K, CTX_MIN_SCORE);
            if (hits == null) return out;
            for (ContextSearchIndex.Hit h : hits) {
                if (h == null || TextUtils.isEmpty(h.chunk)) continue;
                String label = h.displayName != null ? h.displayName + " : " : "";
                out.add(label + h.chunk.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "context search skip", e);
        }
        return out;
    }

    private static List<String> fetchMemories(Context ctx, String userInput) {
        List<String> out = new ArrayList<>();
        try {
            List<MemoryEntry> memories = MemoryRepository.getInstance(ctx)
                    .getRelevantMemoriesSemantic(userInput, MEMORY_TOP_K, MEMORY_MIN_SCORE);
            if (memories == null) return out;
            for (MemoryEntry m : memories) {
                if (m == null || TextUtils.isEmpty(m.content)) continue;
                out.add(m.content.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "memory search skip", e);
        }
        return out;
    }

    private static List<String> fetchPastApproaches(Context ctx, String userInput) {
        List<String> out = new ArrayList<>();
        try {
            List<DiagBehaviorIndex.Result> past =
                    DiagBehaviorIndex.searchHits(ctx, userInput, DIAG_TOP_K);
            if (past == null) return out;
            for (DiagBehaviorIndex.Result p : past) {
                if (p == null || TextUtils.isEmpty(p.summary)) continue;
                out.add(p.summary.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "diag search skip", e);
        }
        return out;
    }

    private static List<String> safeList(List<String> in) {
        return in == null ? Collections.emptyList() : in;
    }

    private static BuiltPrompt buildEditPrompt(Context ctx, String doc, String req) {
        boolean csv = wantsCsv(req);
        boolean mermaid = !csv && wantsMermaid(req);
        boolean table = !csv && wantsMarkdownTable(req);
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es Pégase, co-éditrice du bureau Markdown d'Yannick (Orbe).\n");
        if (csv) {
            sb.append("MODE CSV — génère un fichier de données CSV.\n");
        } else if (mermaid) {
            sb.append("MODE DIAGRAMME — génère un schéma Mermaid dans le document.\n");
        } else if (table) {
            sb.append("MODE TABLEAU — génère un tableau Markdown dans le document.\n");
        } else {
            sb.append("MODE ÉDITION — Améliore ou structure ce document.\n");
        }
        sb.append("Français, concis, utile. Tu modifies le document en Markdown propre.\n\n")
                .append("=== DOCUMENT ACTUEL ===\n")
                .append(doc.isEmpty() ? "(vide)\n" : doc + "\n");
        int chunks = appendQuestionEnrichment(sb, ctx, req);
        sb.append("\n=== DEMANDE ===\n")
                .append(req).append("\n\n")
                .append("Règles de réponse :\n")
                .append("- Préfère écrire dans la section pertinente ")
                .append("(## Objectifs / Décisions / Tâches / Notes) plutôt qu'en bas du fichier.\n")
                .append("- Par défaut : réponds UNIQUEMENT en Markdown (pas de prose hors document).\n")
                .append("- CALCUL (× ÷ + − %, marge, CA) : émets UNIQUEMENT le JSON ")
                .append("{\"tool\":\"calculator\",\"params\":{\"expression\":\"…\"}} — ")
                .append("Orbe exécute l'outil et inscrit le résultat dans le .md (jamais le JSON brut).\n")
                .append("- Autres outils : même règle si nécessaire — JSON d'outil seul, Orbe remplace ")
                .append("par le résultat.\n")
                .append("- Lignes commençant par > = phrase lue à voix haute (courte, naturelle), ")
                .append("sauf « > 💡 Pégase : » qui reste aussi dans le document.\n")
                .append("- Le reste = contenu affiché dans le document.\n")
                .append("- Par défaut : ajoute ou modifie UNIQUEMENT la section pertinente ")
                .append("(## titres, - listes, - [ ] / - [x] tâches).\n")
                .append("- INTERDIT de recopier le « DOCUMENT ACTUEL » en entier si tu ajoutes ")
                .append("juste une section, une tâche ou une note.\n")
                .append("- N'efface pas le document sans demande explicite.\n")
                .append("- Si l'utilisateur demande de reformater / structurer TOUT le document, ")
                .append("préfixe ta réponse par ---DOCUMENT--- puis le markdown complet.\n")
                .append("- Si « note technique » / Orion : utilise Objectif, Contexte technique, ")
                .append("Fichiers concernés, À faire (- [ ]), Ne pas toucher, Critères OK, ")
                .append("Notes techniques — précisions de code utiles, sans inventer de scope.\n")
                .append("- Pas de balises parasites (]]] [[ DOCUMENT ACTUEL, fences orphelines).\n")
                .append("- Pour cocher une tâche : reprends la ligne avec - [x].\n")
                .append("- Tableaux Markdown autorisés (| col | col |) — visibles dans l'aperçu.\n")
                .append("- Diagrammes : blocs ```mermaid … ``` (graph TD/LR, sequenceDiagram, etc.) ")
                .append("— rendus dans l'aperçu bureau.\n")
                .append("- CSV : blocs ```csv … ``` — Orbe sauve le fichier dans Fichiers.\n");

        if (csv) {
            sb.append(buildCsvPrompt());
        } else if (mermaid) {
            sb.append(buildMermaidPrompt(req));
        } else if (table) {
            sb.append(buildTablePrompt());
        } else {
            sb.append("- Sur demande (« fais un graphique / schéma / architecture / diagramme ») : ")
                    .append("génère un bloc ```mermaid.\n")
                    .append("- Sur demande (« fais un tableau / compare / colonnes ») : ")
                    .append("génère un tableau Markdown.\n")
                    .append("- Sur demande (« CSV / export csv / fichier csv ») : ")
                    .append("génère un bloc ```csv.\n")
                    .append("- Exemple Markdown :\n")
                    .append("> C'est noté, j'ai ajouté la section Dev.\n")
                    .append("## Dev Pégase\n")
                    .append("- [ ] Morning Routine\n");
        }
        return new BuiltPrompt(sb.toString(), chunks, false);
    }

    /**
     * Instructions MODE DIAGRAMME — type Mermaid adapté à la demande
     * (flowchart, séquence, ER, classes).
     */
    static String buildMermaidPrompt(String userRequest) {
        MermaidKind kind = guessMermaidKind(userRequest);
        StringBuilder sb = new StringBuilder();
        sb.append("\nMODE DIAGRAMME (prioritaire pour cette demande) :\n")
                .append("- Insère un titre ## court puis UN seul bloc ```mermaid … ```.\n")
                .append("- Ferme TOUJOURS le bloc avec ``` sur sa propre ligne.\n")
                .append("- Nœuds / libellés en français, 4–14 éléments, lisible en aperçu.\n")
                .append("- N'enveloppe PAS tout le document dans ```markdown.\n");
        switch (kind) {
            case SEQUENCE:
                sb.append("- Type OBLIGATOIRE : sequenceDiagram (échanges / appels).\n")
                        .append("- Exemple :\n")
                        .append("> Voici la séquence.\n")
                        .append("## Séquence\n\n")
                        .append("```mermaid\n")
                        .append("sequenceDiagram\n")
                        .append("    participant U as Utilisateur\n")
                        .append("    participant P as Pégase\n")
                        .append("    participant O as Orion\n")
                        .append("    U->>P: Demande\n")
                        .append("    P->>O: Génère le code\n")
                        .append("    O-->>P: Fichiers\n")
                        .append("    P-->>U: Résultat\n")
                        .append("```\n");
                break;
            case ER:
                sb.append("- Type OBLIGATOIRE : erDiagram (schéma de données / entités).\n")
                        .append("- Relations claires (||--o{, }o--||, etc.).\n")
                        .append("- Exemple :\n")
                        .append("> Voici le schéma de données.\n")
                        .append("## Schéma données\n\n")
                        .append("```mermaid\n")
                        .append("erDiagram\n")
                        .append("    SESSION ||--o{ TURN : contient\n")
                        .append("    SESSION {\n")
                        .append("        string id\n")
                        .append("        string title\n")
                        .append("    }\n")
                        .append("    TURN {\n")
                        .append("        string role\n")
                        .append("        string text\n")
                        .append("    }\n")
                        .append("```\n");
                break;
            case CLASS:
                sb.append("- Type OBLIGATOIRE : classDiagram (classes / modules).\n")
                        .append("- Exemple :\n")
                        .append("> Voici le diagramme de classes.\n")
                        .append("## Classes\n\n")
                        .append("```mermaid\n")
                        .append("classDiagram\n")
                        .append("    class BureauMarkdownPanel {\n")
                        .append("        +applyPegaseResult()\n")
                        .append("    }\n")
                        .append("    class BureauMarkdownBrain {\n")
                        .append("        +buildPromptMarkdown()\n")
                        .append("    }\n")
                        .append("    BureauMarkdownPanel --> BureauMarkdownBrain\n")
                        .append("```\n");
                break;
            case FLOW:
            default:
                sb.append("- Type OBLIGATOIRE : graph TD ou graph LR (architecture / flux).\n")
                        .append("- Exemple :\n")
                        .append("> Voilà le schéma d'architecture.\n")
                        .append("## Architecture\n\n")
                        .append("```mermaid\n")
                        .append("graph TD\n")
                        .append("    Bureau --> ContexteOrion\n")
                        .append("    ContexteOrion --> Orion\n")
                        .append("    Orion --> GitHub\n")
                        .append("    GitHub --> Hostinger\n")
                        .append("```\n");
                break;
        }
        return sb.toString();
    }

    /** Type de diagramme Mermaid déduit de la demande. */
    enum MermaidKind {
        FLOW, SEQUENCE, ER, CLASS
    }

    static MermaidKind guessMermaidKind(String phrase) {
        if (phrase == null || phrase.isEmpty()) return MermaidKind.FLOW;
        String f = phrase.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('ù', 'u');
        if (f.contains("sequence") || f.contains("echange") || f.contains("appel")
                || (f.contains("flux") && f.contains("message"))) {
            return MermaidKind.SEQUENCE;
        }
        if (f.contains("erdiagram") || f.contains("entite")
                || f.contains("bdd") || f.contains("base de donnee")
                || f.contains("modele de donnee") || f.contains("schema de donnee")
                || f.contains("schema donnees") || f.contains("relationnel")
                || (f.contains("schema") && (f.contains("donnee") || f.contains("table")
                || f.contains("sql") || f.contains("db")))) {
            return MermaidKind.ER;
        }
        if (f.contains("classdiagram") || f.contains("diagramme de classe")
                || (f.contains("classe") && (f.contains("diagram") || f.contains("uml")))) {
            return MermaidKind.CLASS;
        }
        return MermaidKind.FLOW;
    }

    /**
     * Instructions MODE TABLEAU — format Markdown valide obligatoire
     * (en-têtes + ligne {@code |---|---|}).
     */
    static String buildTablePrompt() {
        return "\nMODE TABLEAU (prioritaire pour cette demande) :\n"
                + "Un tableau Markdown VALIDE doit OBLIGATOIREMENT avoir :\n"
                + "1. Ligne d'en-têtes : | Col1 | Col2 | Col3 |\n"
                + "2. Ligne de séparation : |---|---|---|\n"
                + "3. Lignes de données : | val1 | val2 | val3 |\n"
                + "Sans la ligne de séparation → le tableau ne se rend pas.\n"
                + "- Insère un titre ## court puis le tableau complet (en-tête + séparateur + données).\n"
                + "- Colonnes utiles (3–5), états en ✅ / ❌ si pertinent.\n"
                + "- Exemple OBLIGATOIRE à respecter :\n"
                + "> Voici le tableau des priorités.\n"
                + "## Priorités\n\n"
                + "| Étape | Description | État |\n"
                + "|---|---|---|\n"
                + "| 1 | Structure HTML | ❌ |\n"
                + "| 2 | Boucle animation | ❌ |\n";
    }

    /** Instructions MODE CSV — fichier données importable (Excel, Sheets…). */
    static String buildCsvPrompt() {
        return "\nMODE CSV (prioritaire pour cette demande) :\n"
                + "- Extrais ou génère des données structurées depuis le document / la demande.\n"
                + "- Réponds avec : une ligne > courte, un titre ##, puis UN bloc ```csv … ```.\n"
                + "- CSV UTF-8, séparateur virgule, première ligne = en-têtes.\n"
                + "- Escape les champs avec virgule ou guillemets : \"valeur, avec virgule\".\n"
                + "- Pas de Markdown dans le bloc csv (pas de | ni ###).\n"
                + "- 3–12 colonnes, lignes concrètes (pas de lorem ipsum).\n"
                + "- Exemple :\n"
                + "> J'ai préparé l'export CSV.\n"
                + "## Export données\n\n"
                + "```csv\n"
                + "etape,description,etat\n"
                + "1,Structure HTML,todo\n"
                + "2,Boucle animation,todo\n"
                + "```\n"
                + "- Orbe sauvera le .csv dans l'onglet Fichiers et mettra un aperçu dans le plan.\n";
    }

    /** true si le markdown contient déjà une ligne de séparation de tableau. */
    public static boolean hasMarkdownTableSeparator(String markdown) {
        if (markdown == null || markdown.isEmpty()) return false;
        for (String line : markdown.split("\n", -1)) {
            if (isMdTableSeparator(line)) return true;
        }
        return false;
    }

    /**
     * Si un tableau a des en-têtes + données sans {@code |---|}, insère la ligne
     * de séparation automatiquement.
     */
    public static String ensureMarkdownTableSeparators(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown == null ? "" : markdown;
        String[] lines = markdown.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length + 4);
        for (int i = 0; i < lines.length; i++) {
            out.add(lines[i]);
            if (!isMdTableDataRow(lines[i]) || isMdTableSeparator(lines[i])) continue;
            String next = i + 1 < lines.length ? lines[i + 1] : "";
            if (!isMdTableDataRow(next) || isMdTableSeparator(next)) continue;
            String prev = i > 0 ? lines[i - 1] : "";
            boolean startsTable = !isMdTableDataRow(prev) || isMdTableSeparator(prev);
            if (startsTable) {
                out.add(mdTableSeparatorLine(countMdTableColumns(lines[i])));
            }
        }
        return String.join("\n", out);
    }

    static boolean isMdTableDataRow(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.length() < 3 || t.indexOf('|') < 0) return false;
        if (isMdTableSeparator(t)) return false;
        // Au moins deux pipes → cellule
        int pipes = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '|') pipes++;
        }
        return pipes >= 2;
    }

    static boolean isMdTableSeparator(String line) {
        if (line == null) return false;
        String t = line.trim().replace(" ", "");
        if (!t.contains("|") || !t.contains("---")) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '|' && c != '-' && c != ':') return false;
        }
        return true;
    }

    static int countMdTableColumns(String headerLine) {
        if (headerLine == null) return 2;
        String t = headerLine.trim();
        int pipes = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '|') pipes++;
        }
        if (pipes < 2) return 2;
        if (t.startsWith("|") && t.endsWith("|")) {
            return Math.max(2, pipes - 1);
        }
        return Math.max(2, pipes + 1);
    }

    static String mdTableSeparatorLine(int columns) {
        int cols = Math.max(2, columns);
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sb.append("---|");
        }
        return sb.toString();
    }

    /**
     * « fais un graphique », schéma, architecture, diagramme → génération Mermaid.
     */
    public static boolean wantsMermaid(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) return false;
        String f = phrase.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a');
        return f.contains("mermaid")
                || f.contains("graphique") || f.contains("diagramme")
                || f.contains("schema") || f.contains("architecture")
                || f.contains("flowchart") || f.contains("erdiagram")
                || f.contains("sequence diagram") || f.contains("sequencediagram")
                || (f.contains("fais un") && (f.contains("graph") || f.contains("schema")
                || f.contains("diagram") || f.contains("flux")))
                || (f.contains("dessine") && (f.contains("archi") || f.contains("flux")
                || f.contains("schema") || f.contains("processus")));
    }

    /**
     * « fais un tableau », compare, colonnes → tableau Markdown.
     */
    public static boolean wantsMarkdownTable(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) return false;
        if (wantsCsv(phrase)) return false; // CSV prioritaire
        String f = phrase.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e');
        // Schéma de données → Mermaid ER, pas tableau (sauf si « tableau » explicite)
        if (wantsMermaid(phrase) && !f.contains("tableau") && !f.contains("colonne")
                && !f.contains("compare") && !f.contains("comparatif")) {
            return false;
        }
        return f.contains("tableau") || f.contains("colonnes") || f.contains("colonne")
                || f.contains("compare") || f.contains("comparatif")
                || (f.contains("liste") && f.contains("colonne"))
                || (f.contains("fais un") && f.contains("tableau"))
                || (f.contains("tableau des") || f.contains("table des"));
    }

    /**
     * « CSV », export csv, fichier csv → données tabulaires exportables.
     */
    public static boolean wantsCsv(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) return false;
        String f = phrase.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e');
        return f.contains("csv")
                || f.contains("export excel")
                || f.contains("fichier excel")
                || (f.contains("export") && (f.contains("table") || f.contains("donnees")
                || f.contains("donnee")))
                || (f.contains("export") && f.contains("tableau"));
    }

    private static final Pattern CSV_FENCE = Pattern.compile(
            "```csv\\s*\\r?\\n(.*?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Extrait le corps CSV d'une réponse (fence ```csv ou lignes virgule). */
    public static String extractCsvContent(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Matcher m = CSV_FENCE.matcher(markdown);
        if (m.find()) {
            return m.group(1).trim();
        }
        // Fallback : tableau Markdown → CSV
        String fromTable = markdownTableToCsv(markdown);
        if (!fromTable.isEmpty()) return fromTable;
        return "";
    }

    /** Nom de fichier .csv suggéré. */
    public static String suggestedCsvFilename(String userRequest) {
        String base = "export";
        if (userRequest != null) {
            String f = userRequest.toLowerCase(Locale.ROOT)
                    .replace('é', 'e').replace('è', 'e');
            if (f.contains("priorit")) base = "priorites";
            else if (f.contains("tache")) base = "taches";
            else if (f.contains("budget") || f.contains("cout")) base = "budget";
            else if (f.contains("contact")) base = "contacts";
            else if (f.contains("inventaire") || f.contains("stock")) base = "inventaire";
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                .format(new java.util.Date());
        return base + "-" + stamp + ".csv";
    }

    /**
     * Convertit un tableau Markdown en CSV (en-têtes + lignes, ignore |---|).
     */
    public static String markdownTableToCsv(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        List<String> rows = new ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            if (!isMdTableDataRow(line) || isMdTableSeparator(line)) continue;
            String[] cells = splitMdTableCells(line);
            if (cells.length == 0) continue;
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) row.append(',');
                row.append(csvEscape(cells[i]));
            }
            rows.add(row.toString());
        }
        if (rows.size() < 2) return ""; // besoin en-tête + au moins 1 donnée
        return String.join("\n", rows) + "\n";
    }

    static String[] splitMdTableCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /** Aperçu Markdown à partir d'un CSV (pour le .md bureau). */
    public static String csvToMarkdownTable(String csv) {
        if (csv == null || csv.trim().isEmpty()) return "";
        String[] lines = csv.trim().split("\n", -1);
        if (lines.length == 0) return "";
        List<String[]> rows = new ArrayList<>();
        int cols = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cells = parseCsvLine(line);
            rows.add(cells);
            cols = Math.max(cols, cells.length);
        }
        if (rows.isEmpty() || cols == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                String v = c < cells.length ? cells[c] : "";
                sb.append(' ').append(v.replace("|", "/")).append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < cols; c++) sb.append("---|");
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    static String csvEscape(String value) {
        if (value == null) return "";
        String v = value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** Parse simple d'une ligne CSV (guillemets basiques). */
    static String[] parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells.toArray(new String[0]);
    }

    /**
     * Note Markdown après sauvegarde CSV (aperçu tableau + nom fichier).
     */
    public static String csvExportMarkdownNote(String filename, String csvBody) {
        String name = filename == null ? "export.csv" : filename;
        String table = csvToMarkdownTable(csvBody);
        StringBuilder sb = new StringBuilder();
        sb.append("## Export CSV\n\n");
        sb.append("Fichier : `").append(name).append("` — onglet Fichiers.\n\n");
        if (!table.isEmpty()) {
            sb.append(table).append('\n');
        }
        return sb.toString().trim();
    }

    /** true si le texte contient déjà un bloc ```mermaid. */
    public static boolean hasMermaidFence(String markdown) {
        if (markdown == null || markdown.isEmpty()) return false;
        return markdown.contains("```mermaid");
    }

    /**
     * Répare un diagramme Mermaid mal encapsulé (corps sans fence, ou fence non fermée).
     */
    public static String ensureMermaidFence(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown == null ? "" : markdown;
        String text = markdown;
        if (hasMermaidFence(text)) {
            return closeOpenFence(text);
        }
        int bodyAt = indexOfMermaidBody(text);
        if (bodyAt < 0) return text;
        String before = text.substring(0, bodyAt).trim();
        String from = text.substring(bodyAt);
        StringBuilder body = new StringBuilder();
        for (String line : from.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) break;
            body.append(line).append('\n');
        }
        String mermaidBody = body.toString().trim();
        if (mermaidBody.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        if (!before.isEmpty()) out.append(before).append("\n\n");
        out.append("```mermaid\n").append(mermaidBody).append("\n```\n");
        return out.toString();
    }

    static String closeOpenFence(String text) {
        int open = text.indexOf("```mermaid");
        if (open < 0) return text;
        int afterOpen = text.indexOf('\n', open);
        if (afterOpen < 0) return text + "\n```\n";
        int close = text.indexOf("```", afterOpen + 1);
        if (close >= 0) return text;
        return text.trim() + "\n```\n";
    }

    static int indexOfMermaidBody(String text) {
        String[] markers = {
                "graph TD", "graph LR", "graph TB", "graph BT",
                "flowchart TD", "flowchart LR",
                "sequenceDiagram", "erDiagram", "classDiagram", "stateDiagram"
        };
        int best = -1;
        for (String m : markers) {
            int i = text.indexOf(m);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /**
     * Normalise la réponse mode question (préfixe {@code > 💡 Pégase : }).
     * Pas de replace_all — l'UI insère le fragment sous la question via
     * {@link #insertUnderQuestion(String, String, String)}.
     */
    public static BureauMarkdownParser.Parsed finalizeQuestionReply(
            String documentMarkdown, String userRequest,
            BureauMarkdownParser.Parsed parsed) {
        String answer = answerBlockFrom(parsed);
        // Toujours une ligne préfixée ; le \n d'insertion est géré par insertUnderQuestion
        if (!answer.startsWith(">")) {
            answer = QUESTION_ANSWER_PREFIX + stripQuestionPrefix(answer);
        }
        String speak = speakFromAnswer(parsed, answer);
        return new BureauMarkdownParser.Parsed(answer, speak, false);
    }

    static String answerBlockFrom(BureauMarkdownParser.Parsed parsed) {
        if (parsed == null) {
            return QUESTION_ANSWER_PREFIX + "(pas de réponse)";
        }
        String md = parsed.markdown == null ? "" : parsed.markdown.trim();
        if (!md.isEmpty()) {
            return ensureQuestionPrefix(md);
        }
        String speak = parsed.speak == null ? "" : parsed.speak.trim();
        if (!speak.isEmpty()) {
            return QUESTION_ANSWER_PREFIX + stripQuestionPrefix(speak);
        }
        return QUESTION_ANSWER_PREFIX + "(pas de réponse)";
    }

    static String ensureQuestionPrefix(String block) {
        String t = block == null ? "" : block.trim();
        if (t.isEmpty()) return QUESTION_ANSWER_PREFIX + "(pas de réponse)";
        String first = t.split("\n", 2)[0].trim();
        if (first.startsWith("> 💡") || first.startsWith(">💡")) {
            return t;
        }
        if (first.startsWith(">")) {
            String rest = first.startsWith("> ") ? first.substring(2).trim() : first.substring(1).trim();
            rest = stripQuestionPrefix(rest);
            String remainder = t.contains("\n") ? t.substring(t.indexOf('\n')) : "";
            return QUESTION_ANSWER_PREFIX + rest + remainder;
        }
        return QUESTION_ANSWER_PREFIX + stripQuestionPrefix(t);
    }

    static String stripQuestionPrefix(String text) {
        if (text == null) return "";
        String t = text.trim();
        String fold = t;
        if (fold.startsWith("💡")) {
            fold = fold.substring("💡".length()).trim();
        }
        if (fold.regionMatches(true, 0, "Pégase", 0, "Pégase".length())
                || fold.regionMatches(true, 0, "Pegase", 0, "Pegase".length())) {
            int colon = fold.indexOf(':');
            if (colon >= 0 && colon < 12) {
                return fold.substring(colon + 1).trim();
            }
        }
        return t;
    }

    private static String speakFromAnswer(BureauMarkdownParser.Parsed parsed, String answer) {
        if (parsed != null && parsed.speak != null && !parsed.speak.trim().isEmpty()) {
            return stripQuestionPrefix(parsed.speak.trim());
        }
        String line = answer == null ? "" : answer.trim().split("\n", 2)[0].trim();
        if (line.startsWith(">")) {
            line = line.startsWith("> ") ? line.substring(2).trim() : line.substring(1).trim();
        }
        return stripQuestionPrefix(line);
    }

    /**
     * Insère {@code \n> 💡 Pégase : [réponse]} juste sous la question.
     * Retourne le document modifié une seule fois — pas de duplication du corps.
     * Un second appel remplace la réponse Pégase existante sous la question.
     */
    public static String insertUnderQuestion(String document, String question,
            String answerBlock) {
        String doc = document == null ? "" : document;
        String q = question == null ? "" : question.trim();
        String ans = extractSinglePegaseAnswer(answerBlock, doc);
        if (ans.isEmpty()) return doc;

        String[] lines = doc.split("\n", -1);
        int insertAfter = findQuestionLineIndex(lines, q);
        if (insertAfter < 0) {
            StringBuilder sb = new StringBuilder();
            if (!doc.trim().isEmpty()) {
                sb.append(trimTrailingNewlines(doc)).append("\n\n");
            }
            if (!q.isEmpty()) {
                sb.append(q).append('\n');
            }
            sb.append(ans);
            if (!ans.endsWith("\n")) sb.append('\n');
            return assertNoDocumentDuplication(sb.toString(), doc);
        }

        // Réponse Pégase déjà sous la question → remplacer (idempotent)
        int next = insertAfter + 1;
        while (next < lines.length && lines[next].trim().isEmpty()) next++;
        boolean replaceNext = next < lines.length && isPegaseAnswerLine(lines[next]);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            if (replaceNext && i == next) {
                sb.append(ans);
                continue;
            }
            sb.append(lines[i]);
            if (i == insertAfter && !replaceNext) {
                sb.append('\n').append(ans);
            }
        }
        return assertNoDocumentDuplication(sb.toString(), doc);
    }

    /**
     * Une seule ligne {@code > 💡 Pégase : …}. Ignore tout echo du document
     * éventuellement collé dans la réponse LLM.
     */
    static String extractSinglePegaseAnswer(String answerBlock, String document) {
        String raw = answerBlock == null ? "" : answerBlock.trim();
        if (raw.isEmpty()) return "";

        String docTrim = document == null ? "" : document.trim();
        if (!docTrim.isEmpty() && docTrim.length() >= 8 && raw.contains(docTrim)) {
            // La réponse contient une copie du .md → ne garder que les lignes Pégase
            raw = keepPegaseLinesOnly(raw);
        }

        for (String line : raw.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (isPegaseAnswerLine(t)) {
                return t;
            }
        }
        // Première ligne utile, hors contenu document
        for (String line : raw.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (!docTrim.isEmpty() && isLikelyDocumentLine(t, docTrim)) continue;
            if (t.startsWith(">")) {
                String prefixed = ensureQuestionPrefix(t);
                return prefixed.split("\n", 2)[0].trim();
            }
            return QUESTION_ANSWER_PREFIX + stripQuestionPrefix(t);
        }
        return "";
    }

    private static String keepPegaseLinesOnly(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String t = line.trim();
            if (isPegaseAnswerLine(t) || (t.startsWith(">") && t.contains("Pégase"))) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static boolean isLikelyDocumentLine(String line, String document) {
        for (String dl : document.split("\n", -1)) {
            if (dl.trim().equals(line)) return true;
        }
        return false;
    }

    /**
     * Garde-fou : si le résultat contient le document original deux fois, ne
     * conserve que la première occurrence enrichie (question + réponse).
     */
    static String assertNoDocumentDuplication(String result, String originalDoc) {
        if (result == null) return "";
        String doc = originalDoc == null ? "" : originalDoc.trim();
        if (doc.length() < 12) return result;
        int first = indexOfIgnoreTrailingNl(result, doc);
        if (first < 0) return result;
        int second = indexOfIgnoreTrailingNl(result, doc, first + Math.min(doc.length(), 40));
        if (second < 0) return result;
        // Dupliquation détectée → garder jusqu'à la fin de la 1ère copie + éventuelle réponse
        // déjà présente juste après la 1ère occurrence dans result
        return result.substring(0, second).trim() + "\n";
    }

    private static int indexOfIgnoreTrailingNl(String haystack, String needle) {
        return indexOfIgnoreTrailingNl(haystack, needle, 0);
    }

    private static int indexOfIgnoreTrailingNl(String haystack, String needle, int from) {
        if (haystack == null || needle == null || needle.isEmpty()) return -1;
        String n = trimTrailingNewlines(needle);
        return haystack.indexOf(n, Math.max(0, from));
    }

    private static int findQuestionLineIndex(String[] lines, String question) {
        if (lines == null || lines.length == 0) return -1;
        String q = question == null ? "" : question.trim();
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || isPegaseAnswerLine(line)) continue;
            if (!q.isEmpty() && (line.equals(q) || line.contains(q) || q.contains(line))) {
                return i;
            }
            if (!q.isEmpty() && line.indexOf('?') >= 0) {
                // Ne matcher un '?' générique que si proche de la question
                if (foldLoose(line).contains(foldLoose(q))
                        || foldLoose(q).contains(foldLoose(line))) {
                    return i;
                }
            }
        }
        // Dernière ligne interrogative si question vide / introuvable exacte
        if (!q.isEmpty()) {
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty() || isPegaseAnswerLine(line)) continue;
                if (line.indexOf('?') >= 0) return i;
            }
        }
        return -1;
    }

    private static String foldLoose(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isPegaseAnswerLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        return t.startsWith("> 💡") || t.startsWith(">💡");
    }

    private static String trimTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    public static void edit(Context context, String documentMarkdown, String userRequest,
            Callback callback) {
        Context app = context.getApplicationContext();
        PegaseSession session = PegaseSession.get(app);
        if (session.getChannel() != Channel.BUREAU) {
            session.init(new SessionContext(Channel.BUREAU, false));
        }
        session.editBureauMarkdown(documentMarkdown, userRequest, callback);
    }

    /** Repli local quand le LLM est indisponible. */
    public static BureauMarkdownParser.Parsed localFallback(String userRequest) {
        String req = userRequest == null ? "" : userRequest.trim();
        if (req.isEmpty()) {
            return new BureauMarkdownParser.Parsed("", "Je n'ai pas pu modifier le document.", false);
        }
        if (isQuestion(req)) {
            String line = QUESTION_ANSWER_PREFIX
                    + "Je n'ai pas pu répondre pour le moment (modèle indisponible).";
            return new BureauMarkdownParser.Parsed(line,
                    "Je n'ai pas pu répondre pour le moment.", false);
        }
        String fold = req.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e');
        if (fold.startsWith("nouvelle section ") || fold.startsWith("ajoute une section ")) {
            String name = req.replaceFirst("(?i)^(?:nouvelle section|ajoute une section)\\s+", "")
                    .trim();
            if (!name.isEmpty()) {
                String title = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                return new BureauMarkdownParser.Parsed(
                        "## " + title + "\n\n",
                        "Section ajoutée en local.",
                        false);
            }
        }
        return new BureauMarkdownParser.Parsed(
                "- " + req + "\n",
                "Le modèle est indisponible, j'ai noté ta demande.",
                false);
    }

    /** Heuristique : la demande nécessite le LLM (vs raccourcis locaux). */
    public static boolean wantsLlmEdit(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) return false;
        if (isQuestion(phrase)) return true;
        if (wantsMermaid(phrase) || wantsMarkdownTable(phrase) || wantsCsv(phrase)) return true;
        String f = phrase.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e');
        if (f.contains("pegase") || f.contains("structure") || f.contains("formate")
                || f.contains("organise") || f.contains("reforme") || f.contains("resume")
                || f.contains("resumé") || f.contains("tableau") || f.contains("decision")
                || f.contains("décision") || f.contains("coche") || f.contains("marque")
                || f.contains("terminé") || f.contains("termine") || f.contains("comme fait")) {
            return true;
        }
        return f.contains("section") && (f.contains("ajoute") || f.contains("nouvelle"));
    }

    /**
     * Remplace un JSON d'outil LLM par son résultat affichable dans le .md bureau.
     * Priorité : texte outil (ex. calculatrice) — jamais le JSON brut.
     */
    public static String materializeToolResult(String rawLlm, ToolResult result) {
        String toolOut = result != null && result.text != null ? result.text.trim() : "";
        String preamble = ToolDispatcher.stripToolCall(rawLlm).trim();
        if (toolOut.isEmpty()) {
            return materializeToolError(rawLlm, "L'outil n'a rien renvoyé.");
        }
        StringBuilder sb = new StringBuilder();
        if (!preamble.isEmpty()) {
            sb.append(preamble).append('\n');
        }
        if (!preambleHasSpeakLine(preamble)) {
            sb.append("> ").append(toolOut).append('\n');
        }
        sb.append(toolOut);
        return sb.toString().trim();
    }

    public static String materializeToolError(String rawLlm, String error) {
        String preamble = ToolDispatcher.stripToolCall(rawLlm).trim();
        String msg = error == null || error.trim().isEmpty()
                ? "Je n'ai pas pu exécuter l'outil."
                : error.trim();
        StringBuilder sb = new StringBuilder();
        if (!preamble.isEmpty()) {
            sb.append(preamble).append('\n');
        }
        sb.append("> ").append(msg);
        return sb.toString().trim();
    }

    private static boolean preambleHasSpeakLine(String preamble) {
        if (preamble == null || preamble.isEmpty()) return false;
        for (String line : preamble.split("\n", -1)) {
            if (line.trim().startsWith(">")) return true;
        }
        return false;
    }
}
