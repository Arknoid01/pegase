package com.pegasuscorp.orbe.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.mcp.McpTool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Serveurs MCP configurés + outils découverts. Chaque outil est désactivé par défaut : seuls
 * ceux que l'utilisateur active sont exposés au modèle.
 */
public final class McpStore {

    private static final String PREFS = "pegase_mcp";
    private static final String KEY_SERVERS = "servers";

    public static final class ToolEntry {
        public final String name;
        public final String description;
        public final JSONObject schema;
        public boolean enabled;

        ToolEntry(String name, String description, JSONObject schema, boolean enabled) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.enabled = enabled;
        }
    }

    public static final class Server {
        public final String id;
        public String name;
        public String url;
        public String token;
        public final List<ToolEntry> tools = new ArrayList<>();

        Server(String id, String name, String url, String token) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.token = token;
        }
    }

    private static volatile List<Tool> snapshot = Collections.emptyList();

    private final SharedPreferences prefs;

    public McpStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Outils MCP activés, prêts à être exposés (voir {@link #reload}). */
    public static List<Tool> enabledTools() {
        return snapshot;
    }

    /** Recharge l'instantané d'outils depuis les préférences (appeler après chaque modification). */
    public static void reload(Context ctx) {
        List<Tool> out = new ArrayList<>();
        try {
            for (Server s : new McpStore(ctx).servers()) {
                for (ToolEntry t : s.tools) {
                    if (t.enabled) out.add(new McpTool(s.id, s.name, t.name, t.description, t.schema));
                }
            }
        } catch (Exception ignored) {
        }
        snapshot = Collections.unmodifiableList(out);
    }

    public List<Server> servers() {
        List<Server> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Server s = new Server(o.getString("id"), o.optString("name"), o.optString("url"),
                        o.optString("token"));
                JSONArray tools = o.optJSONArray("tools");
                if (tools != null) {
                    for (int j = 0; j < tools.length(); j++) {
                        JSONObject t = tools.getJSONObject(j);
                        s.tools.add(new ToolEntry(t.getString("name"), t.optString("description"),
                                t.optJSONObject("schema"), t.optBoolean("enabled", false)));
                    }
                }
                out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public Server find(String id) {
        for (Server s : servers()) if (s.id.equals(id)) return s;
        return null;
    }

    /** Ajoute un serveur ; l'URL doit être https (ou http en réseau local). */
    public Server add(String name, String url, String token) throws McpException {
        String cleanUrl = url != null ? url.trim() : "";
        if (!McpClient.isAllowedUrl(cleanUrl)) {
            throw new McpException("URL refusée : utilise https (http seulement en réseau local).");
        }
        String base = slug(name);
        List<Server> all = servers();
        String id = base;
        for (int n = 2; contains(all, id); n++) id = base + n;
        Server s = new Server(id, name != null && !name.trim().isEmpty() ? name.trim() : id,
                cleanUrl, token != null ? token.trim() : "");
        all.add(s);
        save(all);
        return s;
    }

    public void remove(String id) {
        List<Server> all = servers();
        all.removeIf(s -> s.id.equals(id));
        save(all);
    }

    public void setToolEnabled(String serverId, String toolName, boolean enabled) {
        List<Server> all = servers();
        for (Server s : all) {
            if (!s.id.equals(serverId)) continue;
            for (ToolEntry t : s.tools) if (t.name.equals(toolName)) t.enabled = enabled;
        }
        save(all);
    }

    /**
     * Remplace la liste d'outils d'un serveur par celle découverte. Les activations existantes
     * sont conservées ; un nouvel outil est désactivé.
     */
    public void updateTools(String serverId, List<McpClient.ToolInfo> discovered) {
        List<Server> all = servers();
        for (Server s : all) {
            if (!s.id.equals(serverId)) continue;
            List<ToolEntry> fresh = new ArrayList<>();
            for (McpClient.ToolInfo info : discovered) {
                boolean was = false;
                for (ToolEntry old : s.tools) if (old.name.equals(info.name)) was = old.enabled;
                fresh.add(new ToolEntry(info.name, info.description, info.inputSchema, was));
            }
            s.tools.clear();
            s.tools.addAll(fresh);
        }
        save(all);
    }

    private void save(List<Server> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Server s : all) {
                JSONObject o = new JSONObject().put("id", s.id).put("name", s.name)
                        .put("url", s.url).put("token", s.token);
                JSONArray tools = new JSONArray();
                for (ToolEntry t : s.tools) {
                    tools.put(new JSONObject().put("name", t.name)
                            .put("description", t.description).put("schema", t.schema)
                            .put("enabled", t.enabled));
                }
                o.put("tools", tools);
                arr.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply();
    }

    private static boolean contains(List<Server> all, String id) {
        for (Server s : all) if (s.id.equals(id)) return true;
        return false;
    }

    /** Identifiant [a-z0-9_] court, utilisé dans les ids d'outils. */
    public static String slug(String name) {
        String s = (name != null ? name : "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "serveur";
        return s.length() > 16 ? s.substring(0, 16) : s;
    }
}
