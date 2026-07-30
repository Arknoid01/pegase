package com.pegasuscorp.orbe.orion.prompt;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class OrionModeTest {

    @Test
    public void detect_featureWord_isFeature() {
        assertEquals(OrionMode.FEATURE, OrionMode.detect("ajoute une feature timer"));
        assertEquals(OrionMode.FEATURE, OrionMode.detect("Feature : bouton pause"));
        assertEquals(OrionMode.FEATURE,
                OrionMode.detect("ajoute l'écran de réglages en feature"));
    }

    @Test
    public void detect_withoutFeatureWord_isPatch() {
        assertEquals(OrionMode.PATCH, OrionMode.detect("plus de particules"));
        assertEquals(OrionMode.PATCH, OrionMode.detect("corrige le compteur"));
        assertEquals(OrionMode.PATCH, OrionMode.detect(""));
        assertEquals(OrionMode.PATCH, OrionMode.detect(null));
    }

    @Test
    public void detect_substringNotWholeWord() {
        // « featured » / préfixe ne doit pas matcher
        assertEquals(OrionMode.PATCH, OrionMode.detect("featured content"));
    }

    @Test
    public void compiledPatchExclusions_doNotFlipMode_whenRawIsPatch() {
        ResolvedTask patchTask = ResolvedTask.builder()
                .mission("corrige le compteur")
                .rawInput("corrige le compteur")
                .mode(OrionMode.PATCH)
                .build();
        String compiled = PromptCompiler.compile(patchTask);
        assertTrue(compiled.toLowerCase().contains("fonctionnalité")
                || compiled.toLowerCase().contains("fonctionnalite"));
        assertFalse(compiled.matches("(?is).*\\bfeature\\b.*"));
        // resolve 3-args avec demande brute PATCH
        ResolvedTask again = PromptCompiler.resolve(null, compiled, "corrige le compteur");
        assertEquals(OrionMode.PATCH, again.mode);
        // resolve 4-args hérité
        ResolvedTask derived = PromptCompiler.resolve(null, compiled, "étape 1", OrionMode.PATCH);
        assertEquals(OrionMode.PATCH, derived.mode);
    }

    @Test
    public void resolve_compiledAsRawInput_staysPatch_neverDetect() {
        String compiled = "Mission : corrige timer.js\n\n"
                + "Objectif principal :\naffichage\n\n"
                + "Ne pas toucher / Contraintes :\n"
                + "- Aucun refactoring ; pas de nouvelle fonctionnalité ; patch minimal.\n";
        assertTrue(PromptCompiler.looksLikeCompiledMission(compiled));
        // Bug historique : resolve(mission, mission) → detect voyait « feature »
        ResolvedTask t = PromptCompiler.resolve(null, compiled, compiled);
        assertEquals(OrionMode.PATCH, t.mode);
    }

    @Test
    public void resolve_rawFeatureWord_stillFeature() {
        String compiled = "Mission : timer\n\nObjectif principal :\nfeature UI\n";
        ResolvedTask t = PromptCompiler.resolve(null, compiled, "ajoute une feature timer");
        assertEquals(OrionMode.FEATURE, t.mode);
    }

    @Test
    public void from_copiesMode_noRedetect() {
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("ajoute une feature X")
                .mode(OrionMode.FEATURE)
                .build();
        ResolvedTask child = ResolvedTask.builder()
                .from(parent)
                .rawInput("étape sans le mot")
                .build();
        assertEquals(OrionMode.FEATURE, child.mode);
    }

    @Test
    public void compile_featureDefaultExclusions() {
        ResolvedTask t = ResolvedTask.builder()
                .mission("timer")
                .mode(OrionMode.FEATURE)
                .build();
        String c = PromptCompiler.compile(t);
        assertTrue(c.contains("Implémente la fonctionnalité demandée"));
        assertFalse(c.contains("pas de nouvelle feature ; patch minimal"));
        assertFalse(c.contains("pas de nouvelle fonctionnalité ; patch minimal"));
    }
}
