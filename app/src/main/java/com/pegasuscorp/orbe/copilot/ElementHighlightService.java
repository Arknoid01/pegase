package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Surlignage des éléments interactifs via les bounds a11y (100% local).
 */
public class ElementHighlightService extends Service {

    private static final String CHANNEL_ID = "pegase_element_highlight";
    private static final int NOTIF_ID = 87;
    private static final long AUTO_HIDE_MS = 8_000L;

    private static volatile List<HighlightRect> pendingRects = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout root;
    private Runnable hideRunnable;
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
        pendingRects = new ArrayList<>(rects);
        Intent i = new Intent(ctx, ElementHighlightService.class);
        i.setAction("show");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {}
    }

    public static void hide(Context ctx) {
        pendingRects = new ArrayList<>();
        ctx.stopService(new Intent(ctx, ElementHighlightService.class));
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
        renderRects(new ArrayList<>(pendingRects));
        scheduleAutoHide();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        removeOverlay();
        if (hideRunnable != null) main.removeCallbacks(hideRunnable);
        super.onDestroy();
    }

    private void renderRects(List<HighlightRect> rects) {
        removeOverlay();
        if (rects.isEmpty()) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        for (HighlightRect rect : rects) {
            if (rect == null) continue;
            int w = rect.right - rect.left;
            int h = rect.bottom - rect.top;
            if (w < 8 || h < 8) continue;
            View box = buildHighlight(rect);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(dp(24), w), Math.max(dp(24), h));
            lp.leftMargin = Math.max(0, rect.left);
            lp.topMargin = Math.max(0, rect.top);
            lp.gravity = Gravity.TOP | Gravity.START;
            root.addView(box, lp);
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        try {
            wm.addView(root, params);
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

    private void scheduleAutoHide() {
        if (hideRunnable != null) main.removeCallbacks(hideRunnable);
        hideRunnable = () -> {
            hideRunnable = null;
            stopSelf();
        };
        main.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void removeOverlay() {
        if (wm != null && root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
            root = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void startAsForeground() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Surlignage copilote")
                .setContentText("Éléments interactifs")
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
