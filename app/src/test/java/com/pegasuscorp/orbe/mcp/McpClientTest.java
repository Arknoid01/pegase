package com.pegasuscorp.orbe.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class McpClientTest {

    /** Serveur MCP simulé : répond selon la méthode JSON-RPC. */
    private static final class FakeServer implements McpTransport {
        final List<JSONObject> received = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        boolean sse;
        int status = 200;

        @Override
        public Response post(String url, Map<String, String> h, String body) throws Exception {
            JSONObject req = new JSONObject(body);
            received.add(req);
            headers.add(h);
            if (status != 200) return new Response(status, "text/plain", "no", null);
            String method = req.getString("method");
            if (!req.has("id")) return new Response(202, "", "", null);
            JSONObject result = new JSONObject();
            switch (method) {
                case "initialize":
                    result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
                    break;
                case "tools/list":
                    if (req.getJSONObject("params").has("cursor")) {
                        result.put("tools", new org.json.JSONArray().put(new JSONObject()
                                .put("name", "b").put("description", "outil b")));
                    } else {
                        result.put("tools", new org.json.JSONArray().put(new JSONObject()
                                .put("name", "a").put("description", "outil a")
                                .put("inputSchema", new JSONObject().put("type", "object"))));
                        result.put("nextCursor", "p2");
                    }
                    break;
                case "tools/call":
                    result.put("content", new org.json.JSONArray()
                            .put(new JSONObject().put("type", "text")
                                    .put("text", "salut " + req.getJSONObject("params")
                                            .getJSONObject("arguments").optString("nom")))
                            .put(new JSONObject().put("type", "image").put("data", "xx")));
                    break;
                default:
                    return new Response(200, "application/json", new JSONObject()
                            .put("jsonrpc", "2.0").put("id", req.get("id"))
                            .put("error", new JSONObject().put("message", "méthode inconnue"))
                            .toString(), null);
            }
            String rpc = new JSONObject().put("jsonrpc", "2.0").put("id", req.get("id"))
                    .put("result", result).toString();
            if (sse) {
                return new Response(200, "text/event-stream",
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}\n\n"
                                + "event: message\ndata: " + rpc + "\n\n",
                        "sess-1");
            }
            return new Response(200, "application/json", rpc, "sess-1");
        }
    }

    @Test
    public void initialize_puisListTools_paginé_etSessionRenvoyée() throws Exception {
        FakeServer server = new FakeServer();
        McpClient client = new McpClient(server, "https://x.test/mcp", "tok");
        client.initialize();
        List<McpClient.ToolInfo> tools = client.listTools();

        assertEquals(2, tools.size());
        assertEquals("a", tools.get(0).name);
        assertEquals("b", tools.get(1).name);
        assertEquals("notifications/initialized", server.received.get(1).getString("method"));
        assertEquals("Bearer tok", server.headers.get(0).get("Authorization"));
        assertFalse(server.headers.get(0).containsKey("Mcp-Session-Id"));
        assertEquals("sess-1", server.headers.get(2).get("Mcp-Session-Id"));
    }

    @Test
    public void callTool_texteEtContenuNonTexte_viaSse() throws Exception {
        FakeServer server = new FakeServer();
        server.sse = true;
        McpClient client = new McpClient(server, "https://x.test/mcp", "");
        client.initialize();
        McpClient.CallResult r = client.callTool("a", new JSONObject().put("nom", "Yannick"));

        assertEquals("salut Yannick\n[image non affiché]", r.text);
        assertFalse(r.isError);
        assertFalse(server.headers.get(0).containsKey("Authorization"));
    }

    @Test
    public void erreurs_401_etErreurRpc() throws Exception {
        FakeServer server = new FakeServer();
        server.status = 401;
        try {
            new McpClient(server, "https://x.test/mcp", "").initialize();
            fail();
        } catch (McpException e) {
            assertTrue(e.getMessage().contains("accès refusé"));
        }
        FakeServer ok = new FakeServer();
        McpClient client = new McpClient(ok, "https://x.test/mcp", "");
        JSONObject dummy = new JSONObject();
        try {
            // méthode inconnue côté serveur simulé → erreur JSON-RPC
            java.lang.reflect.Method m = McpClient.class.getDeclaredMethod("request",
                    String.class, JSONObject.class);
            m.setAccessible(true);
            m.invoke(client, "inconnue", dummy);
            fail();
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause().getMessage().contains("méthode inconnue"));
        }
    }

    @Test
    public void urlAutorisee_httpsOuReseauLocal() {
        assertTrue(McpClient.isAllowedUrl("https://mcp.example.com/mcp"));
        assertTrue(McpClient.isAllowedUrl("http://192.168.1.20:8000/mcp"));
        assertTrue(McpClient.isAllowedUrl("http://100.101.102.103/mcp"));
        assertTrue(McpClient.isAllowedUrl("http://localhost:3000/mcp"));
        assertTrue(McpClient.isAllowedUrl("http://pegase-arch.tail1234.ts.net/mcp"));
        assertFalse(McpClient.isAllowedUrl("http://example.com/mcp"));
        assertFalse(McpClient.isAllowedUrl("ftp://x"));
        assertFalse(McpClient.isAllowedUrl(""));
        assertFalse(McpClient.isAllowedUrl(null));
    }
}
