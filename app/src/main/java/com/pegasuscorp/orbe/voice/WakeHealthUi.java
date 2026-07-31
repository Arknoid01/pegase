package com.pegasuscorp.orbe.voice;

/**
 * Propage l'état wake honnête vers l'UI HOME (orbe rouge P4).
 */
public final class WakeHealthUi {

    public interface Listener {
        void onWakeHealthChanged(WakeHealthStatus status);
    }

    private static volatile Listener listener;

    private WakeHealthUi() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void apply(WakeHealthStatus status) {
        WakeHealthStatus s = status != null ? status : WakeHealthStatus.OFF;
        PegaseWakeController.setWakeHealthProblem(s.isProblem());
        Listener l = listener;
        if (l != null) l.onWakeHealthChanged(s);
    }
}
