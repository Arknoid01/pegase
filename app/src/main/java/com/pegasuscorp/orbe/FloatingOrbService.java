package com.pegasuscorp.orbe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

/**
 * Mini-orbe flottante draggable, visible par-dessus toutes les apps.
 *
 * Comportement :
 *  - Tap court  → revient à MainActivity et relance le micro (resume_chat).
 *  - Drag       → déplace l'orbe n'importe où sur l'écran.
 *  - Visible quand une discussion Pégase est en cours et qu'une autre app prend le dessus.
 *  - Masquée au retour sur MainActivity ou PegaseInterfaceActivity.
 *
 * Prérequis : permission SYSTEM_ALERT_WINDOW (Paramètres → Orbe → Afficher par-dessus).
 */
public class FloatingOrbService extends Service {

    private static final String CHANNEL_ID = "pegase_overlay";
    private static final int NOTIF_ID = 42;

    private static volatile boolean running;

    private WindowManager wm;
    private View orbView;
    private boolean foregroundStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        if (!startAsForeground()) {
            stopSelf();
            return;
        }
        showFloatingOrb();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (orbView == null) showFloatingOrb();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        removeOrb();
        foregroundStarted = false;
        running = false;
        super.onDestroy();
    }

    /** @return false si le service de premier plan n'a pas pu démarrer (évite un crash en boucle). */
    private boolean startAsForeground() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            foregroundStarted = true;
            return true;
        } catch (SecurityException e) {
            android.util.Log.e("FloatingOrb",
                    "Permission FGS manquante — orbe flottante désactivée", e);
        } catch (RuntimeException e) {
            android.util.Log.e("FloatingOrb", "Impossible de démarrer en premier plan", e);
        }
        foregroundStarted = false;
        running = false;
        return false;
    }

    private void showFloatingOrb() {
        if (orbView != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        orbView = new MiniOrbView(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                160, 160,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 300;

        orbView.setOnTouchListener(new DragAndTapListener(wm, params, () -> {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("resume_chat", true);
            startActivity(i);
        }));

        try {
            wm.addView(orbView, params);
        } catch (Exception e) {
            android.util.Log.e("FloatingOrb", "Overlay impossible", e);
            orbView = null;
            stopSelf();
        }
    }

    private void removeOrb() {
        if (wm != null && orbView != null) {
            try { wm.removeView(orbView); } catch (Exception ignored) {}
            orbView = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pégase actif", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Orbe flottante pendant la discussion");
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("resume_chat", true);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pégase")
                .setContentText("Discussion en cours — tap l'orbe pour revenir")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tap)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    public static void show(Context ctx) {
        if (running) return;
        if (NasaImagePreviewActivity.isShowing()) return;
        try {
            Intent i = new Intent(ctx, FloatingOrbService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            android.util.Log.w("FloatingOrb", "Démarrage orbe flottante impossible", e);
        }
    }

    public static void hide(Context ctx) {
        if (!running) return;
        ctx.stopService(new Intent(ctx, FloatingOrbService.class));
    }

    private static class MiniOrbView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MiniOrbView(Context ctx) {
            super(ctx);
            setBackgroundColor(Color.TRANSPARENT);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.42f;
            paint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{Color.parseColor("#D0FFFE"),
                              Color.parseColor("#35D0DD"),
                              Color.parseColor("#0B7D8F")},
                    new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
            glowPaint.setColor(Color.parseColor("#3035D0DD"));
            glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                    r * 0.35f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.42f;
            c.drawCircle(cx, cy, r * 1.3f, glowPaint);
            c.drawCircle(cx, cy, r, paint);
        }
    }

    private static class DragAndTapListener implements View.OnTouchListener {

        private final WindowManager wm;
        private final WindowManager.LayoutParams params;
        private final Runnable onTap;

        private float startX, startY;
        private int startParamX, startParamY;
        private boolean dragged;

        DragAndTapListener(WindowManager wm, WindowManager.LayoutParams p, Runnable onTap) {
            this.wm = wm; this.params = p; this.onTap = onTap;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startParamX = params.x; startParamY = params.y;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - startX, dy = e.getRawY() - startY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
                    if (dragged) {
                        params.x = startParamX + (int) dx;
                        params.y = startParamY + (int) dy;
                        wm.updateViewLayout(v, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) onTap.run();
                    return true;
            }
            return false;
        }
    }
}
