package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import com.pegasuscorp.orbe.memory.MemoryEditResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotHintsEditorTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("copilot_app_hints", Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    @Test
    public void looksLike_retiensSur() {
        assertTrue(CopilotHintsEditor.looksLikeHintsEdit(
                "Retiens que sur WhatsApp le contact Marie c'est le chat épinglé"));
        assertTrue(CopilotHintsEditor.looksLikeHintsEdit(
                "retiens sur Brave que l'omnibox c'est la barre d'adresse"));
        assertTrue(CopilotHintsEditor.looksLikeHintsEdit(
                "Sur Reddit, retiens que upvote c'est gesture"));
        assertFalse(CopilotHintsEditor.looksLikeHintsEdit(
                "Retiens que j'aime le café"));
    }

    @Test
    public void process_addsNote() {
        MemoryEditResult r = CopilotHintsEditor.process(ctx,
                "Retiens que sur WhatsApp le contact Marie c'est le chat épinglé");
        assertTrue(r.success);
        assertFalse(r.fallbackToChat);
        assertTrue(r.spokenReply.toLowerCase().contains("whatsapp"));
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.whatsapp");
        assertTrue(h.toPromptSection().contains("Marie"));
    }

    @Test
    public void process_alias() {
        MemoryEditResult r = CopilotHintsEditor.process(ctx,
                "Retiens que sur WhatsApp envoyer veut dire Envoyer");
        assertTrue(r.success);
        assertEquals("Envoyer",
                CopilotAppHintsStore.get(ctx, "com.whatsapp").resolveAlias("envoyer"));
    }

    @Test
    public void process_strictFlag() {
        MemoryEditResult r = CopilotHintsEditor.process(ctx,
                "Retiens que sur Reddit matching strict");
        assertTrue(r.success);
        assertTrue(CopilotAppHintsStore.get(ctx, "com.reddit.frontpage").strictTextMatch);
    }

    @Test
    public void process_gestureFlag() {
        MemoryEditResult r = CopilotHintsEditor.process(ctx,
                "Retiens que sur WhatsApp privilégie le gesture webview");
        assertTrue(r.success);
        assertTrue(CopilotAppHintsStore.get(ctx, "com.whatsapp").distrustA11yClickSuccess);
    }

    @Test
    public void process_unknownApp() {
        MemoryEditResult r = CopilotHintsEditor.process(ctx,
                "Retiens que sur AppliInconnue le bouton X");
        assertFalse(r.success);
        assertNotNull(r.spokenReply);
    }

    @Test
    public void memoryEditor_excludesHints() {
        assertFalse(com.pegasuscorp.orbe.memory.MemoryEditor.looksLikeMemoryEdit(
                "Retiens que sur WhatsApp Marie c'est le chat épinglé"));
        assertTrue(com.pegasuscorp.orbe.memory.MemoryEditor.looksLikeMemoryEdit(
                "Retiens que j'habite à Lyon"));
    }
}
