package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Machine d'état unique (process {@code :voice}) pour le cycle wake → STT.
 * Pilotée par {@link VoiceService} ; le launcher signale STT via
 * {@code notifySttSessionStarted}/{@code Ended} (AIDL).
 */
public final class WakeCoordinator {

    private static final String TAG = "WakeCoordinator";

    /**
     * Après {@link #releaseSttSession()} : laisse TTS / buffers audio se vider avant
     * de réarmer le wake (anti-boucle FP). Même valeur et intention que
     * {@link PegaseWakeController#resumeWakeIfAllowed} (commit a7d4532) — constante
     * distincte de {@link KwsCrashGuard#CRASH_WINDOW_MS}.
     */
    public static final long POST_STT_REARM_DELAY_MS = 8_000L;

    public enum WakeState {
        IDLE,
        LISTENING_WAKE,
        HANDING_OFF,
        STT_ACTIVE,
        COOLDOWN
    }

    /** Backend KWS figé pour une session LISTENING_WAKE. */
    public enum WakeBackend {
        SHERPA,
        OWW,
        NONE
    }

    /** Lecture synchrone de la source (impl. prod = {@link AudioRouteObserver}). */
    public interface AudioSourceReadable {
        AudioRouteObserver.AudioSource currentSource();
    }

    public static final class Snapshot {
        public final WakeState state;
        public final AudioRouteObserver.AudioSource source;
        public final WakeBackend backend;

        public Snapshot(WakeState state,
                        AudioRouteObserver.AudioSource source,
                        WakeBackend backend) {
            this.state = state;
            this.source = source;
            this.backend = backend;
        }
    }

    /** Sélection backend (injectable pour tests). */
    public interface BackendSelector {
        WakeBackend select(AudioRouteObserver.AudioSource source);
    }

    /** Préparation / relâche SCO (injectable ; défaut = {@link KwsAudioRouteManager}). */
    public interface ScoGateway {
        void prepareAsync(AudioRouteObserver.AudioSource source, Consumer<Boolean> onReady);
        void release();
    }

    /** Planification délais (injectable). */
    public interface Scheduler {
        void postDelayed(Runnable r, long delayMs);
        void removeCallbacks(Runnable r);
    }

    /** Notifié après chaque transition (process {@code :voice} / VoiceService). */
    public interface Listener {
        void onWakeCoordinatorState(Snapshot snapshot);
    }

    private final AudioSourceReadable routeObserver;
    private final BackendSelector backendSelector;
    private final ScoGateway scoGateway;
    private final Scheduler scheduler;
    /** {@link KwsCrashGuard#CRASH_WINDOW_MS} — reprise après crash-loop. */
    private final long crashCooldownMs;
    /** {@link #POST_STT_REARM_DELAY_MS} — anti-écho après session STT normale. */
    private final long postSttRearmDelayMs;

    private WakeState state = WakeState.IDLE;
    private AudioRouteObserver.AudioSource sessionSource =
            AudioRouteObserver.AudioSource.PHONE_BUILTIN;
    private WakeBackend sessionBackend = WakeBackend.NONE;
    private boolean scoHeldForStt;
    /** True entre {@link #releaseSttSession()} et le retour effectif en LISTENING_WAKE. */
    private boolean postSttRearmPending;
    private Listener listener;
    private final Runnable leaveCooldown = this::leaveCooldown;
    private final Runnable leavePostSttRearm = this::leavePostSttRearm;

    public WakeCoordinator(Context context, AudioRouteObserver routeObserver) {
        this(routeObserver,
                defaultBackendSelector(context.getApplicationContext()),
                defaultScoGateway(context.getApplicationContext()),
                defaultScheduler(),
                KwsCrashGuard.CRASH_WINDOW_MS,
                POST_STT_REARM_DELAY_MS);
    }

