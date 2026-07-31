package com.pegasuscorp.orbe.tools.copilot;

import com.pegasuscorp.orbe.copilot.AccessibilityAccess;
import com.pegasuscorp.orbe.copilot.CopilotPrefs;
import com.pegasuscorp.orbe.copilot.PegaseAccessibilityService;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Actions copilote locales (accessibility hardcodé par app) — sans LLM cloud.
 */
public final class CopilotActionTool implements Tool {

    @Override
    public String id() {
        return "copilot_action";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.DEVICE;
    }

    @Override
    public String description() {
        return "copilot_action(action:\"youtube_subtitles\") — Actions copilote locales "
                + "dans l'app en cours (accessibility). youtube_subtitles = activer les "
                + "sous-titres YouTube.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params.optString("action", "").trim().toLowerCase();
        switch (action) {
            case "youtube_subtitles":
            case "youtube_cc":
                executeYouTubeSubtitles(ctx, cb);
                break;
            default:
                cb.onError("Action copilote inconnue : " + action);
        }
    }

    private static void executeYouTubeSubtitles(Context ctx, ToolCallback cb) {
        PegaseAccessibilityService svc = PegaseAccessibilityService.getInstance();
        if (svc == null && !AccessibilityAccess.isEnabled(ctx)) {
            CopilotPrefs.enableYouTubeCopilot(ctx);
            cb.onError("Active le service d'accessibilité Pégase dans les réglages, "
                    + "puis réessaie.");
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        }
        if (svc == null) {
            cb.onError("Service d'accessibilité pas encore prêt — réessaie.");
            return;
        }
        boolean ok = svc.activateYouTubeSubtitles();
        if (ok) {
            cb.onSuccess(ToolResult.text("J'active les sous-titres YouTube."));
        } else {
            cb.onError("Je n'ai pas trouvé le bouton sous-titres — ouvre une vidéo "
                    + "et réessaie.");
        }
    }
}
