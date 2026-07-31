package com.pegasuscorp.orbe.copilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    private static volatile PegaseAccessibilityService instance;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingNotify;
    private String lastNotifiedPackage = "";

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
     */
    public boolean tapScreen(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (x < 0 || y < 0) return false;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
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
