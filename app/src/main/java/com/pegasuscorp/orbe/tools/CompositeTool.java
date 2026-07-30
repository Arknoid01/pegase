package com.pegasuscorp.orbe.tools;

import android.content.Context;

import com.pegasuscorp.orbe.voice.LearnedToolPayload;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exécute une séquence d'outils apprise (« on code » = Orion puis RunPod…). */
public final class CompositeTool implements Tool {

    private final ToolRegistry registry;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public CompositeTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return LearnedToolPayload.COMPOSITE_TOOL;
    }

    @Override
    public ToolTag tag() {
        return ToolTag.COMPOSITE;
    }

    @Override
    public String description() {
        return "composite(label?:str, steps:[{tool,params}]) — Séquence d'outils enchaînés.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        io.execute(() -> {
            try {
                JSONArray steps = params.getJSONArray("steps");
                String label = params.optString("label", "séquence");
                runStep(ctx, steps, 0, label, new StringBuilder(), cb);
            } catch (Exception e) {
                cb.onError("Séquence : " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    private void runStep(Context ctx, JSONArray steps, int index, String label,
            StringBuilder replies, ToolCallback cb) {
        if (index >= steps.length()) {
            String out = replies.length() == 0
                    ? "Séquence « " + label + " » terminée."
                    : replies.toString().trim();
            cb.onSuccess(ToolResult.text(out));
            return;
        }
        try {
            JSONObject step = steps.getJSONObject(index);
            String toolId = step.optString("tool", "");
            Tool tool = registry.findById(toolId);
            if (tool == null) {
                cb.onError("Outil inconnu dans la séquence : " + toolId);
                return;
            }
            JSONObject stepParams = step.optJSONObject("params");
            if (stepParams == null) stepParams = new JSONObject();
            final JSONObject paramsCopy = stepParams;
            tool.execute(ctx, paramsCopy, new ToolCallback() {
                @Override
                public void onSuccess(ToolResult result) {
                    appendReply(replies, result);
                    runStep(ctx, steps, index + 1, label, replies, cb);
                }

                @Override
                public void onSuccessAndExit(ToolResult result) {
                    appendReply(replies, result);
                    runStep(ctx, steps, index + 1, label, replies, cb);
                }

                @Override
                public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                    cb.onConfirmNeeded(question, onConfirm, onCancel);
                }

                @Override
                public void onChoiceNeeded(String title, String[] labels,
                        java.util.function.IntConsumer onChosen, Runnable onCancel) {
                    cb.onChoiceNeeded(title, labels, onChosen, onCancel);
                }

                @Override
                public void onError(String error) {
                    cb.onError("Étape " + (index + 1) + " : " + error);
                }
            });
        } catch (Exception e) {
            cb.onError("Étape " + (index + 1) + " : "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private static void appendReply(StringBuilder replies, ToolResult result) {
        if (result == null) return;
        String spoken = result.text;
        if (spoken == null || spoken.trim().isEmpty()) return;
        if (replies.length() > 0) replies.append(' ');
        replies.append(spoken.trim());
    }
}
