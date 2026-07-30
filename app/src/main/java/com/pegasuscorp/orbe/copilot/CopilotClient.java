package com.pegasuscorp.orbe.copilot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Client launcher → {@link CopilotService} ({@code :copilot}).
 * Même principe que {@link com.pegasuscorp.orbe.voice.VoiceWakeClient}.
 */
public final class CopilotClient {

    private static final String TAG = "CopilotClient";

    private static final CopilotClient INSTANCE = new CopilotClient();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Context app;
    private ICopilotService remote;
    private boolean binding;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (lock) {
                binding = false;
                remote = ICopilotService.Stub.asInterface(service);
                try {
                    if (CopilotPrefs.isScreenAnalysisEnabled(app)) {
                        remote.startAnalysis();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "startAnalysis", e);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                remote = null;
                binding = false;
            }
        }
    };

    private CopilotClient() {}

    public static CopilotClient get() {
        return INSTANCE;
    }

    public void init(Context context) {
        if (app == null && context != null) {
            app = context.getApplicationContext();
        }
    }

    /** Notifie le service :copilot d'un changement de contenu (depuis le process principal). */
    public static void notifyContentChanged(Context ctx, String packageName) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, CopilotService.class);
        i.setAction(PegaseAccessibilityService.ACTION_CONTENT_CHANGED);
        i.putExtra("package", packageName);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "notifyContentChanged", e);
        }
        get().ensureBound(app);
    }

    public void ensureBound(Context context) {
        init(context);
        synchronized (lock) {
            if (remote != null || binding || app == null) return;
            binding = true;
            Intent intent = new Intent(app, CopilotService.class);
            intent.setAction(CopilotService.ACTION_BIND);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent);
                } else {
                    app.startService(intent);
                }
            } catch (Exception e) {
                Log.w(TAG, "startForegroundService", e);
            }
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
    }

    public void sync(Context context) {
        init(context);
        if (!CopilotPrefs.isScreenAnalysisEnabled(app)) return;
        if (!AccessibilityAccess.isEnabled(app)) return;
        ensureBound(app);
    }
}
