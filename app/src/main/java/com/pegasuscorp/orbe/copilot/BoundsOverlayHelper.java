package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

/**
 * Boilerplate partagé pour les overlays positionnés (traduction, surlignage).
 */
public final class BoundsOverlayHelper {

    private BoundsOverlayHelper() {}

    public static WindowManager.LayoutParams buildOverlayParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
    }

    public static FrameLayout createRoot(Context ctx) {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        return root;
    }

    public static FrameLayout.LayoutParams childAt(int left, int top, int width, int height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.max(1, width), height > 0 ? height : FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.max(0, left);
        lp.topMargin = Math.max(0, top);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    public static void createChannel(Context ctx, String channelId, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, name, NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void startForeground(Service service, String channelId,
            int notifId, String title, String text, String fgsSubtype) {
        Notification n = new NotificationCompat.Builder(service, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(notifId, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                service.startForeground(notifId, n);
            }
        } catch (Exception ignored) {}
    }

    public static void addView(WindowManager wm, FrameLayout root) {
        if (wm == null || root == null) return;
        wm.addView(root, buildOverlayParams());
    }

    public static void removeView(WindowManager wm, FrameLayout root) {
        if (wm != null && root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
    }
}
