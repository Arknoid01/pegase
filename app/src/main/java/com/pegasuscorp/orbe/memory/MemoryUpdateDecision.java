package com.pegasuscorp.orbe.memory;

import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Décision Mem0-style pour un candidat vs voisins existants.
 */
public final class MemoryUpdateDecision {

    public enum Op {
        ADD, UPDATE, DELETE, NOOP
    }

    public final Op op;
    /** Index 0-based dans la liste des voisins fournie au LLM (−1 si ADD/NOOP). */
    public final int targetIndex;
    /** Contenu enrichi (UPDATE) ou null. */
    public final String updatedContent;
    public final String reason;

    public MemoryUpdateDecision(Op op, int targetIndex, String updatedContent, String reason) {
        this.op = op != null ? op : Op.NOOP;
        this.targetIndex = targetIndex;
        this.updatedContent = updatedContent;
        this.reason = reason != null ? reason : "";
    }

    public static MemoryUpdateDecision noop(String reason) {
        return new MemoryUpdateDecision(Op.NOOP, -1, null, reason);
    }

    public static MemoryUpdateDecision add(String reason) {
        return new MemoryUpdateDecision(Op.ADD, -1, null, reason);
    }

    /**
     * Parse JSON libre : {@code {"op":"UPDATE","id":1,"content":"…","reason":"…"}}.
     * Accepte aussi event/function-style {@code {"name":"UPDATE",…}}.
     */
    public static MemoryUpdateDecision parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return noop("empty");
        String json = extractJsonObject(raw.trim());
        if (json == null) return noop("no_json");
        try {
            JSONObject o = new JSONObject(json);
            String opStr = firstNonEmpty(
                    o.optString("op", ""),
                    o.optString("operation", ""),
                    o.optString("action", ""),
                    o.optString("name", ""),
                    o.optString("function", ""));
            if (opStr.isEmpty() && o.has("arguments")) {
                Object args = o.opt("arguments");
                if (args instanceof JSONObject) {
                    JSONObject a = (JSONObject) args;
                    opStr = firstNonEmpty(a.optString("op", ""), a.optString("operation", ""));
                    o = a;
                } else if (args instanceof String) {
                    try {
                        JSONObject a = new JSONObject((String) args);
                        opStr = firstNonEmpty(a.optString("op", ""), a.optString("operation", ""));
                        o = a;
                    } catch (Exception ignored) {}
                }
            }
            Op op = parseOp(opStr);
            int id = o.optInt("id", o.optInt("target_id", o.optInt("memory_id", -1)));
            if (id < 0 && o.has("target")) {
                id = o.optInt("target", -1);
            }
            // LLM parfois 1-based
            if (id == 0 && o.has("id")) {
                // id 0 is valid 0-based; leave
            }
            String content = firstNonEmpty(
                    o.optString("content", ""),
                    o.optString("updated_content", ""),
                    o.optString("text", ""));
            String reason = o.optString("reason", o.optString("rationale", ""));
            if (op == Op.UPDATE && content.isEmpty()) {
                return noop("update_sans_content");
            }
            if ((op == Op.UPDATE || op == Op.DELETE) && id < 0) {
                return noop("cible_manquante");
            }
            return new MemoryUpdateDecision(op, id, content.isEmpty() ? null : content, reason);
        } catch (Exception e) {
            return noop("parse_error");
        }
    }

    static Op parseOp(String raw) {
        if (raw == null) return Op.NOOP;
        String s = raw.trim().toUpperCase();
        if (s.equals("ADD") || s.equals("CREATE") || s.equals("NEW")) return Op.ADD;
        if (s.equals("UPDATE") || s.equals("EDIT") || s.equals("MERGE")) return Op.UPDATE;
        if (s.equals("DELETE") || s.equals("REMOVE") || s.equals("INVALIDATE")) return Op.DELETE;
        if (s.equals("NOOP") || s.equals("NONE") || s.equals("SKIP") || s.equals("IGNORE")) {
            return Op.NOOP;
        }
        return Op.NOOP;
    }

    static String extractJsonObject(String text) {
        if (text.startsWith("```")) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) return text.substring(start, end + 1);
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (!TextUtils.isEmpty(v) && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /** Construit la liste numérotée pour le prompt. */
    public static String formatNeighbors(java.util.List<MemoryEntry> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) return "(aucun)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < neighbors.size(); i++) {
            MemoryEntry e = neighbors.get(i);
            if (e == null) continue;
            sb.append(i).append(". [").append(e.category != null ? e.category : "?")
                    .append("] ").append(e.content != null ? e.content : "").append('\n');
        }
        return sb.toString().trim();
    }

    public static String buildPrompt(String candidate, String category,
            java.util.List<MemoryEntry> neighbors) {
        return "Tu gères la mémoire longue de Pégase (assistant vocal).\n"
                + "Nouveau fait candidat (catégorie " + (category != null ? category : "session")
                + ") :\n« " + candidate + " »\n\n"
                + "Souvenirs proches existants (id = index) :\n"
                + formatNeighbors(neighbors) + "\n\n"
                + "Choisis UNE opération JSON strict, sans markdown :\n"
                + "{\"op\":\"ADD|UPDATE|DELETE|NOOP\",\"id\":0,\"content\":\"…\",\"reason\":\"…\"}\n"
                + "- ADD : fait nouveau, pas couvert par les voisins (id ignoré, content ignoré).\n"
                + "- UPDATE : enrichit / corrige le voisin id (content = texte final fusionné).\n"
                + "- DELETE : le candidat contredit le voisin id → invalide ce voisin "
                + "(puis on ajoutera le candidat). id obligatoire.\n"
                + "- NOOP : doublon inutile, rien à faire.\n"
                + "Préfère UPDATE à ADD+DELETE quand c'est le même sujet mis à jour.";
    }
}
