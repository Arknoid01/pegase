package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.pegasuscorp.orbe.PegaseInterfaceActivity;

/**
 * Analyse d'écran continue — processus {@code :copilot}, tuable indépendamment.
 * S'arrête sur SCREEN_OFF ; reprend sur SCREEN_ON si analyse activée.
 */
public class CopilotService extends Service {

    public static final String ACTION_BIND = "com.pegasuscorp.orbe.copilot.BIND";

    private static final String TAG = "CopilotService";
    private static final String CHANNEL_ID = "pegase_copilot";
    private static final int NOTIF_ID = 84;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<ICopilotCallback> callbacks = new RemoteCallbackList<>();

    private CopilotAnalysisEngine engine;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver contentReceiver;
    private volatile boolean analysisActive;
    private volatile boolean screenOn = true;

    private final ICopilotService.Stub binder = new ICopilotService.Stub() {
        @Override
        public void startAnalysis() {
            main.post(() -> activateAnalysis());
        }

        @Override
        public void stopAnalysis() {
            main.post(() -> deactivateAnalysis());
        }

        @Override
        public boolean isScreenOn() {
            return screenOn;
        }

        @Override
        public String getLastScreenText() {
            return ScreenContextStore.getLastText(CopilotService.this);
        }

        @Override
        public void registerCallback(ICopilotCallback callback) {
            if (callback != null) callbacks.register(callback);
        }

        @Override
        public void unregisterCallback(ICopilotCallback callback) {
            if (callback != null) callbacks.unregister(callback);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startAsForeground();
        engine = new CopilotAnalysisEngine(this);
        engine.setCloudSink((pkg, text, reason) -> {
            notifyCloudCandidate(pkg, text, reason);
        });
        registerScreenReceiver();
        registerContentReceiver();
        if (CopilotPrefs.isScreenAnalysisEnabled(this)) {
            activateAnalysis();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // L'analyse est déclenchée par contentReceiver — évite le double appel.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        deactivateAnalysis();
        unregisterReceivers();
        super.onDestroy();
    }

    private void activateAnalysis() {
        analysisActive = true;
        engine.setScreenOn(screenOn);
    }

    private void deactivateAnalysis() {
        analysisActive = false;
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenOn = false;
                    engine.setScreenOn(false);
                    Log.d(TAG, "SCREEN_OFF — analyse suspendue");
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn = true;
                    engine.setScreenOn(true);
                    Log.d(TAG, "SCREEN_ON — analyse reprise");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
    }

    private void registerContentReceiver() {
        contentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!analysisActive || !screenOn) return;
                String pkg = intent != null ? intent.getStringExtra("package") : null;
                if (pkg == null) return;
                engine.onContentChanged(pkg);
                notifyScreenContext(pkg, ScreenContextStore.getLastText(context));
            }
        };
        IntentFilter filter = new IntentFilter(PegaseAccessibilityService.ACTION_CONTENT_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(contentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(contentReceiver, filter);
        }
    }

    private void unregisterReceivers() {
        try {
            if (screenReceiver != null) unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        try {
            if (contentReceiver != null) unregisterReceiver(contentReceiver);
        } catch (Exception ignored) {}
        screenReceiver = null;
        contentReceiver = null;
    }

    private void notifyScreenContext(String pkg, String text) {
        int n = callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                callbacks.getBroadcastItem(i).onScreenContextUpdated(pkg, text);
            } catch (RemoteException ignored) {}
        }
        callbacks.finishBroadcast();
    }

    private void notifyCloudCandidate(String pkg, String text, String reason) {
        int n = callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                callbacks.getBroadcastItem(i).onCloudCandidate(pkg, text, reason);
            } catch (RemoteException ignored) {}
        }
        callbacks.finishBroadcast();
    }

    private void startAsForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pégase copilote")
                .setContentText("Analyse d'écran (apps autorisées)")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, PegaseInterfaceActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "FGS copilote impossible", e);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Copilote", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Analyse d'écran en arrière-plan");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
