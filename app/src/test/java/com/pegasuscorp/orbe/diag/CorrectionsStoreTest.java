package com.pegasuscorp.orbe.diag;

import android.content.Context;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CorrectionsStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PegaseFileSystem.resetInstanceForTests();
        ContextualFileStore.resetInstanceForTests();
        // reset file
        java.io.File f = PegaseFileSystem.get(ctx).correctionsMd();
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    @Test
    public void mergePending_addsUnderEnAttenteWithoutDupe() {
        int n = CorrectionsStore.mergePending(ctx, Arrays.asList(
                "bureau (repli) — LLM down",
                "action répétée — notepad"));
        assertEquals(2, n);
        String md = CorrectionsStore.read(ctx);
        assertTrue(md.contains(CorrectionsStore.SECTION_PENDING));
        assertTrue(md.contains("- [ ] bureau (repli) — LLM down"));
        assertTrue(md.contains("- [ ] action répétée — notepad"));

        int again = CorrectionsStore.mergePending(ctx, Arrays.asList(
                "bureau (repli) — LLM down",
                "nouveau problème XYZ"));
        assertEquals(1, again);
        assertEquals(3, CorrectionsStore.countPending(ctx));
    }

    @Test
    public void markDone_movesToTermine() {
        CorrectionsStore.mergePending(ctx, Arrays.asList(
                "échec calculator — div0",
                "hésitation weather — city"));
        String spoken = CorrectionsStore.markDone(ctx, "calculator");
        assertTrue(spoken.toLowerCase().contains("termin"));
        assertEquals(1, CorrectionsStore.countPending(ctx));
        String md = CorrectionsStore.read(ctx);
        assertTrue(md.contains(CorrectionsStore.SECTION_DONE));
        assertTrue(md.contains("- [x] échec calculator — div0"));
        assertTrue(md.contains("- [ ] hésitation weather — city"));
    }

    @Test
    public void speakPendingList_andCount() {
        assertTrue(CorrectionsStore.speakPendingCount(ctx).toLowerCase().contains("aucune")
                || CorrectionsStore.speakPendingCount(ctx).contains("0"));
        CorrectionsStore.mergePending(ctx, List.of("unanswered_request — timeout"));
        assertTrue(CorrectionsStore.speakPendingList(ctx).contains("unanswered_request")
                || CorrectionsStore.speakPendingList(ctx).contains("timeout"));
        assertTrue(CorrectionsStore.speakPendingCount(ctx).contains("1")
                || CorrectionsStore.speakPendingCount(ctx).toLowerCase().contains("une"));
    }

    @Test
    public void contextualFileStore_resolvesCorrections() {
        CorrectionsStore.mergePending(ctx, List.of("phantom_action — tool"));
        ContextualFileStore store = ContextualFileStore.getInstance(ctx);
        assertEquals(CorrectionsStore.FILENAME, store.resolveKeyword("corrections"));
        String content = store.readByKeyword("corrections");
        assertNotNull(content);
        assertTrue(content.contains("phantom_action"));
        String loadMsg = store.load("corrections");
        assertNotNull(loadMsg);
        assertTrue(loadMsg.toLowerCase().contains("correction"));
    }

    @Test
    public void editor_detectsVoiceCommands() {
        assertTrue(CorrectionsEditor.looksLikeCorrectionsCommand(
                "qu'est-ce qui reste à corriger"));
        assertTrue(CorrectionsEditor.looksLikeCorrectionsCommand(
                "combien de corrections en attente"));
        assertTrue(CorrectionsEditor.looksLikeCorrectionsCommand(
                "marque calculator comme terminé"));
        assertFalse(CorrectionsEditor.looksLikeCorrectionsCommand("bonjour pégase"));
    }
}
