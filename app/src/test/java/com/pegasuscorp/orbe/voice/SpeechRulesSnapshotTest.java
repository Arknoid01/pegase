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
        assertTrue(SpeechRulesStore.isBlockedDictionaryKey("Chat"));
        assertTrue(SpeechRulesStore.isBlockedDictionaryKey("continue"));
    }

    @Test
    public void dictionary_skipsFrenchCollisions() throws Exception {
        JSONObject root = new JSONObject()
                .put("dictionary", new JSONObject()
                        .put("Chat", "Tchate")
                        .put("Continue", "Conitniou")
                        .put("Branch", "Brantche")
                        .put("GitHub", "Guite Hub"));
        SpeechRulesSnapshot snap = SpeechRulesSnapshot.from(root);
        assertEquals("Le chat continue.", snap.applyDictionary("Le chat continue."));
        assertEquals("Guite Hub est ouvert.", snap.applyDictionary("GitHub est ouvert."));
    }

    @Test
    public void dictionary_matchesUnicodeWordBoundaries() throws Exception {
        JSONObject root = new JSONObject()
                .put("dictionary", new JSONObject()
                        .put("C++", "cé plus plus")
                        .put("café", "ka fé"));
        SpeechRulesSnapshot snap = SpeechRulesSnapshot.from(root);
        assertEquals("On code en cé plus plus.", snap.applyDictionary("On code en C++."));
        assertEquals("Un ka fé chaud.", snap.applyDictionary("Un café chaud."));
    }

    @Test
    public void dictionary_allCapsKeysAreCaseSensitive() throws Exception {
        JSONObject root = new JSONObject()
                .put("dictionary", new JSONObject()
                        .put("CSV", "Cé Esse Vé")
                        .put("Hz", "Heurtze"));
        SpeechRulesSnapshot snap = SpeechRulesSnapshot.from(root);
        // Tout en maj → casse exacte : « csv » minuscule inchangé
        assertEquals("exporte en csv.", snap.applyDictionary("exporte en csv."));
        assertEquals("exporte en Cé Esse Vé.", snap.applyDictionary("exporte en CSV."));
        // « Hz » (≤2) : casse exacte — « hz » minuscule inchangé
        assertEquals("60 hz.", snap.applyDictionary("60 hz."));
        assertEquals("60 Heurtze.", snap.applyDictionary("60 Hz."));
    }

    @Test
    public void replace_disMoi_softensForTts() throws Exception {
        JSONObject root = new JSONObject()
                .put("replace", new JSONObject()
                        .put("dis moi", "di mwa")
                        .put("dis-moi", "di-mwa"));
        SpeechRulesSnapshot snap = SpeechRulesSnapshot.from(root);
        assertEquals("di mwa ce que tu veux.", snap.applyReplace("dis moi ce que tu veux."));
        assertEquals("di-mwa la suite.", snap.applyReplace("Dis-moi la suite."));
    }

    @Test
    public void replace_runsBeforeDictionary_inFormatterOrder() throws Exception {
        // Simule l'ordre SpeechFormatter : replace puis dictionary
        JSONObject root = new JSONObject()
                .put("replace", new JSONObject().put("branch", "branche"))
                .put("dictionary", new JSONObject().put("Git", "Guite"));
        SpeechRulesSnapshot snap = SpeechRulesSnapshot.from(root);
        String text = "La branch Git";
        text = snap.applyReplace(text);
        text = snap.applyDictionary(text);
        assertEquals("La branche Guite", text);
    }
}
