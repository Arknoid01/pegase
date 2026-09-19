package com.pegasuscorp.orbe.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * Valide les arguments d'un appel d'outil <b>avant</b> exécution.
 *
 * <p>Vérifie le contrat d'appel (types, enums, bornes, {@code required}) et rien d'autre :
 * ni permission, ni confirmation. Une erreur n'est pas une exception : elle est renvoyée au
 * modèle comme résultat d'outil ({@link #errorJson}) pour qu'il corrige son appel.
 *
 * <p>Sous-ensemble de JSON Schema : type, enum, minimum/maximum, minLength/maxLength,
 * required, items.type. Les clés inconnues sont tolérées.
 *
 * <p>Inspiré d'{@code AgentToolCallValidator} (Eta, Mangi-11, PolyForm Noncommercial 1.0.0).
 */
public final class ToolCallValidator {

    public static final String CODE_INVALID_ARGUMENTS = "INVALID_TOOL_ARGUMENTS";

    /** Erreur de contrat : {@code code} stable, {@code message} lisible par le modèle. */
    public static final class Failure {
        public final String code;
        public final String message;

        Failure(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private ToolCallValidator() {}

    /**
     * @param tool outil visé
     * @param args arguments reçus du modèle
     * @return {@code null} si l'appel est valide.
     *
     * <p>Outil à schéma explicite ({@link Tool#parameters()}) : validation stricte. Outil à schéma
     * déduit de sa description : seuls les types scalaires (entier, nombre, booléen) sont
     * appliqués. Les enums et le type « texte » y restent indicatifs, car les descriptions ne
     * listent pas tous les alias réellement acceptés (ex. {@code spotify: stop, prev, status}).
     */
    public static Failure validate(Tool tool, JSONObject args) {
        if (tool == null) return null;
        JSONObject schema = OpenAiToolSchemaBuilder.parametersFor(tool);
        return validateSchema(schema, args != null ? args : new JSONObject(),
                tool.parameters() != null);
    }

    static Failure validateSchema(JSONObject schema, JSONObject args, boolean strict) {
        if (schema == null) return null;
        JSONObject properties = schema.optJSONObject("properties");

        JSONArray required = schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                String name = required.optString(i, "");
                if (name.isEmpty()) continue;
                Object v = args.opt(name);
                if (isAbsent(v) || (v instanceof String && ((String) v).trim().isEmpty())) {
                    return fail("Paramètre « " + name + " » obligatoire, absent ou vide.");
                }
            }
        }
        if (properties == null) return null;

        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject prop = properties.optJSONObject(name);
            Object value = args.opt(name);
            if (prop == null || isAbsent(value)) continue;
            String problem = checkValue(value, prop, strict);
            if (problem != null) return fail("Paramètre « " + name + " » : " + problem);
        }
        return null;
    }

    private static String checkValue(Object value, JSONObject schema, boolean strict) {
        String type = schema.optString("type", "");
        switch (type) {
            case "integer": {
                Long n = asInteger(value);
                if (n == null) return "entier attendu, reçu " + show(value) + ".";
                return checkRange(n.doubleValue(), schema);
            }
            case "number": {
                Double d = asNumber(value);
                if (d == null) return "nombre attendu, reçu " + show(value) + ".";
                return checkRange(d, schema);
            }
            case "boolean":
                if (asBoolean(value) == null) {
                    return "booléen attendu, reçu " + show(value) + ".";
                }
                return null;
            case "array": {
                if (!(value instanceof JSONArray)) return "tableau attendu, reçu " + show(value) + ".";
                JSONObject items = schema.optJSONObject("items");
                JSONArray arr = (JSONArray) value;
                if (items != null && items.length() > 0) {
                    for (int i = 0; i < arr.length(); i++) {
                        String p = checkValue(arr.opt(i), items, strict);
                        if (p != null) return "élément " + i + " : " + p;
                    }
                }
                return null;
            }
            case "object":
                return value instanceof JSONObject ? null
                        : "objet attendu, reçu " + show(value) + ".";
            case "string": {
                if (!strict) return null;
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    return "texte attendu, reçu " + show(value) + ".";
                }
                String text = String.valueOf(value);
                int min = schema.optInt("minLength", -1);
                int max = schema.optInt("maxLength", -1);
                if (min >= 0 && text.length() < min) return "au moins " + min + " caractères.";
                if (max >= 0 && text.length() > max) return "au plus " + max + " caractères.";
                return checkEnum(text, schema);
            }
            default:
                return null;
        }
    }

    private static String checkEnum(String text, JSONObject schema) {
        JSONArray allowed = schema.optJSONArray("enum");
        if (allowed == null || allowed.length() == 0) return null;
        String want = text.trim().toLowerCase(Locale.ROOT);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < allowed.length(); i++) {
            String v = String.valueOf(allowed.opt(i));
            if (v.toLowerCase(Locale.ROOT).equals(want)) return null;
            if (i > 0) list.append(", ");
            list.append(v);
        }
        return "valeur « " + text + " » non autorisée ; valeurs possibles : " + list + ".";
    }

    private static String checkRange(double n, JSONObject schema) {
        if (schema.has("minimum") && n < schema.optDouble("minimum")) {
            return "doit être ≥ " + trim(schema.optDouble("minimum")) + ".";
        }
        if (schema.has("maximum") && n > schema.optDouble("maximum")) {
            return "doit être ≤ " + trim(schema.optDouble("maximum")) + ".";
        }
        return null;
    }

    /** Entier, ou chaîne d'entier (les modèles envoient souvent {@code "2"}). */
    private static Long asInteger(Object v) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? (long) d : null;
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double asNumber(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true")) return true;
            if (s.equals("false")) return false;
        }
        return null;
    }

    private static boolean isAbsent(Object v) {
        return v == null || v == JSONObject.NULL;
    }

    private static String show(Object v) {
        String s = v instanceof String ? "\"" + v + "\"" : String.valueOf(v);
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static Failure fail(String message) {
        return new Failure(CODE_INVALID_ARGUMENTS, message);
    }

    /** Résultat d'outil d'échec, au format lu par le modèle. */
    public static String errorJson(String toolId, String code, String message) {
        try {
            return new JSONObject()
                    .put("ok", false)
                    .put("tool", toolId != null ? toolId : "")
                    .put("code", code)
                    .put("message", message)
                    .toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":\"" + code + "\"}";
        }
    }
}
