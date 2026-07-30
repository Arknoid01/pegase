package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionPromptBuilderTest {

    @Test
    public void looksLikeCodeRequest_chatIsFalse() {
        assertFalse(OrionPromptBuilder.looksLikeCodeRequest("salut"));
        assertFalse(OrionPromptBuilder.looksLikeCodeRequest("bonjour Orion"));
        assertFalse(OrionPromptBuilder.looksLikeCodeRequest("tu peux m'expliquer l'idée ?"));
        assertFalse(OrionPromptBuilder.looksLikeCodeRequest("merci"));
        assertFalse(OrionPromptBuilder.looksLikeCodeRequest("encode cette chaîne"));
    }

    @Test
    public void looksLikeCodeRequest_codeKeywordForcesFormat() {
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("code une page"));
        assertFalse(OrionPromptBuilder.looksLikeCodeRequest("explique ce code"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("corrige mon code"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("code moi un hello world"));
    }

    @Test
    public void looksLikeCodeRequest_incrementalEdit() {
        assertTrue(OrionPromptBuilder.looksLikeIncrementalEdit("fais plus de particules"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("fais plus de particules"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("augmente le nombre de particules"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("ajoute des étoiles"));
    }

    @Test
    public void assemble_incremental_withProject_usesPatchMode() {
        String project = "Projet : balle\n--- index.html (20 l.) ---\n<html></html>\n";
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "plus de particules", null, project);
        assertTrue(built.prompt.contains("MODE PATCH MINIMAL"));
        assertTrue(built.prompt.contains("SOURCE DE VÉRITÉ"));
        assertTrue(built.prompt.contains("```lang:chemin/NomFichier.ext"));
    }

    @Test
    public void assemble_featureWord_usesFeatureMode_notPatch() {
        String project = "Projet : app\n--- timer.js (10 l.) ---\nlet t=0;\n";
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.singletonList("# Spec\n- bouton pause"),
                Collections.emptyList(), "",
                "ajoute une feature timer", null, project);
        assertTrue(built.prompt.contains("MODE FEATURE"));
        assertFalse(built.prompt.contains("MODE PATCH MINIMAL"));
        assertTrue(built.prompt.contains("Aligne-toi sur la spec"));
    }

    @Test
    public void assemble_featureWithApostrophe_isFeature() {
        String project = "Projet : app\n--- index.html (5 l.) ---\n<html></html>\n";
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "ajoute l'écran de réglages en feature", null, project);
        assertTrue(built.prompt.contains("MODE FEATURE"));
        assertFalse(built.prompt.contains("MODE PATCH MINIMAL"));
    }

    @Test
    public void assemble_featureCapitalized_isFeature() {
        String project = "Projet : app\n--- a.js (1 l.) ---\nx\n";
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "Feature : bouton pause", null, project);
        assertTrue(built.prompt.contains("MODE FEATURE"));
    }

    @Test
    public void looksLikeIncrementalEdit_falseWhenFeature() {
        assertFalse(OrionPromptBuilder.looksLikeIncrementalEdit(
                "ajoute une feature de pause"));
        assertTrue(OrionPromptBuilder.looksLikeIncrementalEdit("plus de particules"));
        assertTrue(OrionPromptBuilder.looksLikeIncrementalEdit("corrige le compteur"));
    }

    @Test
    public void assemble_targetedFileBlock_addsStrictScopedHint() {
        String project = "Projet : balle\n--- ball.js (20 l.) ---\nlet particleCount = 50;\n";
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "Mission : patch particules", null, project,
                "Fichier : ball.js · Ligne 23\n-> let particleCount = 50;", null);
        assertTrue(built.prompt.contains("=== Fichier ciblé ==="));
        assertTrue(built.prompt.contains("Modifier UNIQUEMENT ce qui est indiqué"));
        assertTrue(built.prompt.contains("ball.js"));
        assertTrue(built.prompt.contains("Ligne 23"));
    }

    @Test
    public void assemble_criticalRiskBlock_warnsMinimalPatch() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch critique")
                .risk(TaskRisk.CRITICAL)
                .build();
        String risk = OrionPromptBuilder.buildRiskBlock(task);
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "Mission : patch critique", null, null, null, risk);
        assertTrue(built.prompt.contains("FICHIER CRITIQUE"));
        assertTrue(built.prompt.contains("patch minimal"));
    }

    @Test
    public void assemble_identity_present() {
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "", "salut");
        assertTrue(built.prompt.contains("Tu es Orion"));
        assertTrue(built.prompt.contains("Demande (à satisfaire maintenant)"));
    }

    @Test
    public void looksLikeCodeRequest_codeIsTrue() {
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("génère index.html"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("implémente une fonction hello"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("écris un fichier Main.java"));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest(
                "MODE GREENFIELD (création depuis Bureau). N'implémente QUE la première tâche."));
        assertTrue(OrionPromptBuilder.looksLikeCodeRequest("crée une page HTML pour la balle"));
    }

    @Test
    public void looksLikeGreenfieldRequest_bureauBridge() {
        assertTrue(OrionPromptBuilder.looksLikeGreenfieldRequest(
                "MODE GREENFIELD (création depuis Bureau).\n"
                        + "N'implémente QUE la première tâche utile.\n"
                        + "un slice minimal et compilable."));
        assertTrue(OrionPromptBuilder.looksLikeGreenfieldRequest(
                "Implémente le plan ci-joint. Commence par les tâches non cochées."));
        assertFalse(OrionPromptBuilder.looksLikeIncrementalEdit(
                "MODE GREENFIELD — première tâche : scaffold MainActivity"));
    }

    @Test
    public void assemble_greenfield_usesSliceMode_notPatch() {
        String project = "Projet : demo (dossier vide)\n";
        String prompt = "MODE GREENFIELD (création depuis Bureau).\n"
                + "N'implémente QUE la première tâche utile : « scaffold MainActivity ».\n"
                + "Pas tout le plan d'un coup — un slice minimal et compilable.\n"
                + "Langage : HTML + CSS + JS — PAS de Java/Kotlin.";
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.singletonList("# Plan\n" + "x".repeat(5000)),
                Collections.emptyList(), "", prompt, null, project);
        assertTrue(built.prompt.contains("MODE GREENFIELD"));
        assertFalse(built.prompt.contains("MODE PATCH MINIMAL"));
        assertTrue(built.prompt.contains("HTML"));
        assertTrue(built.prompt.contains("…[tronqué]")
                || built.prompt.length() < 20_000);
    }

    @Test
    public void assemble_codeKeyword_keepsFileFormat() {
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "code une landing page");
        assertTrue(built.prompt.contains("Le code doit être récupérable"));
        assertTrue(built.prompt.contains("```lang:chemin/NomFichier.ext"));
    }

    @Test
    public void assemble_chat_noForcedCodeFormat() {
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "", "salut");
        assertFalse(built.prompt.contains("Le code doit être récupérable"));
        assertTrue(built.prompt.contains("conversation")
                || built.prompt.contains("Réponds en français"));
    }

    @Test
    public void assemble_code_keepsFileFormat() {
        OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.assemble(
                Collections.emptyList(), Collections.emptyList(), "",
                "génère index.html");
        assertTrue(built.prompt.contains("Le code doit être récupérable"));
        assertTrue(built.prompt.contains("```lang:chemin/NomFichier.ext"));
    }

    @Test
    public void selectProjectFiles_ordersByRelatedList() {
        List<OrionProjectStore.ProjectFile> all = new ArrayList<>();
        all.add(new OrionProjectStore.ProjectFile("z.css", null, 0, 0));
        all.add(new OrionProjectStore.ProjectFile("index.html", null, 0, 0));
        all.add(new OrionProjectStore.ProjectFile("app.js", null, 0, 0));
        List<OrionProjectStore.ProjectFile> sel = OrionPromptBuilder.selectProjectFiles(
                all, Arrays.asList("index.html", "app.js"));
        assertEquals(2, sel.size());
        assertEquals("index.html", sel.get(0).name);
        assertEquals("app.js", sel.get(1).name);
    }

    @Test
    public void selectProjectFiles_nullMeansAll() {
        List<OrionProjectStore.ProjectFile> all = new ArrayList<>();
        all.add(new OrionProjectStore.ProjectFile("a.js", null, 0, 0));
        assertEquals(1, OrionPromptBuilder.selectProjectFiles(all, null).size());
    }

    @Test
    public void countFileHeaders_countsDashedNames() {
        String block = "Projet : x [graphe]\n--- index.html (3 l.) ---\n<html>\n"
                + "--- app.js (1 l.) ---\nx\n";
        assertEquals(2, OrionPromptBuilder.countFileHeaders(block));
    }

    @Test
    public void seedFilename_fromFileLocation() {
        ResolvedTask task = ResolvedTask.builder()
                .rawInput("Mission : patch")
                .fileLocation(new com.pegasuscorp.orbe.orion.search.FileLocation(
                        "timer.js", 10, "snippet"))
                .build();
        assertEquals("timer.js", OrionPromptBuilder.seedFilename(task));
    }

    @Test
    public void guessSeedFilename_fromRawDemand() {
        assertEquals("timer.js", OrionPromptBuilder.guessSeedFilename(
                "corrige l'affichage du timer dans timer.js", null));
        assertEquals("app.css", OrionPromptBuilder.guessSeedFilename(
                "change app.css", "Mission : foo"));
        assertEquals("", OrionPromptBuilder.guessSeedFilename("ajoute un bouton", null));
    }

    @Test
    public void parsePropagatedFileLocation_readsJson() throws Exception {
        JSONObject params = new JSONObject()
                .put("file_location", new JSONObject()
                        .put("filename", "timer.js")
                        .put("line", 12)
                        .put("snippet", "x"));
        com.pegasuscorp.orbe.orion.search.FileLocation loc =
                OrionPromptBuilder.parsePropagatedFileLocation(params);
        assertNotNull(loc);
        assertEquals("timer.js", loc.filename);
        assertEquals(12, loc.line);
    }
}
