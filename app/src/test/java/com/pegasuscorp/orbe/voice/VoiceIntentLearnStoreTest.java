package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoiceIntentLearnStoreTest {

    private Context context;
    private VoiceIntentLearnStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = VoiceIntentLearnStore.getInstance(context);
        store.clearAll();
    }

    @Test
    public void recordConfirmation_thenMatchExact() {
        store.recordConfirmation("on code",
                "{\"tool\":\"open_app\",\"params\":{\"name\":\"Orion\"}}",
                "dev");
        VoiceIntentLearnStore.LearnMatch match = store.match("on code");
        assertNotNull(match);
        assertEquals(0.98, match.score, 0.01);
        assertTrue(match.entry().toolJson.contains("Orion"));
    }

    @Test
    public void recordCorrection_replacesRejectedPhrase() {
        store.recordConfirmation("le camion",
                "{\"tool\":\"sports\",\"params\":{}}", "sports");
        store.recordCorrection("le camion", "le match du psg",
                "{\"tool\":\"sports\",\"params\":{\"team\":\"PSG\"}}", "sports");

        assertNull(store.match("le camion"));
        assertNotNull(store.match("le match du psg"));
    }

    @Test
    public void recordConfirmation_synonymsShareIntentViaSeparateEntries() {
        String tool = "{\"tool\":\"spotify\",\"params\":{\"action\":\"play\"}}";
        store.recordConfirmation("mets un truc", tool, "spotify");
        store.recordConfirmation("mets de la musique", tool, "spotify");

        assertNotNull(store.match("mets un truc"));
        assertNotNull(store.match("mets de la musique"));
        List<VoiceIntentLearnStore.LearnedIntent> entries = store.getEntries();
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).synonyms.size());
    }

    @Test
    public void recordConfirmation_groupsSameToolJson() {
        String tool = "{\"tool\":\"spotify\",\"params\":{\"action\":\"play\"}}";
        store.recordConfirmation("balance une playlist", tool, "spotify");
        store.recordConfirmation("j'ai envie de musique", tool, "spotify");
        assertEquals(1, store.getEntries().size());
        assertEquals(2, store.getEntries().get(0).confirmations);
    }
}
