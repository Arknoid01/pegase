package com.pegasuscorp.orbe.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallValidator;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.EnumSet;

@RunWith(RobolectricTestRunner.class)
public class McpStoreTest {

    @Test
    public void outilsDesactivesParDefaut_puisExposesUneFoisActives() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        McpStore store = new McpStore(ctx);
        McpStore.Server s = store.add("Mon Serveur!", "https://x.test/mcp", "tok");
        assertEquals("mon_serveur", s.id);

        JSONObject schema = new JSONObject().put("type", "object").put("properties",
                new JSONObject().put("q", new JSONObject().put("type", "string")))
                .put("required", new org.json.JSONArray().put("q"));
        store.updateTools(s.id, Arrays.asList(
                new McpClient.ToolInfo("chercher", "Cherche des trucs", schema),
                new McpClient.ToolInfo("supprimer", "Supprime", null)));

        McpStore.reload(ctx);
        assertTrue(McpStore.enabledTools().isEmpty());
        assertNull(new ToolRegistry().findById("mcp_mon_serveur_chercher"));

        store.setToolEnabled(s.id, "chercher", true);
        McpStore.reload(ctx);
        assertEquals(1, McpStore.enabledTools().size());

        ToolRegistry registry = new ToolRegistry();
        Tool tool = registry.findById("mcp_mon_serveur_chercher");
        assertNotNull(tool);
        assertNull(registry.findById("mcp_mon_serveur_supprimer"));
        assertTrue(registry.listTools(EnumSet.of(ToolTag.MCP)).contains(tool));
        assertTrue(tool.description().contains("[MCP Mon Serveur!] Cherche des trucs"));

        // le schéma du serveur est appliqué localement (required q)
        assertNotNull(ToolCallValidator.validate(tool, new JSONObject()));
        assertNull(ToolCallValidator.validate(tool, new JSONObject().put("q", "x")));

        // rafraîchir conserve les activations
        store.updateTools(s.id, Arrays.asList(
                new McpClient.ToolInfo("chercher", "Cherche v2", schema)));
        McpStore.reload(ctx);
        assertEquals(1, McpStore.enabledTools().size());

        store.remove(s.id);
        McpStore.reload(ctx);
        assertTrue(McpStore.enabledTools().isEmpty());
    }

    @Test
    public void urlNonSure_refusee_etIdsUniques() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        McpStore store = new McpStore(ctx);
        try {
            store.add("x", "http://example.com/mcp", "");
            fail();
        } catch (McpException expected) {
            // ok
        }
        McpStore.Server a = store.add("Notes", "https://a.test/mcp", "");
        McpStore.Server b = store.add("Notes", "https://b.test/mcp", "");
        assertFalse(a.id.equals(b.id));
        assertEquals("notes2", b.id);
    }
}
