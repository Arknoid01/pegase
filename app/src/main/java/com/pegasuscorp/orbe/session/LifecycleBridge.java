package com.pegasuscorp.orbe.session;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.pegasuscorp.orbe.AppListCache;
import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.MainActivity;
import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.PegaseWakeService;
import com.pegasuscorp.orbe.permissions.PermissionFlow;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.llm.LlmEngineManager;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.prefetch.PrefetchService;
import com.pegasuscorp.orbe.ui.OrbUiController;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.PegaseWakeStore;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;
import com.pegasuscorp.orbe.voice.VoiceManager;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;

/**
 * Pont lifecycle Activity ↔ orbe / voix / prefetch / LLM idle unload.
 * Délègue l'orchestration HOME à {@link HomeBootstrapCoordinator} si lazy bootstrap actif.
 */
public final class LifecycleBridge {

    public static final int REQ_MIC = 1001;

    private static final long HOME_WARMUP_DELAY_MS = 420L;
    private static final long HOME_WAKE_DELAY_MS = 1_600L;
    private static final long HOME_WARMUP_DELAY_LOW_MS = 1_800L;
    private static final long HOME_WAKE_DELAY_LOW_MS = 4_000L;
    private static final long HOME_GESTURE_IDLE_MS = 2_400L;

    public interface Host {
        androidx.appcompat.app.AppCompatActivity activity();

        OrbUiController orbUi();

        VoiceInputHandler voiceInput();

        VoiceManager voiceManager();

        void resetHomeTouch();

        void clearInk();

        void attachInkStatusListener();

        void startChargingMonitor();

        void stopChargingMonitor();

        void onMicGrantedEnterChat();

        void onMicGrantedStartListening();

        default boolean isHomeGestureBusy() {
            return false;
        }

        /** Session + voix + bridge — idempotent (PR3 lazy assistant). */
        void ensureAssistantWired();
    }

    private final Host host;
    private final Handler mainHandler;
    private final HomeBootstrapCoordinator bootstrap;
    private final Runnable unloadLlmIdle;
    private Runnable deferredHomeWarmup;
    private Runnable deferredWake;

    public LifecycleBridge(Host host, Handler mainHandler) {
        this.host = host;
        this.mainHandler = mainHandler;
        this.bootstrap = new HomeBootstrapCoordinator(host, mainHandler);
        this.unloadLlmIdle = () -> {
            androidx.appcompat.app.AppCompatActivity a = host.activity();
            if (a.isFinishing() || a.isDestroyed()) return;
            if (PegaseInterfaceState.isOpen() || ChatSessionRegistry.isActive()) return;
            LlmEngineManager.getInstance().unloadIfLoaded();
        };
    }

    public void requestAssistant(HomeBootstrapCoordinator.AssistantReason reason) {
        host.ensureAssistantWired();
        if (useLazyBootstrap()) {
            bootstrap.requestAssistant(reason);
        } else {
            bindVoiceHostLegacy();
        }
    }

    public HomeBootstrapCoordinator.Phase homePhase() {
        return bootstrap.phase();
    }

    private boolean useLazyBootstrap() {
        return HomeLoadPolicy.isLazyBootstrapEnabled(host.activity());
    }

    public void cancelLlmIdleUnload() {
        mainHandler.removeCallbacks(unloadLlmIdle);
    }

    public void scheduleLlmIdleUnload() {
        mainHandler.removeCallbacks(unloadLlmIdle);
        if (!ModelStore.useLocalLlm(host.activity())) return;
        mainHandler.postDelayed(unloadLlmIdle, 3 * 60_000L);
    }

