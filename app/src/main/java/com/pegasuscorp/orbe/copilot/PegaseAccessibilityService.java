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
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
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
    public void onInterrupt() {}

    /**
     * Tap écran (gesture) — repli quand ACTION_CLICK a11y échoue (WebView / sections Wiki).
     * Si le tap tombe dans la bulle : la replie (NOT_TOUCHABLE seul insuffisant sur Nothing).
     */
    public boolean tapScreen(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (x < 0 || y < 0) return false;

        Rect overlay = FloatingOrbService.getOverlayScreenBounds();
        boolean hitOverlay = FloatingOrbService.containsScreenPoint(x, y);
        Log.i(TAG, "tapScreen x=" + Math.round(x) + " y=" + Math.round(y)
                + " overlay=" + (overlay != null ? overlay.toShortString() : "none")
                + " hitOverlay=" + hitOverlay);

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        boolean evacuated = FloatingOrbService.evacuateForScreenTap(x, y);
        schedulePassthroughRestore(evacuated ? 900L : 500L);

        if (evacuated || hitOverlay) {
            main.postDelayed(() -> {
                Rect after = FloatingOrbService.getOverlayScreenBounds();
                boolean stillHit = FloatingOrbService.containsScreenPoint(x, y);
                Log.i(TAG, "tapScreen afterEvacuate overlay="
                        + (after != null ? after.toShortString() : "none")
                        + " stillHit=" + stillHit);
                dispatchTapGesture(gesture, x, y, hitOverlay);
            }, 80L);
            return true;
        }
        FloatingOrbService.setTouchPassthrough(true);
        return dispatchTapGesture(gesture, x, y, false);
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
