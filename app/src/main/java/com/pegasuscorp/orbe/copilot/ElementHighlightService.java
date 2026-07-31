package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Surlignage des éléments interactifs via les bounds a11y (100% local).
 */
public class ElementHighlightService extends Service {

    private static final String CHANNEL_ID = "pegase_element_highlight";
    private static final int NOTIF_ID = 87;
    private static final long ACTION_HIDE_MS = 1_400L;
    private static final long CONTINUOUS_HIDE_MS = 4_000L;
    private static final String EXTRA_HIDE_MS = "hide_ms";
    private static final String EXTRA_RESET_TIMER = "reset_timer";

    private static volatile List<HighlightRect> pendingRects = new ArrayList<>();
    private static volatile long pendingHideMs = CONTINUOUS_HIDE_MS;
    private static volatile boolean pendingResetTimer = true;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout root;
    private Runnable hideRunnable;
    private long hideDeadlineElapsed;
    private float density;

    public static final class HighlightRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final String label;

        public HighlightRect(int left, int top, int right, int bottom, String label) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.label = label != null ? label : "";
        }
    }

    public static void show(Context ctx, List<HighlightRect> rects) {
        if (rects == null || rects.isEmpty()) {
            hide(ctx);
            return;
        }
        if (!CopilotPrefs.isElementHighlightEnabled(ctx)) return;
        // Continu : ne pas repousser le timer à chaque refresh Chrome (sinon cadre éternel).
        showInternal(ctx, rects, CONTINUOUS_HIDE_MS, false);
    }

    /** Surlignage ponctuel avant action UI v4 — indépendant du toggle continu. */
    public static void showActionTarget(Context ctx, int left, int top, int right, int bottom,
            String label) {
        if (ctx == null) return;
        showInternal(ctx, java.util.Collections.singletonList(
                new HighlightRect(left, top, right, bottom, label)), ACTION_HIDE_MS, true);
    }

    private static void showInternal(Context ctx, List<HighlightRect> rects,
            long hideMs, boolean resetTimer) {
        if (rects == null || rects.isEmpty()) return;
        pendingRects = new ArrayList<>(rects);
        pendingHideMs = hideMs;
        pendingResetTimer = resetTimer;
        Intent i = new Intent(ctx, ElementHighlightService.class);
        i.setAction("show");
        i.putExtra(EXTRA_HIDE_MS, hideMs);
        i.putExtra(EXTRA_RESET_TIMER, resetTimer);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {}
    }

    public static void hide(Context ctx) {
        if (ctx == null) return;
        pendingRects = new ArrayList<>();
        Intent i = new Intent(ctx, ElementHighlightService.class);
        i.setAction("hide");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            try {
                ctx.stopService(new Intent(ctx, ElementHighlightService.class));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        density = getResources().getDisplayMetrics().density;
        createChannel();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if ("hide".equals(action)) {
            clearAndStop();
            return START_NOT_STICKY;
        }
        long hideMs = intent != null
                ? intent.getLongExtra(EXTRA_HIDE_MS, pendingHideMs)
                : pendingHideMs;
        boolean resetTimer = intent == null
                || intent.getBooleanExtra(EXTRA_RESET_TIMER, pendingResetTimer);
        renderRects(new ArrayList<>(pendingRects));
        scheduleAutoHide(hideMs, resetTimer);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (hideRunnable != null) {
            main.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }
        hideDeadlineElapsed = 0;
        removeOverlay();
        super.onDestroy();
    }

    private void clearAndStop() {
        if (hideRunnable != null) {
            main.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }
        hideDeadlineElapsed = 0;
        removeOverlay();
        stopSelf();
    }

    private void renderRects(List<HighlightRect> rects) {
        removeOverlay();
        if (rects.isEmpty()) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        root = BoundsOverlayHelper.createRoot(this);

        for (HighlightRect rect : rects) {
            if (rect == null) continue;
            int w = rect.right - rect.left;
            int h = rect.bottom - rect.top;
            if (w < 8 || h < 8) continue;
            View box = buildHighlight(rect);
            root.addView(box, BoundsOverlayHelper.childAt(
                    rect.left, rect.top, Math.max(dp(24), w), Math.max(dp(24), h)));
        }

        if (root.getChildCount() == 0) {
            removeOverlay();
            stopSelf();
            return;
        }

        try {
            BoundsOverlayHelper.addView(wm, root);
        } catch (Exception e) {
            root = null;
            stopSelf();
        }
    }

    private View buildHighlight(HighlightRect rect) {
        View v = new View(this);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setShape(GradientDrawable.RECTANGLE);
        stroke.setColor(Color.TRANSPARENT);
        stroke.setStroke(dp(2), Color.parseColor("#CC35D0DD"));
        stroke.setCornerRadius(dp(6));
        v.setBackground(stroke);
        v.setContentDescription(rect.label);
        return v;
    }

    /**
     * @param resetTimer true = action ponctuelle (repart de maintenant) ;
     *                   false = mode continu (ne pas repousser la disparition).
     */
    private void scheduleAutoHide(long ms, boolean resetTimer) {
        long now = SystemClock.elapsedRealtime();
        long at = now + Math.max(400L, ms);
        if (!resetTimer && hideRunnable != null && hideDeadlineElapsed > now) {
            // Refresh continu : garder l'échéance déjà planifiée.
            return;
        }
        if (hideRunnable != null) main.removeCallbacks(hideRunnable);
        hideDeadlineElapsed = at;
        long delay = Math.max(0L, at - now);
        hideRunnable = () -> {
            hideRunnable = null;
            hideDeadlineElapsed = 0;
            removeOverlay();
            stopSelf();
        };
        main.postDelayed(hideRunnable, delay);
    }

    private void removeOverlay() {
        BoundsOverlayHelper.removeView(wm, root);
        root = null;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void startAsForeground() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.copilot_fgs_highlight_title))
                .setContentText(getString(R.string.copilot_fgs_highlight_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Surlignage", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
