package com.pegasuscorp.orbe.session;

import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.MainActivity;
import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.PegaseWakeService;
import com.pegasuscorp.orbe.PersonalizationStore;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.copilot.CopilotPrefs;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.prefetch.PrefetchService;
import com.pegasuscorp.orbe.ui.OrbUiController;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.PegaseWakeStore;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;

/**
 * Orchestration HOME — phases VISIBLE → STABLE → ARMED (lazy bootstrap PR2).
 */
public final class HomeBootstrapCoordinator {

    public enum Phase { OFF, VISIBLE, STABLE, ARMED }

    public enum AssistantReason {
        ORB_TAP,
        ORB_LONG_PRESS,
        WAKE_INTENT,
        TEXT_DISCUSSION,
        CHAT_ACTIVE_RESUME,
        COPILOT_ALWAYS_ON,
        VOICE_IN_PLACE,
        RESUME_ARMED
    }

    private static final long HOME_STABLE_MS = 2_000L;
    private static final long HOME_STABLE_LOW_MS = 4_000L;
    private static final long HOME_WARMUP_DELAY_MS = 420L;
    private static final long HOME_WARMUP_DELAY_LOW_MS = 1_800L;
    private static final long HOME_WAKE_DELAY_MS = 1_600L;
    private static final long HOME_WAKE_DELAY_LOW_MS = 4_000L;
    private static final long HOME_GESTURE_IDLE_MS = 2_400L;
    private static final long FLUID_ANIM_DELAY_MS = 2_500L;

    private final LifecycleBridge.Host host;
    private final Handler handler;

    private Phase phase = Phase.OFF;
    private boolean lowMemAtResume;

    private Runnable stableRunnable;
    private Runnable lightWarmupRunnable;
    private Runnable stableWakeRunnable;
    private Runnable fluidAnimRunnable;

    public HomeBootstrapCoordinator(LifecycleBridge.Host host, Handler handler) {
        this.host = host;
        this.handler = handler;
    }

    public Phase phase() {
        return phase;
    }

