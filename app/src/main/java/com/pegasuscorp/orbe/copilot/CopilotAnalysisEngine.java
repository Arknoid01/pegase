package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pipeline d'analyse continue — déclenché par changement de contenu, pas par intervalle.
 * Priorité arbre a11y ; OCR en complément (futur). Filtre local avant tout envoi cloud.
 */
public final class CopilotAnalysisEngine {

    public interface CloudSink {
        void onFilteredText(String packageName, String text, String reason);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context appContext;
    private volatile boolean screenOn = true;
    private volatile CloudSink cloudSink;

    public CopilotAnalysisEngine(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public void setScreenOn(boolean on) {
        screenOn = on;
    }

    public void setCloudSink(CloudSink sink) {
        cloudSink = sink;
    }

    public void onContentChanged(String packageName) {
        if (!screenOn) return;
        Context ctx = appContext;
        if (!CopilotPrefs.isScreenAnalysisEnabled(ctx)) return;
        if (!CopilotPrefs.isPackageAllowed(ctx, packageName)) return;

        IO.execute(() -> {
            String text = A11yTreeExtractor.extractPlainText(ctx);
            if (text.isEmpty()) return;
            ScreenContextStore.update(ctx, packageName, text);
            String filterReason = shouldSendToCloud(ctx, text);
            if (filterReason != null) {
                CloudSink sink = cloudSink;
                if (sink != null) {
                    MAIN.post(() -> sink.onFilteredText(packageName, text, filterReason));
                }
            }
        });
    }

    /**
     * Filtre local — retourne une raison si le texte devrait partir au cloud,
     * null si on reste 100% local.
     */
    static String shouldSendToCloud(Context ctx, String text) {
        if (text == null || text.trim().length() < 12) return null;
        // Heuristique : langue étrangère détectée (hors français système)
        int latin = 0;
        int accented = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            if ("àâäéèêëïîôùûüç".indexOf(Character.toLowerCase(c)) >= 0) accented++;
            if (Character.isLetter(c)) latin++;
        }
        if (latin > 20 && accented == 0 && cjk > 4) {
            return "langue_etrangere";
        }
        if (cjk > 8 && accented < 2) {
            return "langue_etrangere";
        }
        return null;
    }
}
