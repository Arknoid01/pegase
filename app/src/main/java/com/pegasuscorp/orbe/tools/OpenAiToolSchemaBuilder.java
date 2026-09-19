package com.pegasuscorp.orbe.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.Locale;

/** Convertit {@link ToolRegistry} en schémas OpenAI tools pour Groq. */
public final class OpenAiToolSchemaBuilder {

    private OpenAiToolSchemaBuilder() {}

    public static JSONArray build(ToolRegistry registry, EnumSet<ToolTag> allowed) {
        JSONArray tools = new JSONArray();
        for (Tool tool : registry.listTools(allowed)) {
            tools.put(toOpenAiTool(tool));
        }
        return tools;
    }

    public static JSONObject toOpenAiTool(Tool tool) {
        try {
            return toOpenAiToolUnchecked(tool);
        } catch (Exception e) {
            throw new RuntimeException("Schéma outil invalide : " + tool.id(), e);
        }
    }

    static JSONObject toOpenAiToolUnchecked(Tool tool) throws Exception {
        String desc = tool.description() != null ? tool.description().trim() : "";
        ToolSignature sig = ToolSignature.parse(desc);

        JSONObject parameters = parametersFor(tool, sig);
        JSONObject function = new JSONObject();
        function.put("name", tool.id());
        function.put("description", sig.summary);
        function.put("parameters", parameters);
        return new JSONObject()
                .put("type", "function")
                .put("function", function);
    }

    /** Schéma des paramètres : explicite si l'outil en déclare un, sinon déduit de la description. */
    static JSONObject parametersFor(Tool tool) {
        try {
            return parametersFor(tool, ToolSignature.parse(tool.description()));
        } catch (Exception e) {
            throw new RuntimeException("Schéma outil invalide : " + tool.id(), e);
        }
    }

    private static JSONObject parametersFor(Tool tool, ToolSignature sig) throws Exception {
        JSONObject explicit = tool.parameters();
        return explicit != null ? explicit : buildParameters(sig);
    }

    /**
     * Schéma envoyé au fournisseur : volontairement souple (pas de {@code required}).
     * Groq / gpt-oss répondent HTTP 400 « tool call validation » quand un appel ne respecte
     * pas strictement le schéma ; la validation stricte est faite localement par
     * {@link ToolCallValidator}, qui renvoie l'erreur au modèle pour qu'il se corrige.
     */
    private static JSONObject buildParameters(ToolSignature sig) throws Exception {
        JSONObject properties = new JSONObject();
        for (ToolSignature.Param p : sig.params) {
            JSONObject prop = new JSONObject().put("type", p.type);
            if ("array".equals(p.type)) {
                prop.put("items", new JSONObject());
            }
            if (!p.enumValues.isEmpty()) {
                JSONArray values = new JSONArray();
                for (String v : p.enumValues) values.put(v);
                prop.put("enum", values);
            }
            properties.put(p.name, prop);
        }
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                // true : gpt-oss / Groq rejettent souvent les clés extra si false
                // (HTTP 400 Tool call validation) — on tolère le surplus.
                .put("additionalProperties", true);
    }

    /** Extrait le type après « : » ; enums quotés → string. */
    static String inferTypeHint(String paramToken) {
        if (paramToken == null) return "string";
        int colon = paramToken.indexOf(':');
        if (colon < 0 || colon + 1 >= paramToken.length()) return "string";
        String rhs = paramToken.substring(colon + 1).trim();
        if (rhs.startsWith("\"") || rhs.contains("|")) return "string";
        int end = 0;
        while (end < rhs.length() && Character.isLetterOrDigit(rhs.charAt(end))) end++;
        return end > 0 ? rhs.substring(0, end).toLowerCase(Locale.ROOT) : "string";
    }
}
