package com.pegasuscorp.orbe.tools.copilot;

import android.content.Context;

import com.pegasuscorp.orbe.copilot.PegaseAccessibilityService;
import com.pegasuscorp.orbe.copilot.UiLoopRunner;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

/**
 * Boucle a11y goal-driven — replanifie après chaque geste (vs {@code ui_action.steps} figé).
 */
public final class UiLoopTool implements Tool {

    @Override
    public String id() {
        return "ui_loop";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.UI;
    }

    @Override
    public String description() {
        return "ui_loop(goal:str) — Parcours UI adaptatif : lit l'écran, 1 action, "
                + "re-lit, decide, jusqu'à finish_task. "
                + "Préférer pour objectifs multi-écrans / app inconnue / imprévus "
                + "(cookie, clavier, libellé changeant). "
                + "Pour 2–3 gestes stables sur app connue : ui_action.steps suffit "
                + "(plus rapide). Max " + UiLoopRunner.MAX_TURNS + " tours.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        PegaseAccessibilityService svc = CopilotUiSupport.requireService(ctx, cb);
        if (svc == null) return;
        String goal = "";
        if (params != null) {
            goal = params.optString("goal",
                    params.optString("objective",
                            params.optString("task", ""))).trim();
        }
        UiLoopRunner.run(ctx, svc, goal, cb);
    }
}
