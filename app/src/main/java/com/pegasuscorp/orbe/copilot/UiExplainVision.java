package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.chat.OpenRouterVisionClient;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONObject;

/**
 * Repli vision pour {@code ui_explain} — capture, recadrage zone cible, analyse cloud.
 */
public final class UiExplainVision {

    private static final int CROP_PADDING_PX = 24;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiExplainVision() {}

    public static boolean needsVisionFallback(A11yUiMatcher.Target target) {
        return target != null && target.hasBounds() && TextUtils.isEmpty(target.text);
    }

    public static void explain(Context ctx, A11yUiMatcher.Target target, String question,
            ToolCallback cb) {
        if (ctx == null || target == null || !target.hasBounds()) {
            cb.onError("Zone à analyser introuvable.");
            return;
        }
        A11yUiExecutor.highlightTarget(ctx, target);
        postProgress(ctx, cb, ctx.getString(R.string.copilot_status_capturing));
        ScreenCaptureHelper.capture(ctx, new ScreenCaptureHelper.Callback() {
            @Override
            public void onNeedPermission() {
                MAIN.post(() -> {
                    postProgress(ctx, cb,
                            ctx.getString(R.string.copilot_status_capture_permission));
                    ScreenCapturePermissionActivity.request(ctx, granted -> {
                        if (!granted) {
                            cb.onError(ctx.getString(R.string.copilot_error_capture_denied));
                            return;
                        }
                        explain(ctx, target, question, cb);
                    });
                });
            }

            @Override
            public void onCaptured(byte[] jpeg) {
                postProgress(ctx, cb, ctx.getString(R.string.copilot_status_explain_vision));
                byte[] crop = cropJpegToBounds(jpeg, target.left, target.top,
                        target.right, target.bottom, CROP_PADDING_PX);
                String prompt = buildVisionPrompt(question, target);
                OpenRouterVisionClient.analyzeJpegBytes(ctx, crop, prompt,
                        new OpenRouterVisionClient.Callback() {
                            @Override
                            public void onSuccess(String analysis) {
                                MAIN.post(() -> finish(ctx, target, analysis, cb));
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

    private static void finish(Context ctx, A11yUiMatcher.Target target, String analysis,
            ToolCallback cb) {
        String answer = analysis != null ? analysis.trim() : "";
        if (answer.isEmpty()) {
            cb.onError("Je n'ai pas pu analyser visuellement cet élément.");
            return;
        }
        UiExplainHelper.showOverlay(ctx, target, answer);
        cb.onSuccess(ToolResult.text(answer));
    }

    static String buildVisionPrompt(String question, A11yUiMatcher.Target target) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es le copilote Pégase. L'utilisateur regarde une autre application.\n");
        sb.append("Tu reçois un extrait recadré de l'écran (zone ciblée");
        if (!TextUtils.isEmpty(target.viewId)) {
            sb.append(", id ").append(target.viewId);
        }
        sb.append(").\n");
        if (!TextUtils.isEmpty(question)) {
            sb.append("Question : ").append(question.trim()).append("\n");
        } else {
            sb.append("Explique ce que montre cette zone (icône, image, élément de jeu, etc.).\n");
        }
        sb.append("Réponds en français, 1 à 3 phrases courtes, sans markdown.");
        return sb.toString();
    }

    static byte[] cropJpegToBounds(byte[] jpeg, int left, int top, int right, int bottom,
            int paddingPx) {
        if (jpeg == null || jpeg.length == 0) return jpeg;
        Bitmap full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (full == null) return jpeg;
        try {
            int l = Math.max(0, left - paddingPx);
            int t = Math.max(0, top - paddingPx);
            int r = Math.min(full.getWidth(), right + paddingPx);
            int b = Math.min(full.getHeight(), bottom + paddingPx);
            int w = r - l;
            int h = b - t;
            if (w < 16 || h < 16) {
                return jpeg;
            }
            Bitmap crop = Bitmap.createBitmap(full, l, t, w, h);
            try {
                return OpenRouterVisionClient.compressBitmapToJpeg(crop);
            } finally {
                crop.recycle();
            }
        } finally {
            full.recycle();
        }
    }

    private static void postProgress(Context ctx, ToolCallback cb, String message) {
        if (cb != null && !TextUtils.isEmpty(message)) {
            cb.onProgress(message);
        }
        CopilotStatusBridge.postStatus(ctx, message);
    }
}
