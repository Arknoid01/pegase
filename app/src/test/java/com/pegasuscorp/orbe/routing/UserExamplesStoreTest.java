package com.pegasuscorp.orbe.routing;

import android.content.Context;

import com.pegasuscorp.orbe.memory.ContextAnalyzer;
import com.pegasuscorp.orbe.memory.ContextIntent;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class UserExamplesStoreTest {

    private static EmbeddingEngine sharedEngine;
    private Context ctx;
    private UserExamplesStore store;

    @BeforeClass
    public static void loadEngineOnce() throws Exception {
        File model = new File("src/main/assets/rag/all-MiniLM-L6-v2.onnx");
        File vocab = new File("src/main/assets/rag/vocab.txt");
        assertTrue("ONNX model missing", model.exists());
        try (FileInputStream vin = new FileInputStream(vocab)) {
            sharedEngine = EmbeddingEngine.createFromFiles(model, vin);
        }
        EmbeddingEngine.installForTests(sharedEngine);
    }

    @AfterClass
    public static void unloadEngine() {
        UserExamplesStore.resetInstanceForTests();
        EmbeddingEngine.resetForTests();
        sharedEngine = null;
    }

    @Before
    public void setUp() {
        EmbeddingEngine.installForTests(sharedEngine);
        UserExamplesStore.resetInstanceForTests();
        ctx = RuntimeEnvironment.getApplication();
        store = UserExamplesStore.getInstance(ctx);
        store.clearAll();
    }

    @Test
    public void toolOptions_coversRegistryTools() {
        assertTrue(UserExamplesStore.TOOL_OPTIONS.length > 20);
        assertEquals("none", UserExamplesStore.TOOL_OPTIONS[0]);
        assertTrue(containsTool("agenda"));
        assertTrue(containsTool("youtube"));
        assertTrue(containsTool("sms"));
        assertTrue(containsTool("open_app"));
        assertTrue(containsTool("brief"));
        assertTrue(containsTool("flashlight"));
        assertFalse(containsTool("composite"));
    }

    @Test
    public void guessToolHint_coversMoreTools() {
        assertEquals("agenda", UserExamplesStore.guessToolHint("Qu'est-ce que j'ai demain ?"));
        assertEquals("youtube", UserExamplesStore.guessToolHint("Mets la vidéo YouTube"));
        assertEquals("sms", UserExamplesStore.guessToolHint("Envoie un SMS à Paul"));
        assertEquals("flashlight", UserExamplesStore.guessToolHint("Allume la torche"));
        assertEquals("news", UserExamplesStore.guessToolHint("Quelles sont les actualités ?"));
        assertEquals("brief", UserExamplesStore.guessToolHint("Fais mon brief du matin"));
        assertEquals("diag", UserExamplesStore.guessToolHint("Tu as eut des problèmes ?"));
        assertEquals("none", UserExamplesStore.guessToolHint("Comment va tu ?"));
    }

    @Test
    public void acceptMatch_rejectsDiagFalsePositives() {
        assertFalse(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("Tu peux m'en dire plus ?")));
        assertFalse(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("Tu peux être un plus précis ?")));
        assertFalse(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("Tu peux mettre fin à la conversation")));
        assertFalse(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("Oui tu peux regarder")));
        assertFalse(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("Comment va tu ?")));
        assertTrue(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("Tu as eut des problèmes ?")));
        assertTrue(UserExamplesStore.acceptMatch("diag",
                UserExamplesStore.foldPhrase("t'as eu des soucis ?")));
    }

    @Test
    public void findMatch_ignoresPoisonedExactFollowUp() {
        store.addExample("tu peux m en dire plus", "diag");
        assertNull(store.findMatch("Tu peux m'en dire plus ?",
                UserExamplesStore.DEFAULT_MIN_SCORE));
    }

    private static boolean containsTool(String id) {
        for (String t : UserExamplesStore.TOOL_OPTIONS) {
            if (id.equals(t)) return true;
        }
        return false;
    }

    @Test
    public void importFromConversation_extractsUserLinesOnly() {
        String txt = ""
                + "Yannick : Tu as eut des problèmes ?\n"
                + "Pégase : Rien à signaler.\n"
                + "Yannick : Mets un minuteur dans 30 minutes\n"
                + "Pégase : Minuteur lancé.\n"
                + "Yannick : Il fait beau\n"
                + "Moi : Note qu'il faut détartrer la cafetière\n"
                + "Yannick : " + "x".repeat(130) + "\n";

        List<PhraseCandidate> c = store.importFromConversation(txt);
        assertEquals(4, c.size());
        assertEquals("diag", c.get(0).toolHint);
        assertEquals("timer", c.get(1).toolHint);
        assertEquals("none", c.get(2).toolHint);
        assertEquals("notepad", c.get(3).toolHint);
    }

    @Test
    public void findMatch_exactAfterAdd() {
        store.addExample("tu as eut des problemes", "diag");
        UserExamplesStore.Match m = store.findMatch("Tu as eut des problèmes ?", 0.82f);
        assertNotNull(m);
        assertTrue(m.exact);
        assertEquals("diag", m.tool);
        assertEquals(1f, m.score, 0.01f);
    }

    @Test
    public void findMatch_semanticVariant() {
        store.addExample("tu as eut des problemes", "diag");
        // MiniLM FR ~0.64 sur cette paire — seuil DEFAULT + filet lexical
        UserExamplesStore.Match m = store.findMatch("t'as eu des soucis ?",
                UserExamplesStore.DEFAULT_MIN_SCORE);
        assertNotNull("semantic match expected", m);
        assertFalse(m.exact);
        assertEquals("diag", m.tool);
        assertTrue(m.score >= 0.55f);
    }

    @Test
    public void contextAnalyzer_prefersUserExampleBeforeHardcoded() {
        store.addExample("tu as eut des problemes", "diag");
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "t'as eu des soucis ?");
        assertEquals("diag", intent.intent);
        assertTrue(intent.allowedTools.contains(ToolTag.DIAG));
        assertTrue(intent.requiresTool);
    }

    @Test
    public void noneTool_skipsToolRequirement() {
        store.addExample("il fait beau", "none");
        ContextIntent intent = ContextAnalyzer.analyze(ctx, "il fait beau");
        assertEquals("general", intent.intent);
        assertFalse(intent.requiresTool);
    }
}
