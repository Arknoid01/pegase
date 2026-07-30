package com.pegasuscorp.orbe.contextstore;

import android.content.Context;

import com.pegasuscorp.orbe.memory.ContextBuilder;
import com.pegasuscorp.orbe.memory.MemoryEditResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Phase 1 contextes nommés : charge Orion → injecté à côté du message user.
 */
@RunWith(RobolectricTestRunner.class)
public class ContextualFileStoreTest {

    private ContextualFileStore store;
    private Context ctx;

    @Before
    public void setUp() {
        ContextSearchIndex.setAutoIndexForTests(false);
        ContextualFileStore.resetInstanceForTests();
        ctx = RuntimeEnvironment.getApplication();
        File dir = new File(ctx.getFilesDir(), "contexts");
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
        store = ContextualFileStore.getInstance(ctx);
        store.writeForTests("orion-context.md",
                "# Orion\n\n## Décisions prises\n- exclude_domains Tavily\n");
        store.clearLoadedForTests();
    }

    @After
    public void tearDown() {
        ContextualFileStore.resetInstanceForTests();
    }

    @Test
    public void resolveKeyword_orion() {
        assertEquals("orion-context.md", store.resolveKeyword("Orion"));
        assertEquals("orion-context.md", store.resolveKeyword("le contexte Orion"));
    }

    @Test
    public void chargeOrion_injectsBesideUserMessage() {
        String speech = store.load("Orion");
        assertNotNull(speech);
        assertTrue(store.getLoadedFilenames().contains("orion-context.md"));

        // Pointeur dans le system (noms) — corps collé au message user
        String systemCtx = ContextBuilder.build(ctx, "parle-moi du projet");
        assertTrue(systemCtx.contains("Documents joints") || systemCtx.contains("Orion"));

        String wrapped = AttachedContextInjector.wrapUserMessage(ctx, "parle-moi du projet");
        assertTrue("message user doit contenir le md chargé:\n" + wrapped,
                wrapped.contains("exclude_domains Tavily"));
        assertTrue(wrapped.contains("Message de l'utilisateur"));
    }

    @Test
    public void voiceEditor_chargeOrion() {
        ContextEditor editor = new ContextEditor(ctx);
        assertTrue(ContextEditor.looksLikeContextCommand("charge Orion"));
        AtomicReference<MemoryEditResult> ref = new AtomicReference<>();
        editor.process("charge Orion", ref::set);
        MemoryEditResult r = ref.get();
        assertNotNull(r);
        assertTrue(r.success);
        assertFalse(r.fallbackToChat);
        assertTrue(store.getLoadedFilenames().contains("orion-context.md"));
    }

    @Test
    public void unloadAll_clearsInjection() {
        store.load("Orion");
        store.unload("tout");
        String prompt = ContextBuilder.build(ctx, "hello");
        assertFalse(prompt.contains("exclude_domains Tavily"));
    }

    @Test
    public void contextExists_andSaveCreateOrReplace() {
        assertTrue(store.contextExists("orion"));
        assertFalse(store.contextExists("nouveau-projet-xyz"));

        store.save("nouveau-projet-xyz", "# Nouveau\ncontenu");
        assertTrue(store.contextExists("nouveau-projet-xyz"));
        assertEquals("# Nouveau\ncontenu", store.readByKeyword("nouveau-projet-xyz"));

        store.save("nouveau-projet-xyz", "# Remplacé");
        assertEquals("# Remplacé", store.readByKeyword("nouveau-projet-xyz"));
    }
}
