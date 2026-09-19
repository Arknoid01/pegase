package com.pegasuscorp.orbe.chat;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class GroqCompletionParserTest {

    @Test
    public void parse_toolCalls_extractsNameAndArguments() throws Exception {
        String body = "{"
                + "\"choices\":[{\"message\":{"
                + "\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_abc\",\"type\":\"function\","
                + "\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"days\\\":2}\"}}"
                + "]}}]}";

        LlmReply reply = GroqCompletionParser.parse(body);

        assertTrue(reply.hasNativeToolCalls());
        assertEquals(1, reply.toolCalls.size());
        assertEquals("weather", reply.toolCalls.get(0).name);
        assertEquals(2, reply.toolCalls.get(0).arguments.getInt("days"));
    }

    @Test
    public void parse_plainText_returnsTextReply() throws Exception {
        String body = "{"
                + "\"choices\":[{\"message\":{\"content\":\"Salut !\"}}]"
                + "}";

        LlmReply reply = GroqCompletionParser.parse(body);

        assertFalse(reply.hasNativeToolCalls());
        assertEquals("Salut !", reply.content);
    }

    private static String toolBody(String finish, String arguments) {
        return "{\"choices\":[{\"finish_reason\":\"" + finish + "\",\"message\":{"
                + "\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"notepad\",\"arguments\":" + arguments + "}}"
                + "]}}]}";
    }

    @Test
    public void parse_finishReasonLength_rejectsToolCall() throws Exception {
        LlmReply reply = GroqCompletionParser.parse(
                toolBody("length", "\"{\\\"action\\\":\\\"add\\\"}\""));
        assertTrue(reply.hasNativeToolCalls());
        NativeToolCall call = reply.toolCalls.get(0);
        assertTrue(call.isRejected());
        assertEquals(NativeToolCall.REJECT_TRUNCATED, call.rejectionCode);
        assertEquals("length", reply.finishReason);
    }

    @Test
    public void parse_malformedArguments_areRejected_notSilentlyEmpty() throws Exception {
        LlmReply reply = GroqCompletionParser.parse(
                toolBody("tool_calls", "\"{\\\"action\\\":\\\"add\\\", \\\"text\\\":\\\"cou\""));
        NativeToolCall call = reply.toolCalls.get(0);
        assertTrue(call.isRejected());
        assertEquals(NativeToolCall.REJECT_MALFORMED_ARGUMENTS, call.rejectionCode);
    }

    @Test
    public void parse_blankArguments_areAnEmptyObject_notRejected() throws Exception {
        LlmReply reply = GroqCompletionParser.parse(toolBody("tool_calls", "\"\""));
        assertFalse(reply.toolCalls.get(0).isRejected());
        assertEquals(0, reply.toolCalls.get(0).arguments.length());
    }

    @Test
    public void parse_normalToolCall_isNotRejected() throws Exception {
        LlmReply reply = GroqCompletionParser.parse(
                toolBody("tool_calls", "\"{\\\"action\\\":\\\"list\\\"}\""));
        assertFalse(reply.toolCalls.get(0).isRejected());
        assertEquals("list", reply.toolCalls.get(0).arguments.getString("action"));
        assertEquals("tool_calls", reply.finishReason);
    }
}
