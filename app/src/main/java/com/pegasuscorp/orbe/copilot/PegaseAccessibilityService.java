package com.pegasuscorp.orbe.copilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.copilot.apps.CursorMicAction;
import com.pegasuscorp.orbe.copilot.apps.YouTubeSubtitleAction;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service d'accessibilité Pégase — actions locales hardcodées par app.
 * Analyse d'écran déclenchée par changement de contenu (pas d'intervalle fixe).
 * Liste blanche stricte via {@link CopilotPrefs}.
 */
public class PegaseAccessibilityService extends AccessibilityService {

    public static final String ACTION_CONTENT_CHANGED =
            "com.pegasuscorp.orbe.copilot.CONTENT_CHANGED";

    private static final String TAG = "PegaseA11y";

    private static volatile PegaseAccessibilityService instance;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingNotify;
    private String lastNotifiedPackage = "";
    private Runnable pendingPassthroughRestore;

    public static PegaseAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "onCreate — instance bound");
        try {
            com.pegasuscorp.orbe.diag.Trace.copilotUi(
                    "a11y_lifecycle", "create",
                    "Service accessibilité créé", "", "");
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "onDestroy — instance cleared");
        if (instance == this) instance = null;
        try {
            com.pegasuscorp.orbe.diag.Trace.copilotUi(
                    "a11y_disconnected", "service_destroy",
                    "Service accessibilité détruit", "", "");
        } catch (Exception ignored) {}
        try {
            A11yDownAlert.notifyServiceDown(this, "service_destroy");
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // Ceinture : certains OEM appellent connected sans passer par un onCreate
        // visible côté app après kill process.
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            // Compose / apps natives : beaucoup de nœuds « not important ».
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        Log.i(TAG, "onServiceConnected — ready");
        try {
            com.pegasuscorp.orbe.diag.Trace.copilotUi(
                    "a11y_lifecycle", "connected",
                    "Service accessibilité prêt", "", "");
        } catch (Exception ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "";
        if (!CopilotPrefs.isPackageAllowed(this, pkg)) return;
        if (!CopilotPrefs.isScreenAnalysisEnabled(this)) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        scheduleContentNotify(pkg);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "onInterrupt");
        try {
            com.pegasuscorp.orbe.diag.Trace.copilotUi(
                    "a11y_lifecycle", "interrupted",
                    "Service accessibilité interrompu", "", "");
        } catch (Exception ignored) {}
    }

    /**
     * Tap écran (gesture) — chemin principal des clics UI (ACTION_CLICK trop souvent fantôme).
     * Si le tap tombe dans la bulle : la replie (NOT_TOUCHABLE seul insuffisant sur Nothing).
     * Toujours différer le dispatch : passthrough / collapse sont postés sur le main thread.
     */
    public boolean tapScreen(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (x < 0 || y < 0) return false;

        Rect overlay = FloatingOrbService.getOverlayScreenBounds();
        boolean hitOverlay = FloatingOrbService.containsScreenPoint(x, y);
        Log.i(TAG, "tapScreen x=" + Math.round(x) + " y=" + Math.round(y)
                + " overlay=" + (overlay != null ? overlay.toShortString() : "none")
                + " hitOverlay=" + hitOverlay);

        // Micro-mouvement : certains Compose / OEM ignorent un tap « point mort ».
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + 2f, y + 2f);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        FloatingOrbService.evacuateForScreenTap(x, y);
        FloatingOrbService.setTouchPassthrough(true);
        schedulePassthroughRestore(hitOverlay ? 1300L : 700L);

        long delayMs = hitOverlay ? 180L : 120L;
        main.postDelayed(() -> dispatchTapAfterEvacuate(gesture, x, y, hitOverlay, 0), delayMs);
        return true;
    }

    /**
     * Si la bulle couvre encore le point après évacuation, re-évacue une fois
     * avant le geste — évite un clic absorbé par l'overlay.
     */
    private void dispatchTapAfterEvacuate(GestureDescription gesture, float x, float y,
            boolean hitOverlay, int retry) {
        Rect after = FloatingOrbService.getOverlayScreenBounds();
        boolean stillHit = FloatingOrbService.containsScreenPoint(x, y);
        Log.i(TAG, "tapScreen dispatch overlay="
                + (after != null ? after.toShortString() : "none")
                + " stillHit=" + stillHit + " retry=" + retry);
        if (stillHit && retry < 1) {
            FloatingOrbService.evacuateForScreenTap(x, y);
            FloatingOrbService.setTouchPassthrough(true);
            main.postDelayed(() -> dispatchTapAfterEvacuate(gesture, x, y, true, retry + 1), 160L);
            return;
        }
        dispatchTapGesture(gesture, x, y, hitOverlay || stillHit);
    }

    private boolean dispatchTapGesture(GestureDescription gesture, float x, float y,
            boolean wasHitOverlay) {
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "tapScreen completed x=" + Math.round(x) + " y=" + Math.round(y)
                        + " (wasHitOverlay=" + wasHitOverlay + ")");
                restorePassthroughSoon();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "tapScreen cancelled x=" + Math.round(x) + " y=" + Math.round(y)
                        + " (wasHitOverlay=" + wasHitOverlay + ")");
                restorePassthroughSoon();
            }
        }, null);

        if (!dispatched) {
            Log.w(TAG, "tapScreen dispatchGesture=false");
            restorePassthroughSoon();
        }
        return dispatched;
    }

    private void schedulePassthroughRestore(long delayMs) {
        if (pendingPassthroughRestore != null) {
            main.removeCallbacks(pendingPassthroughRestore);
        }
        pendingPassthroughRestore = this::restorePassthroughSoon;
        main.postDelayed(pendingPassthroughRestore, delayMs);
    }

    private void restorePassthroughSoon() {
        if (pendingPassthroughRestore != null) {
            main.removeCallbacks(pendingPassthroughRestore);
            pendingPassthroughRestore = null;
        }
        FloatingOrbService.restoreAfterScreenGesture();
    }

    /** Active les sous-titres YouTube (action locale, sans cloud). */
    public boolean activateYouTubeSubtitles() {
        if (!CopilotPrefs.isPackageAllowed(this, CopilotPrefs.PKG_YOUTUBE)) {
            CopilotPrefs.enableYouTubeCopilot(this);
        }
        AccessibilityNodeInfo root = A11yRootPicker.preferAppRoot(this);
        if (root == null) return false;
        try {
            return YouTubeSubtitleAction.toggleSubtitles(root);
        } finally {
            root.recycle();
        }
    }

    /** Clique le micro Cursor web (libellé a11y ≠ « micro »). */
    public boolean activateCursorMic() {
        AccessibilityNodeInfo root = A11yRootPicker.preferForegroundRoot(this);
        if (root == null) return false;
        try {
            A11yUiMatcher.Target mic = CursorMicAction.findMic(root);
            if (mic == null) return false;
            if (mic.hasBounds()
                    && tapScreen(mic.left + (mic.right - mic.left) / 2f,
                    mic.top + (mic.bottom - mic.top) / 2f)) {
                return true;
            }
            return CursorMicAction.clickMic(root);
        } finally {
            root.recycle();
        }
    }

    private void scheduleContentNotify(String pkg) {
        if (pkg.equals(lastNotifiedPackage) && pendingNotify != null) {
            main.removeCallbacks(pendingNotify);
        }
        pendingNotify = () -> {
            pendingNotify = null;
            lastNotifiedPackage = pkg;
            IO.execute(() -> {
                AccessibilityNodeInfo root = A11yRootPicker.preferAppRoot(
                        PegaseAccessibilityService.this);
                String rootPkg = A11yRootPicker.packageOf(root);
                if (root == null || rootPkg.isEmpty()) {
                    return;
                }
                try {
                    A11yTreeExtractor.writeSnapshot(
                            PegaseAccessibilityService.this, root, rootPkg);
                    if (OcrFallback.needsFallback(PegaseAccessibilityService.this)) {
                        OcrFallback.tryEnrich(PegaseAccessibilityService.this, rootPkg);
                    }
                } finally {
                    root.recycle();
                }
                final String notifyPkg = rootPkg;
                main.post(() -> {
                    Intent i = new Intent(ACTION_CONTENT_CHANGED);
                    i.setPackage(getPackageName());
                    i.putExtra("package", notifyPkg);
                    sendBroadcast(i);
                    CopilotClient.notifyContentChanged(
                            PegaseAccessibilityService.this, notifyPkg);
                });
            });
        };
        main.postDelayed(pendingNotify, 280L);
    }
}
