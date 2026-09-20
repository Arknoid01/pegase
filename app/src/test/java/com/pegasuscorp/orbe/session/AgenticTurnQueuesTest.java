package com.pegasuscorp.orbe.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.chat.AgenticChain;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.chat.NativeToolCall;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class AgenticTurnQueuesTest {

    private static NativeToolCall call(String id, String name) {
        return new NativeToolCall(id, name, new JSONObject());
    }

    @Test
    public void lot_metEnFileLesAppels2aN_dansLOrdre() {
        AgenticTurnQueues q = new AgenticTurnQueues();
        q.stageBatchRemainder(LlmReply.withNativeToolCalls("",
                Arrays.asList(call("1", "a"), call("2", "b"), call("3", "c"))));

        assertEquals(2, q.batchSize());
        assertEquals("b", q.pollBatch().name);
        assertEquals("c", q.pollBatch().name);
        assertNull(q.pollBatch());
    }

    @Test
    public void lot_unSeulAppel_neMetRienEnFile_etRestaureAuNouveauLot() {
        AgenticTurnQueues q = new AgenticTurnQueues();
        q.stageBatchRemainder(LlmReply.withNativeToolCalls("",
                Arrays.asList(call("1", "a"), call("2", "b"))));
        q.stageBatchRemainder(LlmReply.withNativeToolCalls("",
                java.util.Collections.singletonList(call("9", "z"))));
        assertEquals(0, q.batchSize());
    }

    @Test
    public void steering_versesDansLaChaine_puisVide() {
        AgenticTurnQueues q = new AgenticTurnQueues();
        AgenticChain chain = new AgenticChain(null, "x");
        assertFalse(q.hasSteering());
        assertEquals(1, q.addSteering("en silence"));
        assertEquals(2, q.addSteering("et vite"));
        assertTrue(q.hasSteering());

        q.drainSteeringInto(chain);

        assertEquals(Arrays.asList("en silence", "et vite"), chain.steering());
        assertFalse(q.hasSteering());
    }

    @Test
    public void clear_videToutEtLInactiviteSeDetecte() {
        AgenticTurnQueues q = new AgenticTurnQueues();
        q.stageBatchRemainder(LlmReply.withNativeToolCalls("",
                Arrays.asList(call("1", "a"), call("2", "b"))));
        q.addSteering("x");
        q.clear();
        assertEquals(0, q.batchSize());
        assertFalse(q.hasSteering());

        assertTrue(q.isIdleTooLong());   // jamais touché
        q.touch();
        assertFalse(q.isIdleTooLong());
    }
}
