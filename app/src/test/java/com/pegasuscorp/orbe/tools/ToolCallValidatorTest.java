package com.pegasuscorp.orbe.tools;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ToolCallValidatorTest {

    private static Tool tool(String description, String explicitSchema) {
        return new Tool() {
            @Override public String id() { return "t"; }
            @Override public String description() { return description; }
            @Override public ToolTag tag() { return ToolTag.DEVICE; }
            @Override public JSONObject parameters() {
                try {
                    return explicitSchema != null ? new JSONObject(explicitSchema) : null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            @Override public void execute(Context ctx, JSONObject params, ToolCallback cb) {}
        };
    }

    private static ToolCallValidator.Failure check(Tool t, String args) throws Exception {
        return ToolCallValidator.validate(t, new JSONObject(args));
    }

    @Test
    public void derived_validCall_passes() throws Exception {
        Tool t = tool("alarm(action:\"add\"|\"list\", hour?:int) — x", null);
        assertNull(check(t, "{\"action\":\"add\",\"hour\":7}"));
    }

    @Test
    public void derived_doesNotEnforceRequired() throws Exception {
        // les descriptions ne marquent pas toujours les optionnels : {} reste toléré
        Tool t = tool("weather(city:str, days:int) — x", null);
        assertNull(check(t, "{}"));
    }

    @Test
    public void derived_wrongType_isRejected() throws Exception {
        Tool t = tool("alarm(action:\"add\", hour?:int) — x", null);
        ToolCallValidator.Failure f = check(t, "{\"action\":\"add\",\"hour\":\"sept\"}");
        assertNotNull(f);
        assertEquals("INVALID_TOOL_ARGUMENTS", f.code);
        assertTrue(f.message.contains("hour"));
    }

    @Test
    public void numericStrings_areAccepted() throws Exception {
        Tool t = tool("alarm(action:\"add\", hour?:int) — x", null);
        assertNull(check(t, "{\"action\":\"add\",\"hour\":\"7\"}"));
        assertNotNull(check(t, "{\"action\":\"add\",\"hour\":2.5}"));
    }

    @Test
    public void derivedEnum_isAdvisory_aliasesStillPass() throws Exception {
        // SpotifyTool accepte « stop » / « prev » sans les déclarer dans la description
        Tool t = tool("spotify(action:\"play\"|\"pause\"|\"next\") — x", null);
        assertNull(check(t, "{\"action\":\"stop\"}"));
    }

    @Test
    public void derivedString_acceptsArrays_becauseDescriptionsAreLoose() throws Exception {
        Tool t = tool("ui_action(action:\"click\", steps:str) — x", null);
        assertNull(check(t, "{\"steps\":[{\"action\":\"click\"}]}"));
    }

    @Test
    public void explicitEnum_isEnforced_caseInsensitive_andListsAllowedValues() throws Exception {
        Tool t = tool("x() — y", "{\"type\":\"object\",\"properties\":{\"action\":"
                + "{\"type\":\"string\",\"enum\":[\"on\",\"off\",\"toggle\"]}}}");
        assertNull(check(t, "{\"action\":\"ON\"}"));
        ToolCallValidator.Failure f = check(t, "{\"action\":\"blink\"}");
        assertNotNull(f);
        assertTrue(f.message.contains("on, off, toggle"));
    }

    @Test
    public void explicitSchema_enforcesRequired_andBlankCounts_asMissing() throws Exception {
        Tool t = tool("x() — y", "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
                + "\"required\":[\"q\"]}");
        assertNotNull(check(t, "{}"));
        assertNotNull(check(t, "{\"q\":\"  \"}"));
        assertNotNull(check(t, "{\"q\":null}"));
        assertNull(check(t, "{\"q\":\"ok\"}"));
    }

    @Test
    public void explicitSchema_bounds() throws Exception {
        Tool t = tool("x() — y", "{\"type\":\"object\",\"properties\":{\"n\":"
                + "{\"type\":\"integer\",\"minimum\":1,\"maximum\":5}}}");
        assertNull(check(t, "{\"n\":3}"));
        assertNotNull(check(t, "{\"n\":0}"));
        assertNotNull(check(t, "{\"n\":6}"));
    }

    @Test
    public void unknownExtraKeys_areTolerated() throws Exception {
        Tool t = tool("call(contact:str) — x", null);
        assertNull(check(t, "{\"contact\":\"Anne\",\"bonus\":1}"));
    }

    @Test
    public void errorJson_isReadableByModel() throws Exception {
        JSONObject o = new JSONObject(
                ToolCallValidator.errorJson("alarm", "INVALID_TOOL_ARGUMENTS", "hour : entier"));
        assertFalse(o.getBoolean("ok"));
        assertEquals("alarm", o.getString("tool"));
        assertEquals("INVALID_TOOL_ARGUMENTS", o.getString("code"));
    }
}
