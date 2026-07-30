package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pipeline d'analyse continue — déclenché par changement de contenu, pas par intervalle.
 * Priorité arbre a11y ; OCR en complément via {@link OcrFallback}. Filtre local avant cloud.
 */
public final class CopilotAnalysisEngine {

    public interface CloudSink {
        void onFilteredText(String packageName, String text, String reason);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_HIGHLIGHTS = 12;
    private static final long HIGHLIGHT_DEBOUNCE_MS = 4_000L;

    private static volatile long lastHighlightMs;

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
            List<A11ySnapshot.Node> nodes = A11ySnapshot.loadNodes(ctx);
            String text = joinText(nodes);
            if (text.isEmpty()) return;
            ScreenContextStore.update(ctx, packageName, text);
            maybeShowElementHighlights(ctx, nodes);
            List<A11ySnapshot.Node> foreign = CopilotLocaleFilter.foreignBlocks(nodes);
            if (!foreign.isEmpty()) {
                CloudSink sink = cloudSink;
                if (sink != null) {
                    MAIN.post(() -> sink.onFilteredText(packageName, text, "langue_etrangere"));
                }
            }
        });
    }

    private static void maybeShowElementHighlights(Context ctx, List<A11ySnapshot.Node> nodes) {
        if (!CopilotPrefs.isElementHighlightEnabled(ctx)) return;
        long now = System.currentTimeMillis();
        if (now - lastHighlightMs < HIGHLIGHT_DEBOUNCE_MS) return;
        List<ElementHighlightService.HighlightRect> rects = buildHighlightRects(nodes);
        if (rects.isEmpty()) return;
        lastHighlightMs = now;
        MAIN.post(() -> ElementHighlightService.show(ctx, rects));
    }

    static List<ElementHighlightService.HighlightRect> buildHighlightRects(
            List<A11ySnapshot.Node> nodes) {
        List<ElementHighlightService.HighlightRect> out = new ArrayList<>();
        if (nodes == null) return out;
        for (A11ySnapshot.Node n : nodes) {
            if (!n.clickable || !n.hasBounds()) continue;
            out.add(new ElementHighlightService.HighlightRect(
                    n.left, n.top, n.right, n.bottom, n.text));
            if (out.size() >= MAX_HIGHLIGHTS) break;
        }
        return out;
    }

    private static String joinText(List<A11ySnapshot.Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (A11ySnapshot.Node n : nodes) {
            if (n.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(n.text);
        }
        return sb.toString().trim();
    }
}
