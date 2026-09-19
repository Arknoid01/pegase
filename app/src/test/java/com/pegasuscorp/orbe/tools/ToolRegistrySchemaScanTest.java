package com.pegasuscorp.orbe.tools;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.EnumSet;

import static org.junit.Assert.*;

/** Garde-fou : chaque outil du registre a une signature lisible et un schéma valide. */
@RunWith(RobolectricTestRunner.class)
public class ToolRegistrySchemaScanTest {

    @Test
    public void everyRegisteredTool_hasParsableSchema() {
        ToolRegistry registry = new ToolRegistry();
        StringBuilder report = new StringBuilder("SCHEMA-SCAN\n");
        for (Tool tool : registry.listTools(EnumSet.allOf(ToolTag.class))) {
            ToolSignature sig = ToolSignature.parse(tool.description());
            JSONObject schema = OpenAiToolSchemaBuilder.parametersFor(tool);
            assertEquals("object", schema.optString("type"));
            for (ToolSignature.Param p : sig.params) {
                assertTrue(tool.id() + "." + p.name, schema.optJSONObject("properties").has(p.name));
                report.append(String.format("  %s.%s%s : %s %s%n", tool.id(), p.name,
                        p.optional ? "?" : "", p.type, p.enumValues));
            }
        }
        System.out.println(report);
    }
}
