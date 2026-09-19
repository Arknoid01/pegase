package com.pegasuscorp.orbe.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Signature d'un outil, lue depuis la ligne de description {@code id(p1:type, p2?:type) — texte}.
 *
 * <p>Tolérante : les descriptions ne sont pas toutes écrites au même format
 * (enums quotés ou non, valeurs par défaut {@code ="fr"}, tableaux {@code [{…}]}, {@code (...)}).
 * Ce que l'on ne sait pas lire est ignoré, jamais rejeté.
 */
public final class ToolSignature {

    public static final class Param {
        public final String name;
        /** string | integer | number | boolean | array | object */
        public final String type;
        public final boolean optional;
        /** Valeurs autorisées ; vide si non énuméré. */
        public final List<String> enumValues;

        Param(String name, String type, boolean optional, List<String> enumValues) {
            this.name = name;
            this.type = type;
            this.optional = optional;
            this.enumValues = Collections.unmodifiableList(enumValues);
        }
    }

    private static final java.util.Set<String> TYPE_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("str", "string", "int", "integer", "long", "float", "double",
                    "number", "bool", "boolean", "list", "array", "object", "any"));

    /** Texte d'aide seul (sans la partie {@code id(...)}). */
    public final String summary;
    public final List<Param> params;

    private ToolSignature(String summary, List<Param> params) {
        this.summary = summary;
        this.params = Collections.unmodifiableList(params);
    }

    public Param find(String name) {
        for (Param p : params) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    public static ToolSignature parse(String description) {
        String desc = description != null ? description.trim() : "";
        int open = desc.indexOf('(');
        if (open <= 0 || !isIdentifier(desc.substring(0, open).trim())) {
            return new ToolSignature(desc, new ArrayList<>());
        }
        int close = matchingParen(desc, open);
        if (close < 0) return new ToolSignature(desc, new ArrayList<>());

        String inner = desc.substring(open + 1, close);
        String rest = desc.substring(close + 1).trim();
        if (rest.startsWith("—") || rest.startsWith("–") || rest.startsWith("-")) {
            rest = rest.substring(1).trim();
        }
        List<Param> params = new ArrayList<>();
        for (String token : splitTopLevel(inner)) {
            Param p = parseParam(token.trim());
            if (p != null) params.add(p);
        }
        return new ToolSignature(rest.isEmpty() ? desc : rest, params);
    }

    private static Param parseParam(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0) return null;
        String left = token.substring(0, colon).trim();
        boolean optional = left.endsWith("?");
        String name = optional ? left.substring(0, left.length() - 1).trim() : left;
        if (!isIdentifier(name)) return null;

        String rhs = token.substring(colon + 1).trim();
        // valeur par défaut : lang?:str="fr" → ne pas la lire comme un enum
        int eq = topLevelIndexOf(rhs, '=');
        if (eq > 0) {
            rhs = rhs.substring(0, eq).trim();
            optional = true;
        }

        List<String> enums = new ArrayList<>();
        String type;
        if (rhs.startsWith("[")) {
            type = "array";
        } else if (rhs.startsWith("{")) {
            type = "object";
        } else if (rhs.startsWith("\"") || rhs.contains("|")) {
            type = "string";
            boolean typeAlternation = false;
            for (String alt : rhs.split("\\|")) {
                String v = alt.trim();
                boolean quoted = v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"");
                if (quoted) v = v.substring(1, v.length() - 1);
                // « changes?:str|list » = deux types acceptés, pas deux valeurs littérales
                if (!quoted && TYPE_WORDS.contains(v.toLowerCase(Locale.ROOT))) typeAlternation = true;
                if (isEnumValue(v)) enums.add(v);
            }
            if (typeAlternation || (enums.size() < 2 && rhs.indexOf('|') < 0)) {
                // alternance de types, ou un seul littéral (valeur d'exemple) : pas un enum
                enums.clear();
            }
        } else {
            type = normalizeType(rhs);
        }
        return new Param(name, type, optional, enums);
    }

    private static String normalizeType(String raw) {
        int end = 0;
        while (end < raw.length() && Character.isLetterOrDigit(raw.charAt(end))) end++;
        String t = raw.substring(0, end).toLowerCase(Locale.ROOT);
        switch (t) {
            case "int":
            case "integer":
            case "long":
                return "integer";
            case "float":
            case "double":
            case "number":
                return "number";
            case "bool":
            case "boolean":
                return "boolean";
            default:
                return "string";
        }
    }

    private static boolean isEnumValue(String v) {
        if (v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') return false;
        }
        return true;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int topLevelIndexOf(String s, char target) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '[' || c == '{' || c == '(') depth++;
            else if (c == ']' || c == '}' || c == ')') depth--;
            else if (c == target && depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '[' || c == '{' || c == '(') depth++;
            else if (c == ']' || c == '}' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }
}
