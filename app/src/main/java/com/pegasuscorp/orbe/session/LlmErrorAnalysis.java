package com.pegasuscorp.orbe.session;

import org.json.JSONObject;

/** Analyse des erreurs LLM liées aux outils (lecture seule, sans état). */
public final class LlmErrorAnalysis {

    private LlmErrorAnalysis() {}

    /** HTTP 400 « validation d'outil » Groq/OpenAI (tool_use_failed, schéma non respecté…). */
    public static boolean isHttp400ToolValidation(String error) {
        if (error == null || error.isEmpty()) return false;
        String e = error.toLowerCase(java.util.Locale.ROOT);
        // Codes / messages Groq typiques (même sans « HTTP 400 » explicite dans le wrap)
        if (e.contains("tool_use_failed") || e.contains("tool call validation")
                || e.contains("failed to validate tool")
                || (e.contains("parameters for tool") && e.contains("did not match schema"))) {
            return true;
        }
        if (!e.contains("http 400") && !e.contains("status code 400")
                && !e.contains("\"code\":400") && !e.contains(" 400 ")) {
            return false;
        }
        return e.contains("tool") || e.contains("function") || e.contains("tool_call")
                || e.contains("tools") || e.contains("schema") || e.contains("validation")
                || e.contains("invalid");
    }

    /**
     * Extrait un aperçu utile du body 400 (failed_generation / message).
     * Visible tests.
     */
    public static String summarizeToolValidationError(String error) {
        if (error == null || error.isEmpty()) return "";
        String raw = error.trim();
        try {
            int brace = raw.indexOf('{');
            if (brace >= 0) {
                JSONObject root = new JSONObject(raw.substring(brace));
                JSONObject err = root.optJSONObject("error");
                if (err == null && root.has("message")) err = root;
                if (err != null) {
                    String msg = err.optString("message", "").trim();
                    String failed = err.optString("failed_generation", "").trim();
                    if (!failed.isEmpty()) {
                        String clip = failed.length() > 120
                                ? failed.substring(0, 117).trim() + "…" : failed;
                        return (msg.isEmpty() ? "tool validation" : msg) + " | gen=" + clip;
                    }
                    if (!msg.isEmpty()) return truncateDetail(msg, 200);
                }
            }
        } catch (Exception ignored) {}
        return truncateDetail(raw, 200);
    }

    private static String truncateDetail(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1).trim() + "…";
    }
}
