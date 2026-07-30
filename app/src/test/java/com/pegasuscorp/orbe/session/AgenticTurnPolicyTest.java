package com.pegasuscorp.orbe.session;

import com.pegasuscorp.orbe.chat.AgenticChain;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.chat.NativeToolCall;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class AgenticTurnPolicyTest {

    @Test
    public void allowMoreToolCalls_falseAfterCalculator() throws Exception {
        AgenticChain chain = new AgenticChain(null, "12×4");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "calculator",
                new JSONObject().put("expression", "12×4")), "48");
        assertFalse(AgenticTurnPolicy.allowMoreToolCalls(chain));
    }

    @Test
    public void allowMoreToolCalls_falseAfterSearch() throws Exception {
        AgenticChain chain = new AgenticChain(null, "match ce soir");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "search",
                new JSONObject().put("query", "coupe du monde")), "résultat");
        assertFalse(AgenticTurnPolicy.allowMoreToolCalls(chain));
    }

    @Test
    public void blockReason_duplicateToolArgs() throws Exception {
        JSONObject args = new JSONObject().put("query", "Muse");
        AgenticChain chain = new AgenticChain(null, "Muse");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "search", args), "a");
        NativeToolCall dup = new NativeToolCall("c2", "search", new JSONObject(args.toString()));
        assertEquals(AgenticTurnPolicy.BlockReason.DUPLICATE_TOOL_ARGS,
                AgenticTurnPolicy.blockReason(chain, dup));
    }

    @Test
    public void blockReason_capReached() throws Exception {
        AgenticChain chain = new AgenticChain(null, "x");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "weather",
                new JSONObject()), "m1");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c2", "device",
                new JSONObject().put("action", "time")), "m2");
        assertEquals(AgenticTurnPolicy.BlockReason.CAP_REACHED,
                AgenticTurnPolicy.blockReason(chain, new NativeToolCall("c3", "weather",
                        new JSONObject())));
    }

    @Test
    public void blockReason_secondWeatherDifferentArgs() throws Exception {
        AgenticChain chain = new AgenticChain(null, "météo demain");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "weather",
                new JSONObject().put("days", 1)), "m1");
        NativeToolCall second = new NativeToolCall("c2", "weather",
                new JSONObject().put("days", 2));
        assertEquals(AgenticTurnPolicy.BlockReason.TOOL_ALREADY_USED,
                AgenticTurnPolicy.blockReason(chain, second));
    }

    @Test
    public void allowMoreToolCalls_falseAfterBrief() throws Exception {
        AgenticChain chain = new AgenticChain(null, "brief du matin");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "brief",
                new JSONObject().put("action", "brief")), "Soleil. Rien à signaler.");
        assertFalse(AgenticTurnPolicy.allowMoreToolCalls(chain));
        assertEquals(AgenticTurnPolicy.BlockReason.TOOL_ALREADY_USED,
                AgenticTurnPolicy.blockReason(chain, new NativeToolCall("c2", "brief",
                        new JSONObject())));
    }

    @Test
    public void allowMoreToolCalls_falseAfterOrionManager() throws Exception {
        AgenticChain chain = new AgenticChain(null, "lance Orion");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "orion_manager",
                new JSONObject()), "Orion est hors ligne. Pour le lancer : dis 'lance Orion'.");
        assertFalse(AgenticTurnPolicy.allowMoreToolCalls(chain));
        assertEquals(AgenticTurnPolicy.BlockReason.DUPLICATE_TOOL_ARGS,
                AgenticTurnPolicy.blockReason(chain, new NativeToolCall("c2", "orion_manager",
                        new JSONObject())));
    }

    @Test
    public void allowMoreToolCalls_trueAfterSingleWeather() throws Exception {
        AgenticChain chain = new AgenticChain(null, "météo");
        chain.addStep(LlmReply.text(""), new NativeToolCall("c1", "weather",
                new JSONObject()), "soleil");
        assertTrue(AgenticTurnPolicy.allowMoreToolCalls(chain));
    }
}
