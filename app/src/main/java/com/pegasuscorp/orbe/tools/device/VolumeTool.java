package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONObject;

/**
 * Volume média : monter / baisser / silence / état.
 */
public final class VolumeTool implements Tool {

    @Override
    public String id() {
        return "volume";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.VOLUME;
    }

    @Override
    public String description() {
        return "volume(action:\"up\"|\"down\"|\"mute\"|\"unmute\"|\"status\", steps?:int) — "
                + "Volume média du téléphone. steps=nombre de crans (défaut 1). "
                + "Exemple : {\"tool\":\"volume\",\"params\":{\"action\":\"mute\"}}.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            cb.onError("Je n'ai pas accès au volume.");
            return;
        }
        String action = params != null
                ? params.optString("action", "status").trim().toLowerCase()
                : "status";
        int steps = params != null ? params.optInt("steps", 1) : 1;
        if (steps <= 0) steps = 1;
        if (steps > 10) steps = 10;

        switch (action) {
            case "up":
            case "plus":
            case "augmenter":
                adjust(am, AudioManager.ADJUST_RAISE, steps);
                cb.onSuccess(ToolResult.text(statusLine(am, "Volume augmenté.")));
                break;
            case "down":
            case "moins":
            case "baisser":
                adjust(am, AudioManager.ADJUST_LOWER, steps);
                cb.onSuccess(ToolResult.text(statusLine(am, "Volume baissé.")));
                break;
            case "mute":
            case "silence":
            case "muet":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                cb.onSuccess(ToolResult.text("Volume en sourdine."));
                break;
            case "unmute":
            case "son":
            case "reactive":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                cb.onSuccess(ToolResult.text(statusLine(am, "Son réactivé.")));
                break;
            case "status":
            case "niveau":
            default:
                cb.onSuccess(ToolResult.text(statusLine(am, null)));
        }
    }

    private static void adjust(AudioManager am, int direction, int steps) {
        for (int i = 0; i < steps; i++) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    private static String statusLine(AudioManager am, String prefix) {
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int pct = max > 0 ? Math.round(100f * cur / max) : 0;
        String line = "Volume média à " + pct + " pour cent (" + cur + "/" + max + ").";
        if (prefix == null || prefix.isEmpty()) return line;
        return prefix + " " + line;
    }
}