    public void onStart() {
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.resumeAmbient();
        }
    }

    public void onResume() {
        cancelScheduledWork();
        AppCompatActivity activity = host.activity();
        if (activity.isFinishing() || activity.isDestroyed()) return;

        lowMemAtResume = MemoryPressure.isLow(activity);
        host.resetHomeTouch();
        host.clearInk();
        host.startChargingMonitor();
        FloatingOrbService.hide(activity);

        handler.post(() -> {
            if (host.activity().isFinishing() || host.activity().isDestroyed()) return;
            OrbUiController orb = host.orbUi();
            if (orb == null) return;
            if (MemoryPressure.isLow(host.activity())) return;
            orb.reloadWallpaperIfNeeded();
            orb.refreshFluidPhaseOnResume();
        });

        if (requiresImmediateArmed(activity)) {
            host.ensureAssistantWired();
            enterArmed(AssistantReason.RESUME_ARMED);
            resumeChatIfNeeded();
        } else {
            setPhase(Phase.VISIBLE);
        }

        scheduleLightWarmup(lowMemAtResume);
        if (phase != Phase.ARMED) {
            scheduleEnterStable(lowMemAtResume);
        }
    }

    public void onStop() {
        cancelScheduledWork();
        setPhase(Phase.OFF);
    }

    public void noteHomeInteraction() {
        AppCompatActivity activity = host.activity();
        if (activity.isFinishing() || activity.isDestroyed()) return;

        boolean lowMem = MemoryPressure.isLow(activity);
        cancelStableAndWake();

        if (phase == Phase.STABLE) {
            setPhase(Phase.VISIBLE);
        }

        scheduleEnterStableAfterGesture(lowMem);

        if (lightWarmupRunnable != null) {
            handler.removeCallbacks(lightWarmupRunnable);
            lightWarmupRunnable = () -> runLightWarmup(lowMem);
            handler.postDelayed(lightWarmupRunnable, HOME_GESTURE_IDLE_MS);
        }
    }

    public void requestAssistant(AssistantReason reason) {
        AppCompatActivity activity = host.activity();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        enterArmed(reason);
    }

    private void enterArmed(AssistantReason reason) {
        cancelStableAndWake();
        setPhase(Phase.ARMED);
        bindVoiceHost();
    }

    private void bindVoiceHost() {
        host.ensureAssistantWired();
        AppCompatActivity activity = host.activity();
        PegaseSession.get(activity).init(new SessionContext(Channel.VOICE, true));
        if (activity instanceof MainActivity) {
            ChatVoiceBridge.register((MainActivity) activity);
        }
    }

    private boolean requiresImmediateArmed(AppCompatActivity activity) {
        if (PegaseInterfaceState.isOpen()) return true;
        if (ChatSessionRegistry.isActive()) return true;
        if (PegaseWakeController.isVoiceChatActive()) return true;
        if (PegaseWakeController.isInPlaceVoiceActive()) return true;
        if (CopilotPrefs.isAlwaysOn(activity)
                && PegaseWakeController.isVoiceChatActive()) return true;
        VoiceInputHandler voice = host.voiceInput();
        return voice != null && voice.isConversationActive();
    }

    private void scheduleLightWarmup(boolean lowMemAtSchedule) {
        lightWarmupRunnable = () -> runLightWarmup(lowMemAtSchedule);
        long delay = lowMemAtSchedule ? HOME_WARMUP_DELAY_LOW_MS : HOME_WARMUP_DELAY_MS;
        handler.postDelayed(lightWarmupRunnable, delay);
    }

    private void runLightWarmup(boolean lowMemAtSchedule) {
        lightWarmupRunnable = null;
        AppCompatActivity activity = host.activity();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (host.isHomeGestureBusy()) {
            noteHomeInteraction();
            return;
        }
        if (phase == Phase.ARMED) {
            resumeChatIfNeeded();
            return;
        }
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.maybeShowGestureHint();
        }
    }

    private void scheduleEnterStable(boolean lowMem) {
        stableRunnable = () -> onEnterStable(lowMem);
        long delay = lowMem ? HOME_STABLE_LOW_MS : HOME_STABLE_MS;
        handler.postDelayed(stableRunnable, delay);
    }

    private void scheduleEnterStableAfterGesture(boolean lowMem) {
        stableRunnable = () -> onEnterStable(lowMem);
        handler.postDelayed(stableRunnable, HOME_GESTURE_IDLE_MS);
    }

    private void onEnterStable(boolean lowMemAtSchedule) {
        stableRunnable = null;
        AppCompatActivity activity = host.activity();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (phase == Phase.ARMED) return;

        if (host.isHomeGestureBusy()) {
            noteHomeInteraction();
            return;
        }

        setPhase(Phase.STABLE);
        runStableTasks(lowMemAtSchedule);

        if (HomeLoadPolicy.wakeOnHomeStableOnly(activity)) {
            scheduleWakeFromStable(lowMemAtSchedule);
        }
        scheduleFluidAnimIfOptIn();
    }

    private void runStableTasks(boolean lowMemAtSchedule) {
        AppCompatActivity activity = host.activity();
        boolean lowMem = lowMemAtSchedule || MemoryPressure.isLow(activity);
        if (host.isHomeGestureBusy()) {
            noteHomeInteraction();
            return;
        }

        if (!lowMem && !HomeLoadPolicy.skipPrefetchOnHomeResume(activity)) {
            try {
                PrefetchService.run(activity);
            } catch (Exception ignored) {}
        }

        OrbUiController orb = host.orbUi();
        if (orb != null && !lowMem) {
            orb.syncFluidLockWallpaperIfDue();
        }
    }

    private void scheduleWakeFromStable(boolean lowMemAtSchedule) {
        stableWakeRunnable = () -> runWake(lowMemAtSchedule);
        long delay = lowMemAtSchedule ? HOME_WAKE_DELAY_LOW_MS : HOME_WAKE_DELAY_MS;
        handler.postDelayed(stableWakeRunnable, delay);
    }

    private void runWake(boolean lowMemAtSchedule) {
        stableWakeRunnable = null;
        AppCompatActivity activity = host.activity();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (phase == Phase.ARMED) return;

        if (host.isHomeGestureBusy()) {
            noteHomeInteraction();
            return;
        }

        if (lowMemAtSchedule || MemoryPressure.isLow(activity)) {
            PegaseWakeService.sync(activity);
            VoiceWakeClient.get().refreshWakeHealth();
            return;
        }

        VoiceInputHandler voice = host.voiceInput();
        PegaseWakeService.sync(activity);
        VoiceWakeClient.get().refreshWakeHealth();
        if ((voice == null || !voice.isConversationActive())
                && PegaseWakeStore.isEnabled(activity)
                && PegaseWakeController.shouldListen()) {
            VoiceWakeClient.get().startListening(activity);
        }
    }

    private void scheduleFluidAnimIfOptIn() {
        AppCompatActivity activity = host.activity();
        if (!PersonalizationStore.isFluidEnabled(activity)
                || !PersonalizationStore.isFluidAnimated(activity)) {
            return;
        }
        if (MemoryPressure.isLow(activity)) return;

        fluidAnimRunnable = () -> {
            fluidAnimRunnable = null;
            if (host.activity().isFinishing() || host.activity().isDestroyed()) return;
            if (phase != Phase.STABLE && phase != Phase.VISIBLE) return;
            OrbUiController orb = host.orbUi();
            if (orb != null) orb.startFluidIfEnabled();
        };
        handler.postDelayed(fluidAnimRunnable, FLUID_ANIM_DELAY_MS);
    }

    private void resumeChatIfNeeded() {
        VoiceInputHandler voice = host.voiceInput();
        if (voice != null) {
            voice.resumeChatListeningIfNeeded();
        }
    }

    private void setPhase(Phase next) {
        if (phase == next) return;
        phase = next;
        Trace.error("home", "phase " + next.name());
    }

    private void cancelStableAndWake() {
        if (stableRunnable != null) {
            handler.removeCallbacks(stableRunnable);
            stableRunnable = null;
        }
        if (stableWakeRunnable != null) {
            handler.removeCallbacks(stableWakeRunnable);
            stableWakeRunnable = null;
        }
        if (fluidAnimRunnable != null) {
            handler.removeCallbacks(fluidAnimRunnable);
            fluidAnimRunnable = null;
        }
    }

    public void cancelScheduledWork() {
        cancelStableAndWake();
        if (lightWarmupRunnable != null) {
            handler.removeCallbacks(lightWarmupRunnable);
            lightWarmupRunnable = null;
        }
    }
}
