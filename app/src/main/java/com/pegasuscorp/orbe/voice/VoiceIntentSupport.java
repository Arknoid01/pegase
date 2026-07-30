package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.json.JSONObject;

/**
 * Package-shared helpers for voice intent handlers.
 */
public final class VoiceIntentSupport {

    private VoiceIntentSupport() {}

    public static VoiceIntentRouter.RoutedIntent routed(Context context, String text, String json,
            String hint, double confidence) {
        boolean confirm = VoiceConfirmation.needsConfirmation(context, confidence, hint, text);
        return new VoiceIntentRouter.RoutedIntent(text, json, hint, confidence, confirm);
    }

    public static String toolJson(String tool, JSONObject params) {
        try {
            return new JSONObject().put("tool", tool).put("params", params).toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
