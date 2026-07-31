package com.pegasuscorp.orbe.tools.copilot;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.pegasuscorp.orbe.copilot.AccessibilityAccess;
import com.pegasuscorp.orbe.copilot.PegaseAccessibilityService;
import com.pegasuscorp.orbe.tools.ToolCallback;

/**
 * Vérifications communes outils copilote v4.
 */
public final class CopilotUiSupport {

    private CopilotUiSupport() {}

    public static PegaseAccessibilityService requireService(Context ctx, ToolCallback cb) {
        if (!AccessibilityAccess.isEnabled(ctx)) {
            cb.onError("Active le service d'accessibilité Pégase dans les réglages, puis réessaie.");
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return null;
        }
        PegaseAccessibilityService svc = PegaseAccessibilityService.getInstance();
        if (svc == null) {
            cb.onError("Service d'accessibilité pas encore prêt — réessaie.");
            return null;
        }
        return svc;
    }
}
