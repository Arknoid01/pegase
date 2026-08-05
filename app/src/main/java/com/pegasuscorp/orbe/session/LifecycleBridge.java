package com.pegasuscorp.orbe.session;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.pegasuscorp.orbe.AppListCache;
import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.PegaseWakeService;
import com.pegasuscorp.orbe.permissions.PermissionFlow;
import com.pegasuscorp.orbe.PersonalizationStore;
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
 */
public final class LifecycleBridge {

    public static final int REQ_MIC = 1001;

    private static final long HOME_WARMUP_DELAY_MS = 420L;
    /** Wake STT après le 1er rendu HOME — startListening = hitch micro. */
    private static final long HOME_WAKE_DELAY_MS = 1_600L;
    private static final long HOME_WARMUP_DELAY_LOW_MS = 1_800L;
    private static final long HOME_WAKE_DELAY_LOW_MS = 4_000L;
    /**
     * Après un geste HOME (lettre / tiroir) : attendre le calme avant d'ouvrir le micro
     * pour ne pas couper le dessin ou le scroll du drawer.
     */
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

        /** Trait en cours ou tiroir apps ouvert — reporter le hitch micro. */
        default boolean isHomeGestureBusy() {
            return false;
        }
    }

    private final Host host;
    private final Handler mainHandler;
    private final Runnable unloadLlmIdle;
    private Runnable deferredHomeWarmup;
    private Runnable deferredWake;

    public LifecycleBridge(Host host, Handler mainHandler) {
        this.host = host;
        this.mainHandler = mainHandler;
        this.unloadLlmIdle = () -> {
            androidx.appcompat.app.AppCompatActivity a = host.activity();
            if (a.isFinishing() || a.isDestroyed()) return;
            if (PegaseInterfaceState.isOpen() || ChatSessionRegistry.isActive()) return;
            LlmEngineManager.getInstance().unloadIfLoaded();
        };
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
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.resumeAmbient();
            // Fluid au frame suivant — ne pas concurrencer le 1er geste / lettre.
            // Sous low-memory : ne pas démarrer Fluid (animation = pression continue).
            mainHandler.post(() -> {
                if (host.activity().isFinishing() || host.activity().isDestroyed()) return;
                if (MemoryPressure.isLow(host.activity())) return;
                OrbUiController o = host.orbUi();
                if (o != null) o.startFluidIfEnabled();
            });
        }
    }

    public void onResume() {
        cancelDeferredHomeWarmup();
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        final boolean lowMem = MemoryPressure.isLow(a);
        PegaseSession.get(a).init(new SessionContext(Channel.VOICE, true));
        ChatVoiceBridge.register((com.pegasuscorp.orbe.MainActivity) a);
        host.resetHomeTouch();
        host.clearInk();
        host.attachInkStatusListener();
        host.startChargingMonitor();
        FloatingOrbService.hide(a);

        // Visuels au prochain frame — sous low-mem : pas de re-decode wallpaper / Fluid.
        mainHandler.post(() -> {
            if (host.activity().isFinishing() || host.activity().isDestroyed()) return;
            OrbUiController orb = host.orbUi();
            if (orb == null) return;
            if (lowMem || MemoryPressure.isLow(host.activity())) {
                // Garder ce qui est déjà en RAM ; pas de pic qui tue les apps arrière-plan.
                return;
            }
            orb.reloadWallpaperIfNeeded();
            orb.refreshFluidPhaseOnResume();
        });

        long warmupDelay = lowMem ? HOME_WARMUP_DELAY_LOW_MS : HOME_WARMUP_DELAY_MS;
        long wakeDelay = lowMem ? HOME_WAKE_DELAY_LOW_MS : HOME_WAKE_DELAY_MS;
        deferredHomeWarmup = () -> runDeferredHomeWarmup(lowMem);
        mainHandler.postDelayed(deferredHomeWarmup, warmupDelay);
        deferredWake = () -> runDeferredWake(lowMem);
        mainHandler.postDelayed(deferredWake, wakeDelay);
    }

    private void runDeferredHomeWarmup(boolean lowMemAtSchedule) {
        deferredHomeWarmup = null;
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (a.isFinishing() || a.isDestroyed()) return;
        boolean lowMem = lowMemAtSchedule || MemoryPressure.isLow(a);

        // Geste HOME en cours : ne pas rouvrir le micro / prefetch pendant la lettre.
        if (host.isHomeGestureBusy()) {
            noteHomeInteraction();
            return;
        }

        VoiceInputHandler voice = host.voiceInput();
        if (voice != null) {
            voice.resumeChatListeningIfNeeded();
        }
        // Pas de applyAndroidTtsSettings ici : getVoices() lagge le retour HOME.
        // La voix est déjà appliquée à l'init TTS ; les réglages UI rappellent apply.
        // Prefetch (embeddings ONNX) = gros pic — jamais sous low-mem au retour HOME.
        if (!lowMem) {
            try {
                PrefetchService.run(a);
            } catch (Exception ignored) {}
        }
        OrbUiController orb = host.orbUi();
        if (orb != null) {
            orb.maybeShowGestureHint();
            // Sync lock wallpaper = autre plein bitmap — skip si pression.
            if (!lowMem) {
                orb.syncFluidLockWallpaperIfDue();
            }
        }
    }

    private void runDeferredWake(boolean lowMemAtSchedule) {
        deferredWake = null;
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (a.isFinishing() || a.isDestroyed()) return;
        // Encore en train de dessiner / drawer ouvert → reporter encore.
        if (host.isHomeGestureBusy()) {
            scheduleWakeAfterGestureIdle(lowMemAtSchedule);
            return;
        }
        // STT Google sous low-mem au retour HOME = hitch + RAM — reporter via sync léger seul.
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

    /**
     * Appelé dès qu'un geste HOME commence (trait encre, ouverture tiroir).
     * Annule le démarrage micro imminent et le reporte après calme.
     */
    public void noteHomeInteraction() {
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        if (a.isFinishing() || a.isDestroyed()) return;
        boolean lowMem = MemoryPressure.isLow(a);
        cancelDeferredWakeOnly();
        scheduleWakeAfterGestureIdle(lowMem);
        // Warmup micro chat : même report si encore pending.
        if (deferredHomeWarmup != null) {
            mainHandler.removeCallbacks(deferredHomeWarmup);
            deferredHomeWarmup = () -> runDeferredHomeWarmup(lowMem);
            mainHandler.postDelayed(deferredHomeWarmup, HOME_GESTURE_IDLE_MS);
        }
    }

    private void scheduleWakeAfterGestureIdle(boolean lowMem) {
        deferredWake = () -> runDeferredWake(lowMem);
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

    public void onPause() {
        cancelDeferredHomeWarmup();
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
        // Évite le flash orbe : HOME onPause → show, puis Interface onResume → hide.
        final VoiceInputHandler voiceRef = voice;
        mainHandler.postDelayed(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            // Encore au premier plan (ex. dialogue permission) → pas d'overlay
            if (a.hasWindowFocus()) return;
            if (PegaseInterfaceState.isOpen()) return;
            if (com.pegasuscorp.orbe.NasaImagePreviewActivity.isShowing()) return;
            if (!android.provider.Settings.canDrawOverlays(a)) return;
            // Copilote AlwaysOn prioritaire — sinon open_app (Chrome) + chat actif
            // bascule l'orbe en VOICE et le tap ouvre MainActivity au lieu de la bulle.
            if (com.pegasuscorp.orbe.copilot.CopilotPrefs.isAlwaysOn(a)) {
                FloatingOrbService.showCopilot(a);
            } else if (voiceRef != null && voiceRef.isConversationActive()
                    && PegaseWakeController.isVoiceChatActive()) {
                FloatingOrbService.show(a);
            }
        }, 200);
    }

    public void onStop() {
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
            // Écoute continue : open_app / autre app au premier plan ne doit pas
            // tuer le STT ni annuler la reprise planifiée après TTS.
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
                // Garder le wallpaper en RAM pour un retour HOME sans re-decode (pic LMK).
                orb.stopFluid();
                orb.pauseAmbient();
            }
        }
        // Ne libérer le wallpaper qu'en extrême (COMPLETE) — BACKGROUND trop tôt
        // forçait un gros reload au prochain HOME et tuait les apps arrière-plan.
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
        androidx.appcompat.app.AppCompatActivity a = host.activity();
        VoiceInputHandler voice = host.voiceInput();
        VoiceManager vm = host.voiceManager();
        boolean interfaceOpen = PegaseInterfaceState.isOpen();
        boolean chatActive = ChatSessionRegistry.isActive()
                || (voice != null && voice.isConversationActive());
        // Interface ouverte : garder la session, couper le STT HOME.
        // Wake in-place / voiceChat : garder la session SANS toucher au VoiceManager
        // partagé (sinon cancelScheduledListening/stopListening tue l'ack→STT).
        boolean interfacePreserve = interfaceOpen && chatActive;
        boolean wakeSessionPreserve = !interfacePreserve
                && (PegaseWakeController.isInPlaceVoiceActive()
                || PegaseWakeController.isVoiceChatActive())
                && chatActive;

        ChatVoiceBridge.unregister((com.pegasuscorp.orbe.MainActivity) a);
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
}
