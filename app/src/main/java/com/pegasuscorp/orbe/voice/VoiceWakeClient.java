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

import com.pegasuscorp.orbe.MainActivity;

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
    private boolean gentle = true;
    private Runnable pendingAfterBind;

    private final IWakeWordCallback.Stub callback = new IWakeWordCallback.Stub() {
        @Override
        public void onWakeWordDetected(String command) {
            main.post(() -> handleWakeOnLauncher(command));
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
                    remote.setGentleMode(gentle);
                    if (wantListen) {
                        remote.startWakeListening();
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
        }
    }

    public void startListening(Context ctx) {
        ensureApp(ctx);
        wantListen = true;
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

    /** Wake in-place (v3) — overlay vocal sans ramener HOME. */
    private void handleWakeOnLauncher(String command) {
        wantListen = false;
        Context ctx = app;
        if (ctx == null) return;
        Intent i = new Intent(ctx, InPlaceVoiceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra("wake_activate", true)
                .putExtra("wake_command", command == null ? "" : command)
                .putExtra("wake_speaker_verified", false);
        ctx.startActivity(i);
    }
}
