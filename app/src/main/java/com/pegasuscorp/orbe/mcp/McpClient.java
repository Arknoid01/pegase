package com.pegasuscorp.orbe.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client MCP minimal (Streamable HTTP, JSON-RPC 2.0) : initialize, tools/list, tools/call.
 * Les réponses {@code application/json} et {@code text/event-stream} sont acceptées.
 */
public final class McpClient {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final int MAX_RESULT_CHARS = 8_000;
    private static final int MAX_TOOL_PAGES = 10;

    public static final class ToolInfo {
        public final String name;
        public final String description;
        public final JSONObject inputSchema;

        public ToolInfo(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description != null ? description : "";
            this.inputSchema = inputSchema != null ? inputSchema : new JSONObject();
        }
    }

    public static final class CallResult {
        public final String text;
        public final boolean isError;

        CallResult(String text, boolean isError) {
            this.text = text;
            this.isError = isError;
        }
    }

    private final McpTransport transport;
    private final String url;
    private final String token;
    private String sessionId;
    private int nextId = 1;

    public McpClient(McpTransport transport, String url, String token) {
        this.transport = transport;
        this.url = url;
        this.token = token;
    }

    /** Autorise https, et http seulement vers localhost / réseau privé / Tailscale. */
    public static boolean isAllowedUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("https://")) return u.length() > "https://".length();
        if (!u.startsWith("http://")) return false;
        String host = u.substring("http://".length()).split("[/:?#]", 2)[0];
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.startsWith("10.") || host.startsWith("192.168.")
                || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
                || host.matches("100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\..*")
                || host.endsWith(".ts.net") || host.endsWith(".local");
    }

    public void initialize() throws McpException {
        JSONObject params = new JSONObject();
        try {
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", new JSONObject().put("name", "pegase").put("version", "1.0"));
        } catch (JSONException e) {
            throw new McpException("Requête initialize invalide.", e);
        }
        request("initialize", params);
        notifyServer("notifications/initialized");
    }

    public List<ToolInfo> listTools() throws McpException {
        List<ToolInfo> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            JSONObject params = new JSONObject();
            try {
                if (cursor != null) params.put("cursor", cursor);
            } catch (JSONException ignored) {
            }
            JSONObject result = request("tools/list", params);
            JSONArray tools = result.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null || t.optString("name", "").isEmpty()) continue;
                    out.add(new ToolInfo(t.optString("name"), t.optString("description", ""),
                            t.optJSONObject("inputSchema")));
                }
            }
            cursor = result.optString("nextCursor", "");
            if (cursor.isEmpty()) break;
        }
        return out;
    }

    public CallResult callTool(String name, JSONObject arguments) throws McpException {
        JSONObject params = new JSONObject();
        try {
            params.put("name", name);
            params.put("arguments", arguments != null ? arguments : new JSONObject());
        } catch (JSONException e) {
            throw new McpException("Arguments invalides.", e);
        }
        JSONObject result = request("tools/call", params);
        StringBuilder sb = new StringBuilder();
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type", "");
                String piece;
                if ("text".equals(type)) {
                    piece = item.optString("text", "");
                } else if ("resource".equals(type) && item.optJSONObject("resource") != null) {
                    piece = item.optJSONObject("resource").optString("text", "[ressource]");
                } else {
                    piece = "[" + (type.isEmpty() ? "contenu" : type) + " non affiché]";
                }
                if (sb.length() > 0) sb.append("\n");
                sb.append(piece);
            }
        }
        if (sb.length() == 0 && result.has("structuredContent")) {
            sb.append(result.opt("structuredContent"));
        }
        String text = sb.toString();
        if (text.length() > MAX_RESULT_CHARS) {
            text = text.substring(0, MAX_RESULT_CHARS) + "\n[… tronqué]";
        }
        return new CallResult(text, result.optBoolean("isError", false));
    }

    private void notifyServer(String method) throws McpException {
        JSONObject msg = new JSONObject();
        try {
            msg.put("jsonrpc", "2.0").put("method", method);
        } catch (JSONException e) {
            throw new McpException("Notification invalide.", e);
        }
        send(msg.toString());
    }

    private JSONObject request(String method, JSONObject params) throws McpException {
        int id = nextId++;
        JSONObject msg = new JSONObject();
        try {
            msg.put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params);
        } catch (JSONException e) {
            throw new McpException("Requête invalide.", e);
        }
        McpTransport.Response resp = send(msg.toString());
        JSONObject rpc = findResponse(resp, id);
        JSONObject error = rpc.optJSONObject("error");
        if (error != null) {
            throw new McpException("Serveur MCP : " + error.optString("message", "erreur"));
        }
        JSONObject result = rpc.optJSONObject("result");
        return result != null ? result : new JSONObject();
    }

    private McpTransport.Response send(String body) throws McpException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        if (token != null && !token.isEmpty()) headers.put("Authorization", "Bearer " + token);
        if (sessionId != null) {
            headers.put("Mcp-Session-Id", sessionId);
            headers.put("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        McpTransport.Response resp;
        try {
            resp = transport.post(url, headers, body);
        } catch (Exception e) {
            throw new McpException("Serveur MCP injoignable : "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
        if (resp.sessionId != null && !resp.sessionId.isEmpty()) sessionId = resp.sessionId;
        if (resp.status == 401 || resp.status == 403) {
            throw new McpException("Serveur MCP : accès refusé (jeton invalide ou manquant).");
        }
        if (resp.status >= 400) {
            throw new McpException("Serveur MCP : HTTP " + resp.status);
        }
        return resp;
    }

    /** Extrait la réponse JSON-RPC d'identifiant {@code id} (JSON simple ou flux SSE). */
    static JSONObject findResponse(McpTransport.Response resp, int id) throws McpException {
        try {
            if (resp.contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                StringBuilder data = new StringBuilder();
                for (String line : (resp.body + "\n\n").split("\r?\n", -1)) {
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append("\n");
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty() && data.length() > 0) {
                        JSONObject o = tryObject(data.toString());
                        data.setLength(0);
                        if (o != null && o.optInt("id", -1) == id
                                && (o.has("result") || o.has("error"))) {
                            return o;
                        }
                    }
                }
                throw new McpException("Serveur MCP : pas de réponse dans le flux.");
            }
            String text = resp.body.trim();
            if (text.startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && o.optInt("id", -1) == id) return o;
                }
                throw new McpException("Serveur MCP : réponse absente du lot.");
            }
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new McpException("Serveur MCP : réponse illisible.", e);
        }
    }

    private static JSONObject tryObject(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }
}
