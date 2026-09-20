package com.pegasuscorp.orbe.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class AgenticContextCompactorTest {

    private static AgenticChain chainWithBigResults(int steps, int size) throws Exception {
        AgenticChain chain = new AgenticChain(null, "x");
        for (int i = 0; i < steps; i++) {
            char[] big = new char[size];
            java.util.Arrays.fill(big, (char) ('a' + i));
            chain.addStep(LlmReply.text(""), new NativeToolCall("c" + i, "weather",
                    new JSONObject().put("days", i)), new String(big));
        }
        return chain;
    }

    private static int total(List<JSONObject> l) {
        int n = 0;
        for (JSONObject m : l) n += m.toString().length();
        return n;
    }

    @Test
    public void souBudget_rienNestAbrege() throws Exception {
        AgenticChain chain = chainWithBigResults(3, 500);
        List<JSONObject> out = AgenticContextCompactor.stepMessages(chain.steps(), 50_000);
        for (JSONObject m : out) {
            assertFalse(m.toString().contains("abrégé"));
        }
    }

    @Test
    public void depasseBudget_abregeLesAnciennes_gardeLesDeuxDernieresEntieres()
            throws Exception {
        AgenticChain chain = chainWithBigResults(6, 5_000);
        List<JSONObject> out = AgenticContextCompactor.stepMessages(chain.steps(), 25_000);

        assertTrue(total(out) <= 25_000);
        // 6 étapes × (appel + résultat [+ hint])
        String first = out.get(1).optString("content");
        assertTrue(first.contains("abrégé"));
        assertTrue(first.length() < 1_500);
        // les deux dernières étapes gardent leur résultat complet
        String preLast = null, last = null;
        int toolMsgs = 0;
        for (JSONObject m : out) {
            if ("tool".equals(m.optString("role"))) {
                toolMsgs++;
                if (toolMsgs == 5) preLast = m.optString("content");
                if (toolMsgs == 6) last = m.optString("content");
            }
        }
        assertEquals(5_000, preLast.length());
        assertEquals(5_000, last.length());
        // les appels d'outils (assistant) restent tous présents
        int assistant = 0;
        for (JSONObject m : out) if ("assistant".equals(m.optString("role"))) assistant++;
        assertEquals(6, assistant);
    }

    @Test
    public void budgetImpossible_neGardeQueLaDerniereEtape() throws Exception {
        AgenticChain chain = chainWithBigResults(5, 20_000);
        List<JSONObject> out = AgenticContextCompactor.stepMessages(chain.steps(), 3_000);
        int fullResults = 0;
        for (JSONObject m : out) {
            if ("tool".equals(m.optString("role")) && m.optString("content").length() >= 20_000) {
                fullResults++;
            }
        }
        assertEquals(1, fullResults);
    }
}
