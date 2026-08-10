package com.pegasuscorp.orbe.pcshell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alarme « Plus tard » — republie la notification ntfy Arch.
 */
public final class PcShellNtfySnoozeReceiver extends BroadcastReceiver {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pc-shell-ntfy-snooze");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        PcShellNtfySnoozeScheduler.SnoozePayload payload = PcShellNtfySnoozeScheduler.loadPayload(app);
        if (payload == null) return;
        IO.execute(() -> {
            try {
                PcShellNtfyRepublisher.publish(payload);
            } catch (Exception ignored) {
            } finally {
                PcShellNtfySnoozeScheduler.clearPayload(app);
            }
        });
    }
}
