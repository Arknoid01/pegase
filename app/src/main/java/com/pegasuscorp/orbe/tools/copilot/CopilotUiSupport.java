package com.pegasuscorp.orbe.tools.copilot;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.copilot.A11yDownAlert;
import com.pegasuscorp.orbe.copilot.AccessibilityAccess;
import com.pegasuscorp.orbe.copilot.CopilotStatusBridge;
import com.pegasuscorp.orbe.copilot.PegaseAccessibilityService;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.tools.ToolCallback;

/**
 * Vérifications communes outils copilote v4.
 */
public final class CopilotUiSupport {

    private static final String TAG = "CopilotUiSupport";

    private CopilotUiSupport() {}

    public static PegaseAccessibilityService requireService(Context ctx, ToolCallback cb) {
        PegaseAccessibilityService svc = PegaseAccessibilityService.getInstance();
        if (svc != null) return svc;

        if (!AccessibilityAccess.isEnabled(ctx)) {
            Trace.copilotUi("a11y_unavailable", "settings_off",
                    "Service accessibilité désactivé", "", "");
            A11yDownAlert.notifyServiceDown(ctx, "settings_off");
            cb.onError("Active le service d'accessibilité Pégase dans les réglages, puis réessaie.");
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return null;
        }
        // Toggle ON mais instance null = process service mort / pas encore rebound.
        Log.w(TAG, "a11y enabled in Settings but getInstance()=null");
        Trace.copilotUi("a11y_unavailable", "instance_null",
                "Service activé mais non connecté", "", "");
        cb.onError("Service d'accessibilité activé mais pas connecté — "
                + "désactive puis réactive « Pégase copilote », ou redémarre l'app.");
        return null;
    }

    /** Statut « Action en cours » dans la bulle copilote. */
    public static void notifyActionInProgress(Context ctx, ToolCallback cb) {
        notifyProgress(ctx, cb, ctx.getString(R.string.copilot_status_action));
    }

    public static void notifyProgress(Context ctx, ToolCallback cb, String message) {
        if (message == null || message.isEmpty()) {
            CopilotStatusBridge.clearStatus(ctx);
            return;
        }
        if (cb != null) {
            // Un seul chemin : le session observer met à jour le bandeau.
            // Ne pas aussi broadcaster — sinon le clear onToolResult est écrasé
            // par un Intent retardé (« Action en cours… » collé).
            cb.onProgress(message);
            return;
        }
        CopilotStatusBridge.postStatus(ctx, message);
    }
}
