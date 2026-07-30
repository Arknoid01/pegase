package com.pegasuscorp.orbe.tools;

import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Filet pour les appels LLM {@code tool({})} sans action :
 * injecte le dernier message user dans {@code query}/{@code utterance}
 * pour que {@code resolveAction} puisse déduire l'intention.
 */
public final class EmptyToolParams {

    private EmptyToolParams() {}

    public static boolean needsUtteranceSeed(String toolId) {
        if (toolId == null) return false;
        String id = toolId.trim().toLowerCase();
        return "device".equals(id) || "brief".equals(id) || "diag".equals(id);
    }

    /**
     * Si params vides / sans action utile, copie le texte user dans query+utterance.
     * Ne remplace jamais une action déjà fournie.
     */
    public static JSONObject seedUtteranceIfEmpty(String toolId, JSONObject params,
            String userText) {
        if (!needsUtteranceSeed(toolId)) {
            return params != null ? params : new JSONObject();
        }
        JSONObject p = params != null ? params : new JSONObject();
        if (!isActionBlank(p)) return p;
        if (!isHintBlank(p)) return p;
        if (TextUtils.isEmpty(userText) || userText.trim().isEmpty()) return p;
        try {
            JSONObject copy = new JSONObject(p.toString());
            String seed = userText.trim();
            if (TextUtils.isEmpty(copy.optString("query", "").trim())) {
                copy.put("query", seed);
            }
            if (TextUtils.isEmpty(copy.optString("utterance", "").trim())) {
                copy.put("utterance", seed);
            }
            return copy;
        } catch (Exception e) {
            return p;
        }
    }

    static boolean isActionBlank(JSONObject params) {
        if (params == null) return true;
        return TextUtils.isEmpty(params.optString("action", "").trim());
    }

    static boolean isHintBlank(JSONObject params) {
        if (params == null) return true;
        return TextUtils.isEmpty(params.optString("query", "").trim())
                && TextUtils.isEmpty(params.optString("utterance", "").trim())
                && TextUtils.isEmpty(params.optString("q", "").trim())
                && TextUtils.isEmpty(params.optString("type", "").trim())
                && TextUtils.isEmpty(params.optString("text", "").trim());
    }
}
