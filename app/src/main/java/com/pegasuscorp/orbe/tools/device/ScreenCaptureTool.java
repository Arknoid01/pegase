package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.copilot.ScreenCaptureHelper;
import com.pegasuscorp.orbe.copilot.ScreenCapturePermissionActivity;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.chat.OpenRouterVisionClient;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONObject;

/** Capture d'écran + analyse vision (copilote). */
public final class ScreenCaptureTool implements Tool {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "screen_capture";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.UI;
    }

    @Override
    public String description() {
        return "screen_capture(prompt?:str) — Capture l'écran et l'analyse (OCR / vision).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String prompt = params.optString("prompt", "").trim();
        if (TextUtils.isEmpty(prompt)) {
            prompt = "Décris ce que tu vois à l'écran et extrais le texte important.";
        }
        final String userPrompt = prompt;
        ScreenCaptureHelper.capture(ctx, new ScreenCaptureHelper.Callback() {
            @Override
            public void onNeedPermission() {
                MAIN.post(() -> ScreenCapturePermissionActivity.request(ctx, granted -> {
                    if (!granted) {
                        cb.onError("Capture d'écran refusée.");
                        return;
                    }
                    execute(ctx, params, cb);
                }));
            }

            @Override
            public void onCaptured(byte[] jpeg) {
                OpenRouterVisionClient.analyzeJpegBytes(ctx, jpeg, userPrompt,
                        new OpenRouterVisionClient.Callback() {
                            @Override
                            public void onSuccess(String analysis) {
                                MAIN.post(() -> cb.onSuccess(ToolResult.text(analysis)));
                            }

                            @Override
                            public void onError(String message) {
                                MAIN.post(() -> cb.onError(message));
                            }
                        });
            }

            @Override
            public void onError(String message) {
                MAIN.post(() -> cb.onError(message));
            }
        });
    }
}
