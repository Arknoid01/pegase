package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BureauMarkdownBrainTest {

    @After
    public void tearDown() {
        BureauMarkdownBrain.contextProviderOverride = null;
    }

    @Test
    public void isQuestion_detectsMarkAndKeywords() {
        assertTrue(BureauMarkdownBrain.isQuestion("C'est quoi le brief ?"));
        assertTrue(BureauMarkdownBrain.isQuestion("Pourquoi le brief ignore diag"));
        assertTrue(BureauMarkdownBrain.isQuestion("Comment ça marche"));
        assertTrue(BureauMarkdownBrain.isQuestion("Explique le prefetch"));
        assertTrue(BureauMarkdownBrain.isQuestion("Qu'est-ce que le brief"));
        assertFalse(BureauMarkdownBrain.isQuestion("Ajoute une section Dev"));
        assertFalse(BureauMarkdownBrain.isQuestion(""));
        assertFalse(BureauMarkdownBrain.isQuestion(null));
    }

    @Test
    public void buildPrompt_questionMode_includesReflectionRules() {
        String prompt = BureauMarkdownBrain.buildPromptMarkdown(
                "# Notes\n- item\n", "C'est quoi le brief ?");
        assertTrue(prompt.contains("MODE QUESTION"));
        assertTrue(prompt.contains("mode réflexion") || prompt.contains("pas juste exécution"));
        // Sans Context / chunks → pas de règles de contradiction passée
        assertTrue(prompt.contains("0 source RAG") || prompt.contains("INTERDIT"));
        assertTrue(prompt.contains("on avait essayé"));
        assertFalse(prompt.contains("décision passée"));
        assertTrue(prompt.contains("Ne répète pas le document"));
        assertTrue(prompt.contains("2-3 phrases orales") || prompt.contains("2–3 phrases orales"));
        assertTrue(prompt.contains("> 💡 Pégase :"));
        assertFalse(prompt.contains("=== Décisions projet pertinentes ==="));
        assertFalse(prompt.contains("Améliore ou structure ce document"));
    }

    @Test
    public void buildPrompt_questionMode_zeroChunks_forbidsPastReferences() {
        Context ctx = RuntimeEnvironment.getApplication();
        BureauMarkdownBrain.contextProviderOverride = new BureauMarkdownBrain.QuestionContextProvider() {
            @Override
            public List<String> projectDecisions(Context c, String userInput) {
                return Collections.emptyList();
            }

            @Override
            public List<String> memories(Context c, String userInput) {
                return Collections.emptyList();
            }

            @Override
            public List<String> pastApproaches(Context c, String userInput) {
                return Collections.emptyList();
            }
        };

        BureauMarkdownBrain.BuiltPrompt built = BureauMarkdownBrain.buildPromptMarkdown(
                ctx, "# Doc\n", "Comment gérer le rebond ?");
        assertTrue(built.questionMode);
        assertEquals(0, built.contextChunks);
        assertTrue(built.text.contains("INTERDIT"));
        assertTrue(built.text.contains("on avait essayé"));
        assertTrue(built.text.contains("on a déjà fait"));
        assertTrue(built.text.contains("j'avais noté"));
        assertTrue(built.text.contains("tu m'avais dit"));
        assertTrue(built.text.contains("la dernière fois"));
        assertFalse(built.text.contains("décision passée"));
        assertFalse(built.text.contains("on avait décidé de garder le brief"));
    }

    @Test
    public void buildPrompt_questionMode_withChunks_allowsPastReferences() {
        Context ctx = RuntimeEnvironment.getApplication();
        BureauMarkdownBrain.contextProviderOverride = new BureauMarkdownBrain.QuestionContextProvider() {
            @Override
            public List<String> projectDecisions(Context c, String userInput) {
                return Collections.singletonList("Décision : brief cache-only");
            }

            @Override
            public List<String> memories(Context c, String userInput) {
                return Collections.emptyList();
            }

            @Override
            public List<String> pastApproaches(Context c, String userInput) {
                return Collections.emptyList();
            }
        };

        BureauMarkdownBrain.BuiltPrompt built = BureauMarkdownBrain.buildPromptMarkdown(
                ctx, "# Doc\n", "Pourquoi le brief ignore le réseau ?");
        assertTrue(built.questionMode);
        assertTrue(built.contextChunks > 0);
        assertTrue(built.text.contains("décision passée"));
        assertTrue(built.text.contains("brief cache-only"));
        assertTrue(built.text.contains("on avait décidé"));
        assertFalse(built.text.contains("INTERDIT d'écrire"));
    }

    @Test
    public void isPotentialHallucination_zeroChunks_detectsPastPatterns() {
        assertTrue(BureauMarkdownBrain.isPotentialHallucination(
                "On avait essayé ça l'an dernier.", 0));
        assertTrue(BureauMarkdownBrain.isPotentialHallucination(
                "On a déjà fait cette approche.", 0));
        assertTrue(BureauMarkdownBrain.isPotentialHallucination(
                "J'avais noté que le sync échouait.", 0));
        assertTrue(BureauMarkdownBrain.isPotentialHallucination(
                "Tu m'avais dit de garder le brief.", 0));
        assertTrue(BureauMarkdownBrain.isPotentialHallucination(
                "La dernière fois ça plantait.", 0));
        assertFalse(BureauMarkdownBrain.isPotentialHallucination(
                "Je propose d'utiliser requestAnimationFrame.", 0));
    }

    @Test
    public void isPotentialHallucination_withChunks_allowsPastReferences() {
        assertFalse(BureauMarkdownBrain.isPotentialHallucination(
                "On avait essayé le sync réseau — abandonné pour X.", 2));
        assertFalse(BureauMarkdownBrain.isPotentialHallucination(
                "Tu m'avais dit de garder le brief cache-only.", 1));
    }

    @Test
    public void buildPrompt_questionMode_injectsThreeSourcesWhenPresent() {
        Context ctx = RuntimeEnvironment.getApplication();
        BureauMarkdownBrain.contextProviderOverride = new BureauMarkdownBrain.QuestionContextProvider() {
            @Override
            public List<String> projectDecisions(Context c, String userInput) {
                return Collections.singletonList("Décision : brief cache-only");
            }

            @Override
            public List<String> memories(Context c, String userInput) {
                return Collections.singletonList("Yannick préfère le doute avant la réponse");
            }

            @Override
            public List<String> pastApproaches(Context c, String userInput) {
                return Arrays.asList("notepad (échec) — text manquant");
            }
        };

        BureauMarkdownBrain.BuiltPrompt built = BureauMarkdownBrain.buildPromptMarkdown(
                ctx, "# Doc\n", "Pourquoi on a abandonné le sync réseau ?");
        assertTrue(built.questionMode);
        assertEquals(3, built.contextChunks);
        assertTrue(built.text.contains("=== Décisions projet pertinentes ==="));
        assertTrue(built.text.contains("brief cache-only"));
        assertTrue(built.text.contains("=== Souvenirs pertinents ==="));
        assertTrue(built.text.contains("=== Approches déjà essayées ==="));
        assertTrue(built.text.contains("notepad"));
        assertTrue(built.text.contains("décision passée"));
        assertFalse(built.text.contains("INTERDIT d'écrire"));
    }

    @Test
    public void buildPrompt_editMode_includesRagWhenPresent() {
        Context ctx = RuntimeEnvironment.getApplication();
        BureauMarkdownBrain.contextProviderOverride = new BureauMarkdownBrain.QuestionContextProvider() {
            @Override
            public List<String> projectDecisions(Context c, String userInput) {
                return Collections.singletonList("Décision plan: cache-only");
            }

            @Override
            public List<String> memories(Context c, String userInput) {
                return Collections.singletonList("Souvenir bureau");
            }

            @Override
            public List<String> pastApproaches(Context c, String userInput) {
                return Collections.emptyList();
            }
        };

        BureauMarkdownBrain.BuiltPrompt built = BureauMarkdownBrain.buildPromptMarkdown(
                ctx, "# Notes\n", "Ajoute une section Dev");
        assertFalse(built.questionMode);
        assertEquals(2, built.contextChunks);
        assertTrue(built.text.contains("Décision plan: cache-only"));
        assertTrue(built.text.contains("Souvenir bureau"));
        assertTrue(built.text.contains("Améliore ou structure"));
        assertTrue(built.text.contains("section pertinente"));
    }

    @Test
    public void requestForAction_structurer_asksDocumentReplace() {
        String req = BureauMarkdownBrain.requestForAction(
                BureauMarkdownBrain.PlanAction.STRUCTURER);
        assertTrue(req.contains("STRUCTURE"));
        assertTrue(req.contains("---DOCUMENT---"));
        assertTrue(req.contains("Objectifs"));
        assertTrue(req.contains("Vision"));
        assertTrue(req.contains("Questions ouvertes"));
    }

    @Test
    public void requestForAction_designDoc_asksPitchStackArchitecture() {
        String req = BureauMarkdownBrain.requestForAction(
                BureauMarkdownBrain.PlanAction.DESIGN_DOC);
        assertTrue(req.contains("CAHIER DE CONCEPTION"));
        assertTrue(req.contains("---DOCUMENT---"));
        assertTrue(req.contains("Pitch"));
        assertTrue(req.contains("Stack"));
        assertTrue(req.contains("Architecture"));
        assertTrue(req.contains("Jalons"));
        assertTrue(req.contains("Points ouverts"));
        assertTrue(req.contains("N'invente PAS") || req.contains("n'invente PAS")
                || req.toLowerCase().contains("n'invente pas"));
        assertEquals(Integer.valueOf(BureauMarkdownBrain.DESIGN_DOC_MAX_TOKENS),
                BureauMarkdownBrain.maxTokensForAction(
                        BureauMarkdownBrain.PlanAction.DESIGN_DOC));
    }

    @Test
    public void requestForAction_noteTechnique_asksOrionReadySections() {
        String req = BureauMarkdownBrain.requestForAction(
                BureauMarkdownBrain.PlanAction.NOTE_TECHNIQUE);
        assertTrue(req.contains("NOTE TECHNIQUE"));
        assertTrue(req.contains("---DOCUMENT---"));
        assertTrue(req.contains("Contexte technique"));
        assertTrue(req.contains("Ne pas toucher"));
        assertTrue(req.contains("Critères OK"));
        assertTrue(req.contains("Fichiers concernés"));
        assertTrue(req.contains("Notes techniques"));
    }

    @Test
    public void insertUnderQuestion_preservesRestOfDocument() {
        String doc = "# Notes\n\nC'est quoi le brief ?\n\n## Autre\n- garde-moi\n";
        String out = BureauMarkdownBrain.insertUnderQuestion(doc,
                "C'est quoi le brief ?",
                "> 💡 Pégase : Le brief agrège météo et diag.");
        assertTrue(out.contains("C'est quoi le brief ?\n> 💡 Pégase : Le brief agrège météo et diag."));
        assertTrue(out.contains("## Autre"));
        assertTrue(out.indexOf("Pégase") < out.indexOf("## Autre"));
        assertEquals(1, countOccurrences(out, "# Notes"));
    }

    @Test
    public void insertUnderQuestion_twice_doesNotDuplicateDocument() {
        String doc = "# Projet Balle HTML\n\ncomment gérer le rebond d'une balle en html ?\n";
        String q = "comment gérer le rebond d'une balle en html ?";
        String once = BureauMarkdownBrain.insertUnderQuestion(doc, q,
                "> 💡 Pégase : En JavaScript avec requestAnimationFrame.");
        String twice = BureauMarkdownBrain.insertUnderQuestion(once, q,
                "> 💡 Pégase : Utilise une vélocité Y inversée au sol.");

        assertEquals(1, countOccurrences(twice, "# Projet Balle HTML"));
        assertEquals(1, countOccurrences(twice, "comment gérer le rebond d'une balle en html ?"));
        assertEquals(1, countOccurrences(twice, "> 💡 Pégase :"));
        assertTrue(twice.contains("vélocité") || twice.contains("velocite")
                || twice.contains("Utilise une"));
        assertFalse(twice.contains("requestAnimationFrame"));
        // Pas de concaténation doc+doc
        assertFalse(twice.contains("# Projet Balle HTML\n\ncomment gérer le rebond d'une balle en html ?\n\n# Projet Balle HTML"));
    }

    @Test
    public void insertUnderQuestion_stripsDocumentEchoFromAnswer() {
        String doc = "# Notes\n\nPourquoi le brief ?\n";
        String echoed = doc + "\n> 💡 Pégase : Parce que c'est le matin.\n" + doc;
        String out = BureauMarkdownBrain.insertUnderQuestion(doc, "Pourquoi le brief ?", echoed);
        assertEquals(1, countOccurrences(out, "# Notes"));
        assertTrue(out.contains("> 💡 Pégase : Parce que c'est le matin."));
        assertTrue(out.contains("Pourquoi le brief ?\n> 💡 Pégase :"));
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    @Test
    public void finalizeQuestionReply_noReplaceAll_answerFragmentOnly() {
        BureauMarkdownParser.Parsed parsed = new BureauMarkdownParser.Parsed(
                "> 💡 Pégase : Oui, clairement.", "Oui, clairement.", false);
        BureauMarkdownParser.Parsed out = BureauMarkdownBrain.finalizeQuestionReply(
                "# Doc\nPourquoi ?\n", "Pourquoi ?", parsed);
        assertFalse(out.replaceAll);
        assertEquals("> 💡 Pégase : Oui, clairement.", out.markdown.trim());
        assertFalse(out.markdown.contains("# Doc"));
    }

    @Test
    public void wantsMermaid_andTable_detectTriggers() {
        assertTrue(BureauMarkdownBrain.wantsMermaid("Pégase, fais un graphique de l'architecture"));
        assertTrue(BureauMarkdownBrain.wantsMermaid("dessine le schéma Orion"));
        assertTrue(BureauMarkdownBrain.wantsMermaid("un diagramme du flux"));
        assertTrue(BureauMarkdownBrain.wantsMarkdownTable("fais un tableau des priorités"));
        assertTrue(BureauMarkdownBrain.wantsMarkdownTable("compare les outils en colonnes"));
        assertFalse(BureauMarkdownBrain.wantsMermaid("Ajoute une section Dev"));
        assertTrue(BureauMarkdownBrain.wantsLlmEdit("fais un schéma d'architecture"));
        assertTrue(BureauMarkdownBrain.wantsLlmEdit("fais un tableau comparatif"));
    }

    @Test
    public void buildPrompt_mermaidMode_asksForMermaidBlock() {
        String prompt = BureauMarkdownBrain.buildPromptMarkdown(
                "# Projet\n", "fais un graphique de l'architecture Pégase");
        assertTrue(prompt.contains("MODE DIAGRAMME") || prompt.contains("mermaid"));
        assertTrue(prompt.contains("```mermaid"));
        assertTrue(prompt.contains("graph TD") || prompt.contains("graph LR"));
        assertFalse(prompt.contains("MODE QUESTION"));
    }

    @Test
    public void guessMermaidKind_detectsSequenceAndEr() {
        assertEquals(BureauMarkdownBrain.MermaidKind.SEQUENCE,
                BureauMarkdownBrain.guessMermaidKind("fais un diagramme de séquence des appels"));
        assertEquals(BureauMarkdownBrain.MermaidKind.ER,
                BureauMarkdownBrain.guessMermaidKind("schéma de données des sessions"));
        assertEquals(BureauMarkdownBrain.MermaidKind.FLOW,
                BureauMarkdownBrain.guessMermaidKind("architecture Orion"));
    }

    @Test
    public void buildPrompt_erSchema_asksForErDiagram() {
        String prompt = BureauMarkdownBrain.buildPromptMarkdown(
                "# Projet\n", "fais un schéma de données des entités session");
        assertTrue(prompt.contains("erDiagram"));
        assertTrue(prompt.contains("MODE DIAGRAMME"));
    }

    @Test
    public void ensureMermaidFence_wrapsBareGraph() {
        String raw = "## Archi\n\ngraph TD\n    A --> B\n";
        String fixed = BureauMarkdownBrain.ensureMermaidFence(raw);
        assertTrue(BureauMarkdownBrain.hasMermaidFence(fixed));
        assertTrue(fixed.contains("```mermaid"));
        assertTrue(fixed.contains("A --> B"));
    }

    @Test
    public void ensureMermaidFence_closesOpenFence() {
        String raw = "```mermaid\ngraph TD\n    A --> B\n";
        String fixed = BureauMarkdownBrain.ensureMermaidFence(raw);
        assertTrue(fixed.trim().endsWith("```"));
    }

    @Test
    public void buildPrompt_tableMode_asksForMarkdownTable() {
        String prompt = BureauMarkdownBrain.buildPromptMarkdown(
                "# Projet\n", "fais un tableau des priorités");
        assertTrue(prompt.contains("MODE TABLEAU") || prompt.contains("|---|"));
        assertTrue(prompt.contains("tableau") || prompt.contains("|"));
        assertTrue(prompt.contains("OBLIGATOIREMENT") || prompt.contains("ligne de séparation"));
        assertTrue(prompt.contains("|---|---|---|"));
    }

    @Test
    public void buildTablePrompt_requiresSeparatorLine() {
        String p = BureauMarkdownBrain.buildTablePrompt();
        assertTrue(p.contains("Ligne d'en-têtes"));
        assertTrue(p.contains("Ligne de séparation"));
        assertTrue(p.contains("|---|---|---|"));
        assertTrue(p.contains("ne se rend pas"));
    }

    @Test
    public void ensureMarkdownTableSeparators_insertsMissingSeparator() {
        String raw = "## Priorités\n\n"
                + "| Étape | Description | État |\n"
                + "| 1 | Structure HTML | ❌ |\n"
                + "| 2 | Boucle animation | ❌ |\n";
        assertFalse(BureauMarkdownBrain.hasMarkdownTableSeparator(raw));
        String fixed = BureauMarkdownBrain.ensureMarkdownTableSeparators(raw);
        assertTrue(BureauMarkdownBrain.hasMarkdownTableSeparator(fixed));
        assertTrue(fixed.contains("| Étape | Description | État |\n|---|---|---|\n| 1 |"));
    }

    @Test
    public void wantsCsv_detectsExport() {
        assertTrue(BureauMarkdownBrain.wantsCsv("exporte en CSV"));
        assertTrue(BureauMarkdownBrain.wantsCsv("fais un fichier csv des tâches"));
        assertTrue(BureauMarkdownBrain.wantsCsv("export excel du budget"));
        assertFalse(BureauMarkdownBrain.wantsCsv("fais un tableau des priorités"));
        assertFalse(BureauMarkdownBrain.wantsMarkdownTable("exporte en CSV"));
    }

    @Test
    public void buildPrompt_csvMode_asksForCsvFence() {
        String prompt = BureauMarkdownBrain.buildPromptMarkdown(
                "# Projet\n## Tâches\n- [ ] A\n", "exporte ce plan en CSV");
        assertTrue(prompt.contains("MODE CSV"));
        assertTrue(prompt.contains("```csv"));
        assertFalse(prompt.contains("MODE QUESTION"));
    }

    @Test
    public void extractCsvContent_fromFence() {
        String md = "> ok\n## Export\n\n```csv\na,b\n1,2\n```\n";
        assertEquals("a,b\n1,2", BureauMarkdownBrain.extractCsvContent(md));
    }

    @Test
    public void markdownTableToCsv_andBack() {
        String table = "| Étape | État |\n|---|---|\n| 1 | todo |\n| 2 | done |\n";
        String csv = BureauMarkdownBrain.markdownTableToCsv(table);
        assertTrue(csv.contains("Étape,État") || csv.contains("Etape"));
        assertTrue(csv.contains("1,todo"));
        String md = BureauMarkdownBrain.csvToMarkdownTable(csv);
        assertTrue(md.contains("|"));
        assertTrue(md.contains("---|"));
    }

    @Test
    public void ensureMarkdownTableSeparators_leavesValidTableAlone() {
        String raw = "| A | B |\n|---|---|\n| 1 | 2 |\n";
        assertEquals(raw, BureauMarkdownBrain.ensureMarkdownTableSeparators(raw));
    }

    @Test
    public void wantsLlmEdit_trueForQuestions() {
        assertTrue(BureauMarkdownBrain.wantsLlmEdit("C'est quoi ?"));
        assertTrue(BureauMarkdownBrain.wantsLlmEdit("Explique le brief"));
    }
}
