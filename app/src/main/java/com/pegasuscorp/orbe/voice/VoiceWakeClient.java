package com.pegasuscorp.orbe.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * Client launcher → {@link VoiceService} ({@code :voice}).
 * Binding paresseux : pas de bind au boot, seulement au premier besoin.
 * Si le service vit déjà, re-bind ; s'il est mort, startForegroundService + bind.
 */
public final class VoiceWakeClient {

    private static final String TAG = "VoiceWakeClient";

    private static final VoiceWakeClient INSTANCE = new VoiceWakeClient();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Context app;
    private IVoiceWakeService remote;
    private boolean binding;
    private boolean wantListen;
    private boolean keepScoWhilePaused;
    private boolean gentle = true;
    private WakeHealthStatus cachedHealth = WakeHealthStatus.OFF;
    private Runnable pendingAfterBind;

    private final IWakeWordCallback.Stub callback = new IWakeWordCallback.Stub() {
        @Override
        public void onWakeWordDetected(String command) {
            main.post(() -> handleWakeOnLauncher(command));
        }
    };

    private final IWakeHealthCallback.Stub healthCallback = new IWakeHealthCallback.Stub() {
        @Override
        public void onWakeHealthChanged(int code) {
            WakeHealthStatus status = WakeHealthStatus.fromCode(code);
            cachedHealth = status;
            main.post(() -> WakeHealthUi.apply(status));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (lock) {
                binding = false;
                remote = IVoiceWakeService.Stub.asInterface(service);
                try {
                    remote.registerCallback(callback);
                    remote.registerHealthCallback(healthCallback);
                    remote.setGentleMode(gentle);
                    cachedHealth = WakeHealthStatus.fromCode(remote.getWakeHealthCode());
                    main.post(() -> WakeHealthUi.apply(cachedHealth));
                    if (wantListen) {
                        remote.startWakeListening();
                    } else if (keepScoWhilePaused) {
                        remote.pauseWakeListeningKeepSco();
                    } else {
                        remote.stopWakeListening();
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "onServiceConnected", e);
                    remote = null;
                }
                Runnable r = pendingAfterBind;
                pendingAfterBind = null;
                if (r != null) main.post(r);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                remote = null;
                binding = false;
                // Service mort : relance au prochain besoin (wantListen).
                if (wantListen && app != null) {
                    main.post(() -> ensureBound(app, null));
                }
            }
        }
    };

    private VoiceWakeClient() {}

    public static VoiceWakeClient get() {
        return INSTANCE;
    }

    /** Préférence launcher → process voix (pas de SharedPreferences partagé). */
    public void setGentleMode(Context ctx, boolean on) {
        gentle = on;
        MediaPlaybackGuard.setGentle(on);
        ensureApp(ctx);
        IVoiceWakeService r;
        synchronized (lock) {
            r = remote;
        }
        if (r != null) {
            try {
                r.setGentleMode(on);
            } catch (RemoteException ignored) {}
        }
    }

    /**
     * Si wake activé côté prefs launcher : démarre écoute (bind paresseux).
     * Sinon stop + pas de start FGS inutile.
     */
    public void sync(Context ctx) {
        ensureApp(ctx);
        PegaseWakeStore.applyStartupSafety(ctx);
        setGentleMode(ctx, PegaseWakeStore.isGentleMode(ctx));
        if (PegaseWakeStore.isEnabled(ctx) && PegaseWakeController.shouldListen()) {
            startListening(ctx);
        } else {
            stopListening(ctx);
            if (!PegaseWakeStore.isEnabled(ctx)) {
                cachedHealth = WakeHealthStatus.OFF;
                main.post(() -> WakeHealthUi.apply(WakeHealthStatus.OFF));
            }
        }
        refreshWakeHealth();
    }

    public WakeHealthStatus getCachedWakeHealth() {
        return cachedHealth;
    }

    public void refreshWakeHealth() {
        IVoiceWakeService r;
        synchronized (lock) {
            r = remote;
        }
        if (r == null) return;
        try {
            WakeHealthStatus status = WakeHealthStatus.fromCode(r.getWakeHealthCode());
            cachedHealth = status;
            main.post(() -> WakeHealthUi.apply(status));
        } catch (RemoteException e) {
            Log.w(TAG, "refreshWakeHealth", e);
            remoteDied();
        }
    }

    public void resetKwsCrashGuard(Context ctx) {
        ensureApp(ctx);
        ensureBound(ctx, () -> {
            IVoiceWakeService r;
            synchronized (lock) {
                r = remote;
            }
            if (r == null) return;
            try {
                r.resetKwsCrashGuard();
                refreshWakeHealth();
            } catch (RemoteException e) {
                Log.w(TAG, "resetKwsCrashGuard", e);
                remoteDied();
            }
        });
    }

