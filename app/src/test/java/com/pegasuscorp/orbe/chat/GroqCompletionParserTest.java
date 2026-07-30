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
}