    public boolean ensureMic() {
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (com.pegasuscorp.orbe.voice.VoiceMuteStore.isMuted(a)) {
            android.widget.Toast.makeText(a,
                    "Micro coupé — réactive-le dans le tiroir",
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        if (ActivityCompat.checkSelfPermission(a, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    a, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return false;
        }
        return true;
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_MIC && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            host.ensureAssistantWired();
            VoiceManager vm = host.voiceManager();
            if (vm != null) {
                vm.attachHost(host.activity());
            }
            VoiceInputHandler voice = host.voiceInput();
            if (voice != null && voice.isPendingEnterChatAfterMic()) {
                voice.clearPendingEnterChatAfterMic();
                host.onMicGrantedEnterChat();
            } else {
                host.onMicGrantedStartListening();
            }
        } else if (requestCode == PermissionFlow.REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            try {
                com.pegasuscorp.orbe.intentions.location.LocationSituationBootstrap
                        .ensureStarted(host.activity());
            } catch (Exception ignored) {}
        }
    }

    public void onStart() {
        if (useLazyBootstrap()) {
            bootstrap.onStart();
            return;
        }
        onStartLegacy();
    }

    public void onResume() {
        com.pegasuscorp.orbe.llm.ProfilePauser.resumeIfPausedByUs(host.activity());
        if (useLazyBootstrap()) {
            bootstrap.onResume();
            return;
        }
        onResumeLegacy();
    }

    public void noteHomeInteraction() {
        if (useLazyBootstrap()) {
            bootstrap.noteHomeInteraction();
            return;
        }
        noteHomeInteractionLegacy();
    }

    public void onPause() {
        if (useLazyBootstrap()) {
            bootstrap.cancelScheduledWork();
        } else {
            cancelDeferredHomeWarmup();
        }
        host.resetHomeTouch();
        host.stopChargingMonitor();
        VoiceManager vm = host.voiceManager();
        if (vm != null && ChatVoiceBridge.isBureauActive()) {
            vm.cancelScheduledListening();
            vm.stopListening();
        } else if (ChatVoiceBridge.isInterfaceTakingMic() && vm != null
                && !ChatVoiceBridge.isBureauActive()) {
            vm.stopListening();
        }
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        VoiceInputHandler voice = host.voiceInput();
        if (!android.provider.Settings.canDrawOverlays(a)
                || com.pegasuscorp.orbe.NasaImagePreviewActivity.isShowing()) {
            return;
        }
        final VoiceInputHandler voiceRef = voice;
        mainHandler.postDelayed(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (a.hasWindowFocus()) return;
            if (PegaseInterfaceState.isOpen()) return;
            if (com.pegasuscorp.orbe.NasaImagePreviewActivity.isShowing()) return;
            if (!android.provider.Settings.canDrawOverlays(a)) return;
            if (com.pegasuscorp.orbe.copilot.CopilotPrefs.isAlwaysOn(a)) {
                FloatingOrbService.showCopilot(a);
            } else if (voiceRef != null && voiceRef.isConversationActive()
                    && PegaseWakeController.isVoiceChatActive()) {
                FloatingOrbService.show(a);
            }
        }, 200);
    }

    public void onStop() {
        if (useLazyBootstrap()) {
            bootstrap.onStop();
        }
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.pauseAmbient();
            orb.stopFluid();
        }
        VoiceManager vm = host.voiceManager();
        VoiceInputHandler voice = host.voiceInput();
        if (PegaseInterfaceState.isOpen()) {
            if (vm != null) vm.stopListening();
        } else if (ChatSessionRegistry.isActive()
                || PegaseWakeController.isVoiceChatActive()) {
        } else {
            if (voice != null) voice.finalizeChatSession(false);
            scheduleLlmIdleUnload();
        }
    }

