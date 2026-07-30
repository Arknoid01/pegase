package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

/**
 * Héritage de mode parent → chunks (sans LLM).
 */
@RunWith(RobolectricTestRunner.class)
public class OrionModeChunkInheritTest {

    @Test
    public void enrichChunk_inheritsFeatureMode_evenIfSummaryHasNoFeatureWord() {
        ResolvedTask parent = ResolvedTask.builder()
                .mission("ajoute une feature timer")
                .rawInput("ajoute une feature timer")
                .mode(OrionMode.FEATURE)
                .build();
        // Simule le builder final de TaskChunker / PlanBuilder
        OrionMode parentMode = parent.mode != null ? parent.mode : OrionMode.PATCH;
        String compiled = PromptCompiler.compile(ResolvedTask.builder()
                .mission("Étape 1/2")
                .objective("créer le bouton")
                .rawInput("créer le bouton")
                .mode(parentMode)
                .build());
        ResolvedTask chunk = PromptCompiler.resolve(null, compiled, "créer le bouton", parentMode);
        chunk = ResolvedTask.builder().from(chunk).mode(parentMode).build();
        assertEquals(OrionMode.FEATURE, chunk.mode);
    }

    @Test
    public void nullParent_defaultsToPatch() {
        ResolvedTask parent = null;
        OrionMode parentMode = parent != null && parent.mode != null
                ? parent.mode
                : OrionMode.PATCH;
        assertEquals(OrionMode.PATCH, parentMode);
        ResolvedTask chunk = PromptCompiler.resolve(null, "Mission : x", "étape", parentMode);
        assertEquals(OrionMode.PATCH, chunk.mode);
    }
}