    public void startListening(Context ctx) {
        ensureApp(ctx);
        wantListen = true;
        keepScoWhilePaused = false;
        ensureBound(ctx, () -> {
            IVoiceWakeService r;
            synchronized (lock) {
                r = remote;
            }
            if (r == null) return;
            try {
                r.setGentleMode(gentle);
                r.startWakeListening();
            } catch (RemoteException e) {
                Log.w(TAG, "startListening", e);
                remoteDied();
            }
        });
    }

    public void stopListening(Context ctx) {
        ensureApp(ctx);
        wantListen = false;
        keepScoWhilePaused = false;
        IVoiceWakeService r;
        synchronized (lock) {
            r = remote;
        }
        if (r != null) {
            try {
                r.stopWakeListening();
            } catch (RemoteException ignored) {}
        }
        // Ne pas unbind immédiatement : re-bind rapide au prochain resume HOME.
    }

    /**
     * Pause conversation / handoff STT : arrête le wake sans couper le SCO Bluetooth.
     * Évite le silence micro juste après « Pégase » sur casque.
     */
    public void pauseKeepSco(Context ctx) {
        ensureApp(ctx);
        wantListen = false;
        keepScoWhilePaused = true;
        IVoiceWakeService r;
        synchronized (lock) {
            r = remote;
        }
        if (r != null) {
            try {
                r.pauseWakeListeningKeepSco();
            } catch (RemoteException ignored) {}
        }
    }

    /** Handoff : conversation STT ouverte → coordinator {@code STT_ACTIVE}. */
    public void notifySttSessionStarted(Context ctx) {
        ensureApp(ctx);
        ensureBound(ctx, () -> {
            IVoiceWakeService r;
            synchronized (lock) {
                r = remote;
            }
            if (r == null) return;
            try {
                r.notifySttSessionStarted();
            } catch (RemoteException e) {
                Log.w(TAG, "notifySttSessionStarted", e);
                remoteDied();
            }
        });
    }

    /** Handoff : fin STT → {@code releaseSttSession} (rearm anti-écho côté :voice). */
    public void notifySttSessionEnded(Context ctx) {
        ensureApp(ctx);
        IVoiceWakeService r;
        synchronized (lock) {
            r = remote;
        }
        if (r != null) {
            try {
                r.notifySttSessionEnded();
            } catch (RemoteException e) {
                Log.w(TAG, "notifySttSessionEnded", e);
                remoteDied();
            }
            return;
        }
        // Service pas encore bind : tenter un bind et rejouer.
        ensureBound(ctx, () -> {
            IVoiceWakeService r2;
            synchronized (lock) {
                r2 = remote;
            }
            if (r2 == null) return;
            try {
                r2.notifySttSessionEnded();
            } catch (RemoteException e) {
                Log.w(TAG, "notifySttSessionEnded", e);
                remoteDied();
            }
        });
    }

    private void ensureApp(Context ctx) {
        if (ctx == null) return;
        if (app == null) app = ctx.getApplicationContext();
    }

    private void ensureBound(Context ctx, Runnable after) {
        ensureApp(ctx);
        synchronized (lock) {
            if (after != null) pendingAfterBind = after;
            if (remote != null) {
                Runnable r = pendingAfterBind;
                pendingAfterBind = null;
                if (r != null) main.post(r);
                return;
            }
            if (binding) return;
            binding = true;
        }
        try {
            Intent start = VoiceService.startIntent(app);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(start);
            } else {
                app.startService(start);
            }
            boolean ok = app.bindService(
                    VoiceService.bindIntent(app),
                    connection,
                    Context.BIND_AUTO_CREATE);
            if (!ok) {
                synchronized (lock) {
                    binding = false;
                }
                Log.w(TAG, "bindService failed");
            }
        } catch (Exception e) {
            synchronized (lock) {
                binding = false;
            }
            Log.w(TAG, "ensureBound", e);
        }
    }

    private void remoteDied() {
        synchronized (lock) {
            remote = null;
        }
        if (wantListen && app != null) {
            ensureBound(app, null);
        }
    }

    /** Wake reçu via binder — l'activité est lancée par {@link VoiceService} (FGS). */
    private void handleWakeOnLauncher(String command) {
        wantListen = false;
        Log.i(TAG, "wake relayed to launcher cmd=" + (command == null ? "" : command));
    }
}
