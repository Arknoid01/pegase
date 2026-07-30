package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.Tool;

import com.pegasuscorp.orbe.voice.LearnedToolPayload;
import com.pegasuscorp.orbe.voice.SituationRoutineStore;

import org.json.JSONObject;

import java.util.Arrays;

/** Routines situationnelles par défaut (écrasables via SituationRoutineStore). */
public final class SituationRoutineDefaults {

    private SituationRoutineDefaults() {}

    public static String builtin(SituationRoutineStore.Slot slot) {
        try {
            switch (slot) {
                case MORNING:
                    return LearnedToolPayload.buildComposite(Arrays.asList(
                            toolJson("weather", new JSONObject().put("days", 1)),
                            toolJson("device", new JSONObject().put("action", "time"))),
                            "Matin");
                case EVENING:
                    return LearnedToolPayload.buildComposite(Arrays.asList(
                            toolJson("device", new JSONObject().put("action", "battery")),
                            toolJson("flashlight", new JSONObject().put("action", "off"))),
                            "Soir");
                case AFTERNOON:
                    return LearnedToolPayload.buildComposite(Arrays.asList(
                            toolJson("device", new JSONObject().put("action", "time")),
                            toolJson("device", new JSONObject().put("action", "battery"))),
                            "Après-midi");
                case NIGHT:
                    return LearnedToolPayload.buildComposite(Arrays.asList(
                            toolJson("flashlight", new JSONObject().put("action", "off")),
                            toolJson("device", new JSONObject().put("action", "battery"))),
                            "Nuit");
                case DEFAULT:
                default:
                    return LearnedToolPayload.buildComposite(Arrays.asList(
                            toolJson("device", new JSONObject().put("action", "time"))),
                            "Comme d'habitude");
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String toolJson(String tool, JSONObject params) throws Exception {
        return new JSONObject()
                .put("tool", tool)
                .put("params", params)
                .toString();
    }
}
