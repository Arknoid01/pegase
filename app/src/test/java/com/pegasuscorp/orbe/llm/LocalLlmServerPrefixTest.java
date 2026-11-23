package com.pegasuscorp.orbe.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Coupe du préfixe de cache indépendante du format de conversation (Qwen ChatML, Gemma). */
@RunWith(RobolectricTestRunner.class)
public class LocalLlmServerPrefixTest {

    private static JSONObject body(String last) throws Exception {
        return new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "Tu es Pégase."))
                .put(new JSONObject().put("role", "user").put("content", "mets un réveil"))
                .put(new JSONObject().put("role", "assistant").put("content", "alarm add"))
                .put(new JSONObject().put("role", "user").put("content", last)));
    }

    @Test
    public void chatml_coupeAvantLeTexteDuDernierTour() throws Exception {
        String last = "réveille-moi à 7h\n\n(Nous sommes le lundi.)";
        String prompt = "<|im_start|>system\nTu es Pégase.<|im_end|>\n<|im_start|>user\nmets un réveil<|im_end|>\n"
                + "<|im_start|>assistant\nalarm add<|im_end|>\n<|im_start|>user\n" + last + "<|im_end|>\n<|im_start|>assistant\n";
        int cut = LocalLlmServer.prefixCut(prompt, body(last));
        assertTrue(prompt.substring(0, cut).endsWith("<|im_start|>user\n"));
        assertEquals(last, prompt.substring(cut, cut + last.length()));
    }

    @Test
    public void gemma_coupeAvantLeTexteDuDernierTour() throws Exception {
        String last = "mets un réveil demain";   // commence comme un exemple : dernière occurrence
        String prompt = "<bos><start_of_turn>user\nTu es Pégase.\n\nmets un réveil<end_of_turn>\n"
                + "<start_of_turn>model\nalarm add<end_of_turn>\n<start_of_turn>user\n" + last
                + "<end_of_turn>\n<start_of_turn>model\n";
        int cut = LocalLlmServer.prefixCut(prompt, body(last));
        assertTrue(prompt.substring(0, cut).endsWith("<start_of_turn>user\n"));
    }

    @Test
    public void introuvable_renvoieMoinsUn() throws Exception {
        assertEquals(-1, LocalLlmServer.prefixCut("rien à voir", body("autre chose")));
    }
}
