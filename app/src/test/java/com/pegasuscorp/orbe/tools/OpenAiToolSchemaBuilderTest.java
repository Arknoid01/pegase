package com.pegasuscorp.orbe.tools;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.EnumSet;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OpenAiToolSchemaBuilderTest {

    @Test
    public void build_weatherTool_hasCityAndDays() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JSONObject weather = OpenAiToolSchemaBuilder.toOpenAiTool(registry.findById("weather"));
        JSONObject params = weather.getJSONObject("function").getJSONObject("parameters");
        JSONObject properties = params.getJSONObject("properties");

        assertEquals("weather", weather.getJSONObject("function").getString("name"));
        assertEquals("integer", properties.getJSONObject("days").getString("type"));
        assertEquals("string", properties.getJSONObject("city").getString("type"));
    }

    @Test
    public void build_filtersByTag() {
        ToolRegistry registry = new ToolRegistry();
        int all = OpenAiToolSchemaBuilder.build(registry, EnumSet.allOf(ToolTag.class)).length();
        int weatherOnly = OpenAiToolSchemaBuilder.build(
                registry, EnumSet.of(ToolTag.WEATHER, ToolTag.NOTEPAD, ToolTag.MEMORY, ToolTag.DEVICE))
                .length();
        assertTrue(weatherOnly < all);
        assertTrue(weatherOnly >= 1);
    }

    @Test
    public void build_deviceTool_exposesAction() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        JSONObject device = OpenAiToolSchemaBuilder.toOpenAiTool(registry.findById("device"));
        JSONObject properties = device.getJSONObject("function")
                .getJSONObject("parameters")
                .getJSONObject("properties");
        assertTrue("schema device doit exposer action", properties.has("action"));
        assertEquals("string", properties.getJSONObject("action").getString("type"));
    }

    @Test
    public void inferTypeHint_quotedEnum_isString() {
        assertEquals("string",
                OpenAiToolSchemaBuilder.inferTypeHint("action:\"battery\"|\"time\"|\"date\""));
        assertEquals("int", OpenAiToolSchemaBuilder.inferTypeHint("days:int"));
    }
}
