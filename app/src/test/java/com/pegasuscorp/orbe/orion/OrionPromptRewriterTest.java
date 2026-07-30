package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.PromptReadiness;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionPromptRewriterTest {

    @Test
    public void buildMetaPrompt_hasReadinessRules() {
        String meta = OrionPromptRewriter.buildMetaPrompt("plus de particules",
                "Projet actif : balle\nFichiers : index.html\n");
        assertTrue(meta.contains("READINESS"));
        assertTrue(meta.contains("RECOMMENDED"));
        assertTrue(meta.contains("Glossaire Yann"));
        assertTrue(meta.contains("Max 2 questions"));
    }

    @Test
    public void parse_recommendedInterpretation() {
        OrionPromptRewriter.CompileResult r = OrionPromptRewriter.parseCompileResult(
                "READINESS: RECOMMENDED\nINTERPRETATION:\n"
                        + "Je comprends : augmenter la densité sur l'orbe. C'est bien ça ?");
        assertEquals(OrionPromptRewriter.CompileKind.INTERPRETATION, r.kind);
        assertTrue(r.text.contains("orbe"));
    }

    @Test
    public void parse_readyMission() {
        OrionPromptRewriter.CompileResult r = OrionPromptRewriter.parseCompileResult(
                "READINESS: READY\nMission : correction ciblée\n\n"
                        + "Objectif principal :\nSupprimer le titre Bureau\n\n"
                        + "Ne pas toucher / Contraintes :\n- Toolbar\n\n"
                        + "Validation :\n- Titre absent");
        assertEquals(OrionPromptRewriter.CompileKind.MISSION, r.kind);
        assertTrue(r.text.contains("Mission :"));
        assertTrue(r.text.contains("Bureau") || r.text.contains("correction"));
    }

    @Test
    public void promptCompiler_roundTrip() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .keyword("particleCount")
                .objective("Augmenter la densité sur l'orbe")
                .action("Modifier le count")
                .exclusion("ne pas changer la vitesse")
                .validation("densité visible, perfs ok")
                .assumption("cible = orbe centrale")
                .build();
        String compiled = PromptCompiler.compile(task);
        assertTrue(compiled.contains("Hypothèses retenues"));
        assertTrue(compiled.contains("Mot-clé ciblé"));
        ResolvedTask back = PromptCompiler.parseMissionBlock(compiled);
        assertEquals("patch particules", back.mission);
        assertEquals("particleCount", back.extractedKeyword);
    }

    @Test
    public void readinessEnum_threeStates() {
        assertEquals(3, PromptReadiness.values().length);
    }
}
