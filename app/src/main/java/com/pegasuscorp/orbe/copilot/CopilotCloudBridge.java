package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pont main process : filtre cloud → traduction → overlay positionné.
 */
public final class CopilotCloudBridge {

    private static final String TAG = "CopilotCloudBridge";
    private static final AtomicBoolean translating = new AtomicBoolean(false);

    private CopilotCloudBridge() {}

    public static void handleCloudCandidate(Context ctx, String packageName,
            String text, String reason) {
        Context app = ctx.getApplicationContext();
        if (!CopilotPrefs.isTranslationOverlayEnabled(app)) return;
        if (!"langue_etrangere".equals(reason) && !CopilotPrefs.isScreenAnalysisEnabled(app)) {
            return;
        }
        if (!translating.compareAndSet(false, true)) return;

        List<A11ySnapshot.Node> nodes = A11ySnapshot.loadNodes(app);
        List<A11ySnapshot.Node> foreign = CopilotLocaleFilter.foreignBlocks(nodes);
        if (foreign.isEmpty()) {
            translating.set(false);
            return;
        }

        Log.d(TAG, "Traduction de " + foreign.size() + " blocs (" + packageName + ")");
        CopilotTranslator.translateBlocks(app, foreign, new CopilotTranslator.Callback() {
            @Override
            public void onSuccess(List<TranslationOverlayService.TranslatedBlock> blocks) {
                translating.set(false);
                if (blocks != null && !blocks.isEmpty()) {
                    TranslationOverlayService.show(app, blocks);
                }
            }

            @Override
            public void onError(String message) {
                translating.set(false);
                Log.w(TAG, "Traduction échouée: " + message);
            }
        });
    }
}
