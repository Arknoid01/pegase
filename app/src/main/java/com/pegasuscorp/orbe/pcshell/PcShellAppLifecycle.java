package com.pegasuscorp.orbe.pcshell;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Verrouille la session Terminal PC quand l'application passe en arrière-plan
 * (pas lors d'une navigation interne Terminal ↔ Réglages).
 * <p>
 * Le verrouillage est différé pour éviter un faux « arrière-plan » pendant les
 * transitions d'activités (ex. voix → Terminal PC avec {@code NEW_TASK}).
 */
public final class PcShellAppLifecycle implements Application.ActivityLifecycleCallbacks {

    private static final long LOCK_DEBOUNCE_MS = 450L;

    private static volatile PcShellAppLifecycle instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int startedCount;

    private final Runnable lockRunnable = () -> {
        if (startedCount <= 0) {
            PcShellSession.lockOnBackground();
            PcShellKeyCache.lock();
        }
    };

    private PcShellAppLifecycle() {}

    public static void install(Application app) {
        if (instance != null) return;
        synchronized (PcShellAppLifecycle.class) {
            if (instance != null) return;
            instance = new PcShellAppLifecycle();
            app.registerActivityLifecycleCallbacks(instance);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        handler.removeCallbacks(lockRunnable);
        startedCount++;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        startedCount--;
        if (startedCount <= 0) {
            startedCount = 0;
            handler.removeCallbacks(lockRunnable);
            handler.postDelayed(lockRunnable, LOCK_DEBOUNCE_MS);
        }
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
    @Override public void onActivityResumed(@NonNull Activity a) {}
    @Override public void onActivityPaused(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {}
}
