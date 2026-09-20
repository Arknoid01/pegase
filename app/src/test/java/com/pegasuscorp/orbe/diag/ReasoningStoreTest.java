package com.pegasuscorp.orbe.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ReasoningStoreTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        ReasoningStore.init(ctx);
        ReasoningStore.clear();
        ReasoningStore.awaitIdle();
    }

    private static ReasoningCard card(boolean ephemeral, ReasoningCard.ToolStep... steps) {
        return new ReasoningCard("météo", Arrays.asList(steps), 2,
                Collections.singletonList("Il aime le café"), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), "", Collections.singletonList("meteo.fr"),
                "", "groq/llama", 900, 1200, ephemeral, true, "outil non appelé");
    }

    @Test
    public void carteSurvitAuRedemarrage() throws Exception {
        ReasoningStore.put("Il fera beau demain.", card(false,
                new ReasoningCard.ToolStep("weather", "{days:2}", "soleil", 800, true)));
        ReasoningStore.awaitIdle();

        ReasoningStore.init(ctx);   // simule un redémarrage : mémoire vidée puis relue
        ReasoningCard back = ReasoningStore.findForReply("il fera beau demain.");

        assertNotNull(back);
        assertEquals("météo", back.intentDetected);
        assertEquals(1, back.toolsUsed.size());
        assertEquals("weather", back.toolsUsed.get(0).name);
        assertEquals("soleil", back.toolsUsed.get(0).resultPreview);
        assertTrue(back.potentialHallucination);
        assertEquals("outil non appelé", back.hallucinationReason);
        assertEquals(Collections.singletonList("Il aime le café"), back.memoriesUsed);
        assertEquals(Collections.singletonList("meteo.fr"), back.webSources);
        assertEquals("groq/llama", back.backend);
    }

    @Test
    public void outilSensible_neConserveNiParametresNiResultat() throws Exception {
        ReasoningStore.put("Marie dit rendez-vous à 18h.", card(false,
                new ReasoningCard.ToolStep("sms", "{to:Marie}", "rendez-vous à 18h", 400, true)));
        ReasoningStore.awaitIdle();

        ReasoningStore.init(ctx);
        ReasoningCard back = ReasoningStore.findForReply("Marie dit rendez-vous à 18h.");

        assertNotNull(back);
        ReasoningCard.ToolStep step = back.toolsUsed.get(0);
        assertEquals("sms", step.name);
        assertTrue(step.ok);
        assertEquals("", step.paramsPreview);
        assertEquals("", step.resultPreview);
    }

    @Test
    public void carteEphemere_nEstPasEcrite_etClearEfface() throws Exception {
        ReasoningStore.put("réponse éphémère", card(true));
        ReasoningStore.awaitIdle();
        ReasoningStore.init(ctx);
        assertNull(ReasoningStore.findForReply("réponse éphémère"));

        ReasoningStore.put("réponse durable", card(false));
        ReasoningStore.awaitIdle();
        ReasoningStore.clear();
        ReasoningStore.awaitIdle();
        ReasoningStore.init(ctx);
        assertNull(ReasoningStore.findForReply("réponse durable"));
    }

    @Test
    public void fichierCorrompu_repartDeZero() throws Exception {
        ReasoningStore.put("x", card(false));
        ReasoningStore.awaitIdle();
        java.io.File f = new java.io.File(ctx.getFilesDir(), "diag/reasoning_cards.json");
        java.nio.file.Files.write(f.toPath(), "{pas du json".getBytes());
        ReasoningStore.init(ctx);
        assertNull(ReasoningStore.findForReply("x"));
    }
}
