package com.pegasuscorp.orbe.tools.copilot;

import com.pegasuscorp.orbe.copilot.A11yUiExecutor;
import com.pegasuscorp.orbe.copilot.A11yUiMatcher;
import com.pegasuscorp.orbe.copilot.UiExplainHelper;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Explique un élément à l'écran — texte local puis overlay (v4).
 */
public final class UiExplainTool implements Tool {

    @Override
    public String id() {
        return "ui_explain";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.DEVICE;
    }

    @Override
    public String description() {
        return "ui_explain(target:str, view_id:str, question:str) — Explique un élément "
                + "visible (overlay local). Ne ouvre jamais de page web.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (CopilotUiSupport.requireService(ctx, cb) == null) return;
        A11yUiMatcher.Target target = UiExplainHelper.resolveTarget(ctx, params);
        if (target == null) {
            cb.onError("Je ne trouve pas l'élément à expliquer.");
            return;
        }
        String question = params.optString("question", "").trim();
        String answer = UiExplainHelper.localAnswer(target, question);
        if (TextUtils.isEmpty(answer)) {
            cb.onError("Pas de texte lisible sur cet élément — essaie la capture écran.");
            return;
        }
        A11yUiExecutor.highlightTarget(ctx, target);
        UiExplainHelper.showOverlay(ctx, target, answer);
        cb.onSuccess(ToolResult.text(answer));
    }
}
