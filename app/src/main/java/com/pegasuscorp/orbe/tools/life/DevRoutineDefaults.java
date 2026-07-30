package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.Tool;

import com.pegasuscorp.orbe.voice.LearnedToolPayload;

import org.json.JSONObject;

import java.util.Arrays;

/** Routines dev préconfigurées pour la boîte à outils. */
public final class DevRoutineDefaults {

    private DevRoutineDefaults() {}

    public static String onCodeRoutine() throws Exception {
        return LearnedToolPayload.buildComposite(Arrays.asList(
                openApp("RunPod"),
                openApp("Orion")), "On code");
    }

    public static String quickDevRoutine() throws Exception {
        return LearnedToolPayload.buildComposite(Arrays.asList(
                openApp("Orion")), "Orion");
    }

    private static String openApp(String name) throws Exception {
        return new JSONObject()
                .put("tool", "open_app")
                .put("params", new JSONObject().put("name", name))
                .toString();
    }
}