    /** Constructeur tests / injection. */
    public WakeCoordinator(AudioSourceReadable routeObserver,
                           BackendSelector backendSelector,
                           ScoGateway scoGateway,
                           Scheduler scheduler,
                           long crashCooldownMs,
                           long postSttRearmDelayMs) {
        this.routeObserver = Objects.requireNonNull(routeObserver, "routeObserver");
        this.backendSelector = Objects.requireNonNull(backendSelector, "backendSelector");
        this.scoGateway = Objects.requireNonNull(scoGateway, "scoGateway");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.crashCooldownMs = Math.max(0L, crashCooldownMs);
        this.postSttRearmDelayMs = Math.max(0L, postSttRearmDelayMs);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * IDLE ou HANDING_OFF → LISTENING_WAKE. Lit la source une fois, choisit le backend
     * une fois (figé tant qu'on reste en LISTENING_WAKE / cycle wake).
     * Idempotent si déjà LISTENING_WAKE.
     */
    public synchronized boolean start() {
        if (state == WakeState.LISTENING_WAKE) {
            return true;
        }
        if (state == WakeState.COOLDOWN || state == WakeState.STT_ACTIVE) {
            Log.d(TAG, "start ignored from " + state);
            return false;
        }
        // IDLE ou HANDING_OFF
        cancelPendingTimers();
        sessionSource = routeObserver.currentSource();
        sessionBackend = backendSelector.select(sessionSource);
        state = WakeState.LISTENING_WAKE;
        Log.i(TAG, "→ LISTENING_WAKE source=" + sessionSource + " backend=" + sessionBackend);
        notifyListenerLocked();
        return true;
    }

    /** Arrêt explicite (binder stop / destroy) → IDLE. */
    public synchronized void stop() {
        cancelPendingTimers();
        if (scoHeldForStt) {
            scoGateway.release();
            scoHeldForStt = false;
        }
        if (state == WakeState.IDLE && sessionBackend == WakeBackend.NONE) return;
        state = WakeState.IDLE;
        sessionBackend = WakeBackend.NONE;
        Log.i(TAG, "→ IDLE (stop)");
        notifyListenerLocked();
    }

    /** True uniquement en {@link WakeState#LISTENING_WAKE} (moteur wake autorisé). */
    public synchronized boolean wantsListening() {
        return state == WakeState.LISTENING_WAKE;
    }

    /** LISTENING_WAKE → HANDING_OFF. */
    public synchronized boolean onWakeDetected() {
        if (state != WakeState.LISTENING_WAKE) {
            Log.d(TAG, "onWakeDetected ignored from " + state);
            return false;
        }
        state = WakeState.HANDING_OFF;
        Log.i(TAG, "→ HANDING_OFF");
        notifyListenerLocked();
        return true;
    }

    /**
     * HANDING_OFF ou LISTENING_WAKE → STT_ACTIVE une fois la route prête.
     * Déjà {@link WakeState#STT_ACTIVE} : idempotent (callback ok=true) — couvre les
     * 2ᵉ tours de conversation qui rouvrent le STT sans nouveau wake.
     * Pendant rearm post-STT : annule le timer et reste en STT_ACTIVE (chat encore actif).
     * IDLE / COOLDOWN : no-op (callback non appelé).
     */
    public void requestSttSession(Consumer<Boolean> callback) {
        final Consumer<Boolean> cb = callback;
        boolean prepareRoute;
        boolean alreadyActive;
        AudioRouteObserver.AudioSource source;
        synchronized (this) {
            source = sessionSource;
            alreadyActive = false;
            prepareRoute = false;

            if (state == WakeState.STT_ACTIVE) {
                if (postSttRearmPending) {
                    // Chat rouvre le micro pendant le rearm anti-écho : rester STT.
                    scheduler.removeCallbacks(leavePostSttRearm);
                    postSttRearmPending = false;
                    Log.i(TAG, "requestSttSession: cancel post-STT rearm, stay STT_ACTIVE");
                } else {
                    Log.d(TAG, "requestSttSession idempotent (already STT_ACTIVE)");
                }
                alreadyActive = true;
            } else if (state == WakeState.HANDING_OFF || state == WakeState.LISTENING_WAKE) {
                if (state == WakeState.LISTENING_WAKE) {
                    state = WakeState.HANDING_OFF;
                }
                prepareRoute = true;
            } else {
                Log.d(TAG, "requestSttSession ignored from " + state
                        + " rearmPending=" + postSttRearmPending);
            }
        }
        if (alreadyActive) {
            if (cb != null) cb.accept(true);
            return;
        }
        if (!prepareRoute) return;

        scoGateway.prepareAsync(source, ok -> {
            synchronized (WakeCoordinator.this) {
                if (state != WakeState.HANDING_OFF) {
                    Log.w(TAG, "SCO ready but state=" + state + " — drop");
                    scoGateway.release();
                    scoHeldForStt = false;
                    return;
                }
                scoHeldForStt = source == AudioRouteObserver.AudioSource.BLUETOOTH_HFP && ok;
                state = WakeState.STT_ACTIVE;
                Log.i(TAG, "→ STT_ACTIVE scoHeld=" + scoHeldForStt + " ok=" + ok);
                notifyListenerLocked();
            }
            if (cb != null) cb.accept(ok);
        });
    }

    /**
     * Fin de session STT normale : relâche SCO tout de suite, puis
     * STT_ACTIVE → LISTENING_WAKE après {@link #POST_STT_REARM_DELAY_MS} (anti-écho).
     * Pendant le délai, l'état reste {@link WakeState#STT_ACTIVE} avec rearm pending
     * (pas de nouveau STT / pas de wake).
     */
    public synchronized boolean releaseSttSession() {
        if (state != WakeState.STT_ACTIVE || postSttRearmPending) {
            Log.d(TAG, "releaseSttSession ignored from " + state
                    + " rearmPending=" + postSttRearmPending);
            return false;
        }
        if (scoHeldForStt) {
            scoGateway.release();
            scoHeldForStt = false;
        }
        postSttRearmPending = true;
        scheduler.removeCallbacks(leavePostSttRearm);
        Log.i(TAG, "STT released — LISTENING_WAKE in " + postSttRearmDelayMs + " ms (anti-echo)");
        scheduler.postDelayed(leavePostSttRearm, postSttRearmDelayMs);
        return true;
    }

    /**
     * N'importe quel état → COOLDOWN, puis LISTENING_WAKE après
     * {@link KwsCrashGuard#CRASH_WINDOW_MS} (fenêtre mort rapide / crash-loop).
     */
    public synchronized void onCrashGuardTripped() {
        cancelPendingTimers();
        if (scoHeldForStt) {
            scoGateway.release();
            scoHeldForStt = false;
        }
        state = WakeState.COOLDOWN;
        Log.w(TAG, "→ COOLDOWN crash-loop (" + crashCooldownMs + " ms)");
        notifyListenerLocked();
        scheduler.postDelayed(leaveCooldown, crashCooldownMs);
    }

    public synchronized Snapshot getState() {
        return new Snapshot(state, sessionSource, sessionBackend);
    }

    /** Backend figé pour la session wake courante (NONE si IDLE). */
    public synchronized WakeBackend getSessionBackend() {
        return sessionBackend;
    }

    /** Exposé tests / diag : délai anti-écho en cours après release STT. */
    public synchronized boolean isPostSttRearmPending() {
        return postSttRearmPending;
    }

    private synchronized void leaveCooldown() {
        if (state != WakeState.COOLDOWN) return;
        sessionSource = routeObserver.currentSource();
        sessionBackend = backendSelector.select(sessionSource);
        state = WakeState.LISTENING_WAKE;
        Log.i(TAG, "COOLDOWN → LISTENING_WAKE source=" + sessionSource
                + " backend=" + sessionBackend);
        notifyListenerLocked();
    }

    private synchronized void leavePostSttRearm() {
        if (!postSttRearmPending || state != WakeState.STT_ACTIVE) return;
        postSttRearmPending = false;
        state = WakeState.LISTENING_WAKE;
        Log.i(TAG, "→ LISTENING_WAKE (after post-STT rearm)");
        notifyListenerLocked();
    }

    private void notifyListenerLocked() {
        Listener l = listener;
        if (l == null) return;
        Snapshot snap = new Snapshot(state, sessionSource, sessionBackend);
        l.onWakeCoordinatorState(snap);
    }

    private void cancelPendingTimers() {
        postSttRearmPending = false;
        scheduler.removeCallbacks(leaveCooldown);
        scheduler.removeCallbacks(leavePostSttRearm);
    }

    static BackendSelector defaultBackendSelector(Context app) {
        return source -> {
            boolean preferOww = WakeOwwStore.preferCustomWake(app) && WakeOwwStore.isModelReady(app);
            boolean sherpaReady = KwsModelStore.isModelReady(app);
            // Sur HFP, OWW custom souvent sourd → Sherpa prioritaire (même règle VoiceService).
            if (preferOww && source == AudioRouteObserver.AudioSource.BLUETOOTH_HFP && sherpaReady) {
                preferOww = false;
            }
            if (preferOww) return WakeBackend.OWW;
            if (sherpaReady) return WakeBackend.SHERPA;
            return WakeBackend.NONE;
        };
    }

    static ScoGateway defaultScoGateway(Context app) {
        return new ScoGateway() {
            @Override
            public void prepareAsync(AudioRouteObserver.AudioSource source,
                                     Consumer<Boolean> onReady) {
                if (source != AudioRouteObserver.AudioSource.BLUETOOTH_HFP) {
                    if (onReady != null) onReady.accept(true);
                    return;
                }
                KwsAudioRouteManager.getInstance(app)
                        .ensureBluetoothScoActiveAsync(ok -> {
                            if (onReady != null) onReady.accept(ok != null && ok);
                        });
            }

            @Override
            public void release() {
                KwsAudioRouteManager.getInstance(app).releaseBluetoothSco();
            }
        };
    }

    static Scheduler defaultScheduler() {
        Handler main = new Handler(Looper.getMainLooper());
        return new Scheduler() {
            @Override
            public void postDelayed(Runnable r, long delayMs) {
                main.postDelayed(r, delayMs);
            }

            @Override
            public void removeCallbacks(Runnable r) {
                main.removeCallbacks(r);
            }
        };
    }
}
