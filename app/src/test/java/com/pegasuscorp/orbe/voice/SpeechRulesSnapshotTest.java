package com.pegasuscorp.orbe.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SpeechRulesSnapshotTest {

    @Test
    public void dictionary_matchesIgnoreCase() throws Exception {
        JSONObject root = new JSONObject()
                .put("dictionary", new JSONObject()
                        .put("Cursor", "Curseur")
                        .put("Qwen", "Couène")
                        .put("Dis", "Disse")
                        .put("Moi", "Mwa"));
        SpeechRulesSnapshot snap = SpeechRulesSnapshot.from(root);
        assertEquals("J'ouvre Curseur.", snap.applyDictionary("J'ouvre cursor."));
        assertEquals("Couène est prêt.", snap.applyDictionary("qwen est prêt."));
        // Fausses règles FR ignorées
        assertEquals("Dis-moi ça.", snap.applyDictionary("Dis-moi ça."));
        assertFalse(SpeechRulesStore.isBlockedDictionaryKey("Cursor"));
        assertTrue(SpeechRulesStore.isBlockedDictionaryKey("moi"));
    }
}
