package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.pegasuscorp.orbe.PegaseInterfaceActivity;
import com.pegasuscorp.orbe.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground service typé {@code mediaProjection} — requis Android 14+
 * <b>avant</b> tout {@link MediaProjectionManager#getMediaProjection}.
 *
 * Ordre imposé :
 * 1. consentement système ({@code createScreenCaptureIntent})
 * 2. démarrage de ce service + {@code startForeground(MEDIA_PROJECTION)}
 * 3. création de la {@link MediaProjection}
 */
public final class MediaProjectionCaptureService extends Service {

    private static final String TAG = "MediaProjectionFgs";
    private static final String CHANNEL_ID = "pegase_media_projection";
    private static final int NOTIF_ID = 43;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final Object LOCK = new Object();
    private static final AtomicReference<CountDownLatch> READY =
            new AtomicReference<>();

    private static volatile MediaProjectionCaptureService instance;
    private static volatile MediaProjection projection;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Appelé juste après le consentement système (depuis l'activité). */
    public static void start(Context ctx, int resultCode, Intent resultData) {
        if (ctx == null || resultData == null) return;
        synchronized (LOCK) {
            if (projection != null) return;
        }
        armReadyLatch();
        Intent i = new Intent(ctx, MediaProjectionCaptureService.class);
        i.putExtra(EXTRA_RESULT_CODE, resultCode);
        i.putExtra(EXTRA_RESULT_DATA, resultData);
        try {
            ContextCompat.startForegroundService(ctx.getApplicationContext(), i);
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService failed", e);
            signalReady();
        }
    }

    /**
     * Garantit le FGS + projection. À appeler depuis un thread hors UI.
     * @return projection prête, ou {@code null} si échec / timeout
     */
    public static MediaProjection ensureProjection(Context ctx, int resultCode,
            Intent resultData) {
        if (ctx == null || resultData == null) return null;
        synchronized (LOCK) {
            if (projection != null) return projection;
        }
        CountDownLatch latch = armReadyLatch();
        Intent i = new Intent(ctx, MediaProjectionCaptureService.class);
        i.putExtra(EXTRA_RESULT_CODE, resultCode);
        i.putExtra(EXTRA_RESULT_DATA, resultData);
        try {
            ContextCompat.startForegroundService(ctx.getApplicationContext(), i);
        } catch (Exception e) {
            Log.w(TAG, "ensureProjection start failed", e);
            signalReady();
            return null;
        }
        try {
            if (!latch.await(6, TimeUnit.SECONDS)) {
                Log.w(TAG, "timeout waiting for mediaProjection FGS");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (LOCK) {
            return projection;
        }
    }

    public static MediaProjection getProjection() {
        synchronized (LOCK) {
            return projection;
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getApplicationContext().stopService(
                    new Intent(ctx, MediaProjectionCaptureService.class));
        } catch (Exception ignored) {}
        releaseLocalProjection();
        signalReady();
    }

    private static CountDownLatch armReadyLatch() {
        while (true) {
            CountDownLatch existing = READY.get();
            if (existing != null && existing.getCount() > 0) return existing;
            CountDownLatch created = new CountDownLatch(1);
            if (READY.compareAndSet(existing, created)) return created;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        // startForeground AVANT getMediaProjection — dès l'entrée en service.
        if (!enterForeground()) {
            signalReady();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enterForeground()) {
            signalReady();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = readResultData(intent);
            if (data != null) {
                createProjectionIfNeeded(code, data);
            }
        }
        signalReady();
        if (getProjection() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocalProjection();
        instance = null;
        signalReady();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean enterForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground(mediaProjection) failed", e);
            return false;
        }
    }

    private void createProjectionIfNeeded(int resultCode, Intent resultData) {
        synchronized (LOCK) {
            if (projection != null) return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        try {
            MediaProjection created = mpm.getMediaProjection(resultCode, resultData);
            if (created == null) {
                Log.w(TAG, "getMediaProjection returned null");
                return;
            }
            created.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> {
                        releaseLocalProjection();
                        stopSelf();
                    });
                }
            }, main);
            synchronized (LOCK) {
                projection = created;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "getMediaProjection SecurityException — FGS type / ordre ?", e);
            synchronized (LOCK) {
                projection = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "getMediaProjection failed", e);
            synchronized (LOCK) {
                projection = null;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent readResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private static void releaseLocalProjection() {
        synchronized (LOCK) {
            if (projection != null) {
                try {
                    projection.stop();
                } catch (Exception ignored) {}
                projection = null;
            }
        }
    }

    private static void signalReady() {
        CountDownLatch latch = READY.getAndSet(null);
        if (latch != null) latch.countDown();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Capture d'écran Pégase",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Service requis pour la capture d'écran copilote");
        ch.setSound(null, null);
        ch.enableVibration(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, PegaseInterfaceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title;
        try {
            title = getString(R.string.app_name);
        } catch (Exception e) {
            title = "Pégase";
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("Capture d'écran active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(tap)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
