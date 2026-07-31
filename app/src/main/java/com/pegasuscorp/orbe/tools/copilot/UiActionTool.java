package com.pegasuscorp.orbe.tools.copilot;

import com.pegasuscorp.orbe.copilot.A11yUiExecutor;
import com.pegasuscorp.orbe.copilot.PegaseAccessibilityService;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolTag;

import android.content.Context;

import org.json.JSONObject;

/**
 * Actions UI copilote v4 — clic, saisie, scroll, retour (accessibility).
 */
public final class UiActionTool implements Tool {

    @Override
    public String id() {
        return "ui_action";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.DEVICE;
    }

    @Override
    public String description() {
        return "ui_action(action:\"click\"|\"type\"|\"scroll\"|\"back\", target:str, "
                + "value:str, direction:\"up\"|\"down\") — Contrôle l'écran de l'app "
                + "autorisée. target = libellé visible (ex. « Astronomie »). "
                + "Clique directement — aucune question technique à l'utilisateur. "
                + "click peut demander confirmation.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        PegaseAccessibilityService svc = CopilotUiSupport.requireService(ctx, cb);
        if (svc == null) return;
        String action = params.optString("action", "").trim().toLowerCase();
        switch (action) {
            case "click":
            case "tap":
                A11yUiExecutor.executeClick(ctx, svc, params, cb);
                break;
            case "type":
            case "set_text":
                A11yUiExecutor.executeType(ctx, svc, params, cb);
                break;
            case "scroll":
                A11yUiExecutor.executeScroll(ctx, svc, params, cb);
                break;
            case "back":
                A11yUiExecutor.executeBack(ctx, svc, cb);
                break;
            default:
                cb.onError("Action ui_action inconnue : " + action);
        }
    }
}
