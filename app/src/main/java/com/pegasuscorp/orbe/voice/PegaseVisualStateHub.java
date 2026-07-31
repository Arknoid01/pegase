package com.pegasuscorp.orbe.voice;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Relais unique vers HOME, overlay et Discussion — dérivé de {@link PegaseWakeController}.
 */
public final class PegaseVisualStateHub {

    public interface Listener {
        void onPhaseChanged(PegaseVisualPhase phase);
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile PegaseVisualPhase current = PegaseVisualPhase.IDLE;

    private PegaseVisualStateHub() {}

    public static PegaseVisualPhase currentPhase() {
        return current;
    }

    public static PegaseVisualPhase derivePhase() {
        if (PegaseWakeController.isAssistantThinking()) return PegaseVisualPhase.THINKING;
        if (PegaseWakeController.isMicListening()) return PegaseVisualPhase.MIC_LISTENING;
        return PegaseVisualPhase.IDLE;
    }

    public static void refresh() {
        final PegaseVisualPhase next;
        synchronized (LOCK) {
            next = derivePhase();
            if (next == current) return;
            current = next;
        }
        MAIN.post(() -> dispatchPhase(next));
    }

    private static void dispatchPhase(PegaseVisualPhase phase) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onPhaseChanged(phase);
            } catch (Exception ignored) {
            }
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        LISTENERS.add(listener);
        listener.onPhaseChanged(current);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    /** Tests uniquement. */
    static void resetForTests() {
        synchronized (LOCK) {
            LISTENERS.clear();
            current = PegaseVisualPhase.IDLE;
        }
        PegaseWakeController.setMicListening(false);
        PegaseWakeController.setAssistantThinking(false);
    }
}
