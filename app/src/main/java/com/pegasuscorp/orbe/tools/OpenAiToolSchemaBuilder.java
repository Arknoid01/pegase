package com.pegasuscorp.orbe.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convertit {@link ToolRegistry} en schémas OpenAI tools pour Groq. */
public final class OpenAiToolSchemaBuilder {

    private static final Pattern SIGNATURE = Pattern.compile(
            "^(\\w+)\\(([^)]*)\\)\\s*[—–-]\\s*(.+)$", Pattern.DOTALL);
    /** name:type  ou  name:"enum"|…  → propriété string/int/… */
    private static final Pattern PARAM = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)\\s*:\\s*"
                    + "(?:\"[^\"]*\"(?:\\s*\\|\\s*\"[^\"]*\")*|\\w+)");

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
        String desc = tool.description();
        Matcher m = SIGNATURE.matcher(desc.trim());
        String fnDescription = desc.trim();
        String paramsInner = "";
        if (m.matches()) {
            fnDescription = m.group(3).trim();
            paramsInner = m.group(2).trim();
        }

        JSONObject parameters = buildParameters(paramsInner);
        JSONObject function = new JSONObject();
        try {
            function.put("name", tool.id());
            function.put("description", fnDescription);
            function.put("parameters", parameters);
            return new JSONObject()
                    .put("type", "function")
                    .put("function", function);
        } catch (Exception e) {
            throw new RuntimeException("Schéma outil invalide : " + tool.id(), e);
        }
    }

    private static JSONObject buildParameters(String paramsInner) throws Exception {
        JSONObject properties = new JSONObject();
        if (paramsInner != null && !paramsInner.isEmpty()) {
            Matcher pm = PARAM.matcher(paramsInner);
            while (pm.find()) {
                String name = pm.group(1);
                String typeHint = inferTypeHint(pm.group(0));
                properties.put(name, propertySchema(typeHint));
            }
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

    private static JSONObject propertySchema(String typeHint) throws Exception {
        switch (typeHint) {
            case "int":
            case "integer":
                return new JSONObject().put("type", "integer");
            case "bool":
            case "boolean":
                return new JSONObject().put("type", "boolean");
            case "float":
            case "number":
            case "double":
                return new JSONObject().put("type", "number");
            default:
                return new JSONObject().put("type", "string");
        }
    }
}
