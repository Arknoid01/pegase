package com.pegasuscorp.orbe.chatbot;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.search.MultilingualEmbedder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

/**
 * Parité avec le banc Python (pegase-chatbot, 96 % le 2026-11-25) : mêmes règles, même banque,
 * même modèle mpnet int8. Données PERSONNELLES lues hors dépôt (~/.pegase/chatbot) : test
 * ignoré si elles manquent (autre machine, CI).
 */
@RunWith(RobolectricTestRunner.class)
public class ChatbotPariteTest {

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void banc_conversation() throws Exception {
        File home = new File(System.getProperty("user.home"));
        File data = new File(home, ".pegase/chatbot");
        File model = new File(home, ".cache/pegase-chatbot/mpnet-int8");
        File rules = new File("src/main/assets/chatbot/rules.json");
        assumeTrue(new File(data, "banc_conversation.json").isFile()
                && new File(model, ChatbotStore.MODEL_FILE).isFile() && rules.isFile());

        MultilingualEmbedder emb = MultilingualEmbedder.open(model, ChatbotStore.MODEL_FILE);
        ChatbotEngine e = new ChatbotEngine(new JSONObject(read(rules)),
                new JSONObject(read(new File(data, "banque.json"))),
                new Fiches(new JSONObject(read(new File(data, "fiches.json")))),
                emb::embed, false, new Random(1));
        long t0 = System.currentTimeMillis();
        e.loadVectors(null);
        System.out.println("banque : " + e.bankTexts().size() + " formulations en "
                + (System.currentTimeMillis() - t0) + " ms");

        JSONArray cases = new JSONObject(read(new File(data, "banc_conversation.json"))).getJSONArray("cases");
        int ok = 0, n = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String attendu = c.getString("attendu");
            if (attendu.startsWith("outil:")) continue;   // le routeur d'Orbe s'en charge
            ChatbotEngine.Decision d = e.decide(c.getString("text"));
            boolean good = d.source.equals(attendu) || (attendu.equals("libre") && d.zone.equals("faible"));
            n++;
            if (good) ok++;
            System.out.println((good ? "OK " : "KO ") + d + " « " + c.getString("text") + " »"
                    + (good ? "" : " attendu " + attendu));
        }
        System.out.println("parité : " + ok + "/" + n);
        emb.close();
        assertTrue("trop d'écarts avec le banc Python : " + ok + "/" + n, ok >= n - 3);
    }
}
