package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

public final class DiagIntentHandler implements IntentHandler {

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (looksLikeDiagBehaviorSearch(fold)) {
            String json = diagSearchJson(text);
            if (json != null) {
                return VoiceIntentSupport.routed(context, text, json, "diag search", 0.92);
            }
        }

        if (looksLikeDiagAnalyze(fold)) {
            try {
                String json = VoiceIntentSupport.toolJson("diag", new JSONObject().put("action", "analyze"));
                if (json != null) {
                    return VoiceIntentSupport.routed(context, text, json, "diag analyze", 0.93);
                }
            } catch (Exception ignored) {}
        }

        if (looksLikeDiagDetail(fold)) {
            try {
                JSONObject p = new JSONObject().put("action", "detail");
                if (text != null && !text.trim().isEmpty()) {
                    p.put("utterance", text.trim());
                }
                String json = VoiceIntentSupport.toolJson("diag", p);
                if (json != null) {
                    return VoiceIntentSupport.routed(context, text, json, "diag detail", 0.92);
                }
            } catch (Exception ignored) {}
        }

        // Relance après un bilan summary récent → detail
        if (looksLikeDiagDetailFollowUp(fold)
                && com.pegasuscorp.orbe.tools.knowledge.DiagTool.hasRecentSummary(context)) {
            try {
                JSONObject p = new JSONObject().put("action", "detail");
                if (text != null && !text.trim().isEmpty()) {
                    p.put("utterance", text.trim());
                }
                String day = com.pegasuscorp.orbe.tools.knowledge.DiagTool
                        .recentSummaryDayIso(context);
                if (day != null && !day.isEmpty()) p.put("date", day);
                String json = VoiceIntentSupport.toolJson("diag", p);
                if (json != null) {
                    return VoiceIntentSupport.routed(context, text, json,
                            "diag detail follow-up", 0.93);
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    static boolean looksLikeDiagBehaviorSearch(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagSearch(f);
    }

    static boolean looksLikeDiagAnalyze(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagAnalyze(f);
    }

    static boolean looksLikeDiagDetail(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagDetail(f);
    }

    static boolean looksLikeDiagDetailFollowUp(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeDiagDetailFollowUp(f);
    }

    static String diagSearchJson(String query) {
        try {
            JSONObject p = new JSONObject();
            p.put("action", "search");
            p.put("query", query == null ? "" : query.trim());
            return VoiceIntentSupport.toolJson("diag", p);
        } catch (Exception e) {
            return null;
        }
    }
}
