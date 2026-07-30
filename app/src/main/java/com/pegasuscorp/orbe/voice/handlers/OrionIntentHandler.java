package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

public final class OrionIntentHandler implements IntentHandler {

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (looksLikeOrionManager(fold)) {
            RoutedIntent orion = routeOrion(context, text, fold);
            if (orion != null) return orion;
        }
        return null;
    }

    static boolean looksLikeOrionManager(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeOrion(f);
    }

    static RoutedIntent routeOrion(Context context, String text, String fold) {
        String f = fold.replace('\'', ' ').replace('’', ' ');
        try {
            JSONObject p = new JSONObject();
            String hint;
            double conf = 0.93;
            boolean comfy = com.pegasuscorp.orbe.memory.IntentDetector.looksLikeComfy(f);
            if (f.contains("eteins") || f.contains("arrete") || f.contains("stop")
                    || f.contains("couper") || f.contains("eteindre")) {
                p.put("action", "stop");
                hint = comfy ? "comfy stop" : "orion stop";
            } else if (comfy) {
                p.put("action", "start_comfy");
                p.put("confirm", false);
                hint = "comfy start";
                conf = 0.95;
            } else if (f.contains("coute") || f.contains("cout") || f.contains("en ligne")
                    || f.contains("status") || f.contains("etat")
                    || (f.contains("combien") && f.contains("orion"))) {
                p.put("action", "status");
                hint = "orion status";
            } else if (f.contains("liste") || f.contains("quels gpu") || f.contains("quels pods")) {
                p.put("action", "list_pods");
                hint = "orion list";
            } else {
                // lance / démarre Orion — confirmation via onConfirmNeeded
                p.put("action", "start");
                p.put("confirm", false);
                hint = "orion start";
                conf = 0.95;
            }
            String json = VoiceIntentSupport.toolJson("orion_manager", p);
            if (json == null) return null;
            return VoiceIntentSupport.routed(context, text, json, hint, conf);
        } catch (Exception e) {
            return null;
        }
    }
}