    public void onTrimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            AppListCache.trimIcons();
        }
        OrbUiController orb = host.orbUi();
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (orb != null) {
                orb.stopFluid();
                orb.pauseAmbient();
            }
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            if (orb != null) {
                orb.releaseWallpaperBitmap();
            }
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            if (!PegaseInterfaceState.isOpen() && !ChatSessionRegistry.isActive()) {
                LlmEngineManager.getInstance().unloadIfLoaded();
            }
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            AppListCache.invalidate();
        }
    }

    public void onDestroy() {
        if (useLazyBootstrap()) {
            bootstrap.cancelScheduledWork();
        }
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        VoiceInputHandler voice = host.voiceInput();
        VoiceManager vm = host.voiceManager();
        boolean interfaceOpen = PegaseInterfaceState.isOpen();
        boolean chatActive = ChatSessionRegistry.isActive()
                || (voice != null && voice.isConversationActive());
        boolean interfacePreserve = interfaceOpen && chatActive;
        boolean wakeSessionPreserve = !interfacePreserve
                && (PegaseWakeController.isInPlaceVoiceActive()
                || PegaseWakeController.isVoiceChatActive())
                && chatActive;

        ChatVoiceBridge.unregister((MainActivity) a);
        if (interfacePreserve) {
            ChatVoiceBridge.markSessionPreserved(true);
            if (vm != null) {
                vm.cancelScheduledListening();
                vm.stopListening();
            }
        } else if (wakeSessionPreserve) {
            ChatVoiceBridge.markSessionPreserved(true);
        } else {
            ChatVoiceBridge.markSessionPreserved(false);
            if (voice != null) voice.finalizeChatSession(false);
            ChatVoiceBridge.releaseSharedVoiceIfIdle();
        }
    }

    // —— Legacy (lazy bootstrap OFF) ——

    private void onStartLegacy() {
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.resumeAmbient();
            mainHandler.post(() -> {
                if (host.activity().isFinishing() || host.activity().isDestroyed()) return;
                if (MemoryPressure.isLow(host.activity())) return;
                OrbUiController o = host.orbUi();
                if (o != null) o.startFluidIfEnabled();
            });
        }
    }

    private void onResumeLegacy() {
        cancelDeferredHomeWarmup();
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        final boolean lowMem = MemoryPressure.isLow(a);
        bindVoiceHostLegacy();
        host.resetHomeTouch();
        host.clearInk();
        host.startChargingMonitor();
        FloatingOrbService.hide(a);

        mainHandler.post(() -> {
            if (host.activity().isFinishing() || host.activity().isDestroyed()) return;
            OrbUiController orb = host.orbUi();
            if (orb == null) return;
            if (lowMem || MemoryPressure.isLow(host.activity())) return;
            orb.reloadWallpaperIfNeeded();
            orb.refreshFluidPhaseOnResume();
        });

        long warmupDelay = lowMem ? HOME_WARMUP_DELAY_LOW_MS : HOME_WARMUP_DELAY_MS;
        long wakeDelay = lowMem ? HOME_WAKE_DELAY_LOW_MS : HOME_WAKE_DELAY_MS;
        deferredHomeWarmup = () -> runDeferredHomeWarmupLegacy(lowMem);
        mainHandler.postDelayed(deferredHomeWarmup, warmupDelay);
        deferredWake = () -> runDeferredWakeLegacy(lowMem);
        mainHandler.postDelayed(deferredWake, wakeDelay);
    }

    private void bindVoiceHostLegacy() {
        host.ensureAssistantWired();
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        PegaseSession.get(a).init(new SessionContext(Channel.VOICE, true));
        ChatVoiceBridge.register((MainActivity) a);
    }

    private void runDeferredHomeWarmupLegacy(boolean lowMemAtSchedule) {
        deferredHomeWarmup = null;
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (a.isFinishing() || a.isDestroyed()) return;
        boolean lowMem = lowMemAtSchedule || MemoryPressure.isLow(a);

        if (host.isHomeGestureBusy()) {
            noteHomeInteractionLegacy();
            return;
        }

        VoiceInputHandler voice = host.voiceInput();
        if (voice != null) {
            voice.resumeChatListeningIfNeeded();
        }
        if (!lowMem && !HomeLoadPolicy.skipPrefetchOnHomeResume(a)) {
            try {
                PrefetchService.run(a);
            } catch (Exception ignored) {}
        }
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.maybeShowGestureHint();
            if (!lowMem) {
                orb.syncFluidLockWallpaperIfDue();
            }
        }
    }

    private void runDeferredWakeLegacy(boolean lowMemAtSchedule) {
        deferredWake = null;
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (a.isFinishing() || a.isDestroyed()) return;
        if (host.isHomeGestureBusy()) {
            scheduleWakeAfterGestureIdleLegacy(lowMemAtSchedule);
            return;
        }
        if (lowMemAtSchedule || MemoryPressure.isLow(a)) {
            PegaseWakeService.sync(a);
            VoiceWakeClient.get().refreshWakeHealth();
            return;
        }

        VoiceInputHandler voice = host.voiceInput();
        PegaseWakeService.sync(a);
        VoiceWakeClient.get().refreshWakeHealth();
        if ((voice == null || !voice.isConversationActive())
                && PegaseWakeStore.isEnabled(a)
                && PegaseWakeController.shouldListen()) {
            VoiceWakeClient.get().startListening(a);
        }
    }

    private void noteHomeInteractionLegacy() {
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (a.isFinishing() || a.isDestroyed()) return;
        boolean lowMem = MemoryPressure.isLow(a);
        cancelDeferredWakeOnly();
        scheduleWakeAfterGestureIdleLegacy(lowMem);
        if (deferredHomeWarmup != null) {
            mainHandler.removeCallbacks(deferredHomeWarmup);
            deferredHomeWarmup = () -> runDeferredHomeWarmupLegacy(lowMem);
            mainHandler.postDelayed(deferredHomeWarmup, HOME_GESTURE_IDLE_MS);
        }
    }

    private void scheduleWakeAfterGestureIdleLegacy(boolean lowMem) {
        deferredWake = () -> runDeferredWakeLegacy(lowMem);
        mainHandler.postDelayed(deferredWake, HOME_GESTURE_IDLE_MS);
    }

    private void cancelDeferredWakeOnly() {
        if (deferredWake != null) {
            mainHandler.removeCallbacks(deferredWake);
            deferredWake = null;
        }
    }

    private void cancelDeferredHomeWarmup() {
        if (deferredHomeWarmup != null) {
            mainHandler.removeCallbacks(deferredHomeWarmup);
            deferredHomeWarmup = null;
        }
        cancelDeferredWakeOnly();
    }
}
