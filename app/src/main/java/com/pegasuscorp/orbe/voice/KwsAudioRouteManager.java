package com.pegasuscorp.orbe.voice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prépare la route micro — téléphone ou Bluetooth SCO / communication device.
 * <p>
 * Une instance par process. Le SCO conversationnel appartient au launcher
 * ({@link VoiceManager}) ; le process {@code :voice} force le micro téléphone
 * pour le wake et ne tient plus de hold SCO.
 */
public final class KwsAudioRouteManager {

    private static final String TAG = "KwsAudioRoute";
    private static final long SCO_WAIT_MS = 6_000L;
    private static final long ROUTE_NOTIFY_DEBOUNCE_MS = 300L;
    private static final long SCO_RETRY_PAUSE_MS = 800L;

    private static final Object LOCK = new Object();
    private static KwsAudioRouteManager shared;

    public interface RouteChangeListener {
        void onAudioRouteChanged();
    }

    public enum RouteKind {
        PHONE_BUILTIN,
        BLUETOOTH_SCO,
        WIRED_HEADSET,
        USB,
        OTHER,
        UNKNOWN
    }

    private final Context app;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Sérialise prepare/release — VoiceManager (async) et callbacks route partagent le SCO. */
    private final Object scoLock = new Object();
    private final AtomicInteger scoHoldCount = new AtomicInteger(0);

    private volatile RouteChangeListener routeChangeListener;
    private volatile RouteKind activeKind = RouteKind.UNKNOWN;
    private volatile AudioDeviceInfo preferredInput;
    private volatile boolean scoPrepared;
    /** True après échec SCO : bloque refreshRouteKind pour garder le micro téléphone. */
    private volatile boolean phoneForced;
    private AudioFocusRequest scoFocusRequest;
    private BluetoothHeadset bluetoothHeadset;
    private volatile boolean headsetProxyRequesting;
    private BluetoothDevice voiceRecognitionDevice;
    private volatile boolean voiceRecognitionActive;
    private final Object headsetProxyLock = new Object();
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);

    private AudioDeviceCallback deviceCallback;
    private BroadcastReceiver scoReceiver;
    private Runnable pendingRouteNotify;
    /** Dernière tentative SCO — pour diag sco_service_start. */
    private volatile String lastScoFailReason = "";
    private final StringBuilder scoPhaseLog = new StringBuilder();
    private final StringBuilder scoStateLog = new StringBuilder();
    private long lastScoAttemptAtMs = 0L;

    public static KwsAudioRouteManager getInstance(Context context) {
        synchronized (LOCK) {
            if (shared == null) {
                shared = new KwsAudioRouteManager(context.getApplicationContext());
            }
            return shared;
        }
    }

    public KwsAudioRouteManager(Context context) {
        app = context.getApplicationContext();
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        registerObservers();
        ensureHeadsetProxy();
        refreshRouteKind();
    }

    public void setRouteChangeListener(RouteChangeListener listener) {
        routeChangeListener = listener;
    }

    public RouteKind getActiveKind() {
        return activeKind;
    }

    public AudioDeviceInfo getPreferredInput() {
        return preferredInput;
    }

    /** True si un casque / kit BT (A2DP ou SCO) est connecté — on tentera le micro SCO. */
    public boolean wantsBluetoothMic() {
        refreshRouteKind();
        return activeKind == RouteKind.BLUETOOTH_SCO;
    }

    /** SCO réellement prêt (hold + entrée live / audio headset). */
    public boolean isScoLive() {
        synchronized (scoLock) {
            if (!scoPrepared) return false;
            return hasLiveBluetoothScoInput() || isHeadsetAudioConnected();
        }
    }

    /**
     * Attend que le micro SCO soit live (après {@link #ensureBluetoothScoActive}).
     * Callback sur le main thread ; {@code false} si timeout.
     */
    public void awaitScoReadyAsync(long timeoutMs, java.util.function.Consumer<Boolean> onReady) {
        io.execute(() -> {
            long deadline = android.os.SystemClock.elapsedRealtime()
                    + Math.max(0L, timeoutMs);
            boolean ready = false;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (isScoLive()) {
                    ready = true;
                    break;
                }
                sleepQuiet(40);
            }
            final boolean result = ready;
            if (onReady != null) main.post(() -> onReady.accept(result));
        });
    }

    public int getAudioSource() {
        switch (activeKind) {
            case BLUETOOTH_SCO:
                // Surtout pas VOICE_COMMUNICATION ici : cette source passe par la chaîne
                // AEC/NS/AGC du HAL, qui attend un signal de référence de lecture. Pendant
                // l'écoute du mot d'éveil rien ne joue — l'AEC sortait alors des buffers de
                // zéros par salves (mesuré : 46 % du signal perdu, trous de 5 à 492 ms).
                // VOICE_RECOGNITION est la source prévue pour la reconnaissance : pas de
                // pré-traitement, et c'est celle qu'utilise SpeechRecognizer lui-même.
                // Le routage vers le casque reste assuré par setPreferredDevice + le hold SCO.
                return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case WIRED_HEADSET:
                return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            default:
                return MediaRecorder.AudioSource.MIC;
        }
    }

    public void forcePhoneBuiltin() {
        phoneForced = true;
        activeKind = RouteKind.PHONE_BUILTIN;
        preferredInput = findBuiltinMic();
        scoPrepared = false;
        Log.i(TAG, "forced phone builtin " + describeRouteLocked());
    }

    /**
     * Point d'entrée unique STT + wake : active le SCO si un casque BT est connecté.
     * API 31+ {@code setCommunicationDevice}, sinon {@code startBluetoothSco} (+ HFP VR).
     * Sans casque : retour immédiat {@code true} (micro local inchangé).
     * Bloquant — hors UI thread. Ref-count : chaque succès incrémente un hold pour que
     * {@link #releaseBluetoothSco()} ne coupe jamais le SCO d'un autre client.
     */
    public boolean ensureBluetoothScoActive() {
        synchronized (scoLock) {
            phoneForced = false;
            refreshRouteKind();
            logRoute("ensureBluetoothScoActive");
            int holds = scoHoldCount.incrementAndGet();
            if (activeKind != RouteKind.BLUETOOTH_SCO) {
                return true;
            }
            if (holds > 1 && scoPrepared) {
                Log.i(TAG, "SCO already held count=" + holds);
                return true;
            }
            boolean ok = establishScoWithFallbacksLocked();
            if (!ok) {
                scoHoldCount.decrementAndGet();
                abandonScoAudioFocus();
                Log.w(TAG, "ensureBluetoothScoActive failed — caller peut fallback téléphone");
            }
            return ok;
        }
    }

    /** @deprecated préfère {@link #ensureBluetoothScoActive()} — alias conservé pour STT. */
    public boolean prepareCapture() {
        return ensureBluetoothScoActive();
    }

    /**
     * Prépare la route en arrière-plan puis appelle {@code onReady} sur le main thread.
     */
    public void ensureBluetoothScoActiveAsync(java.util.function.Consumer<Boolean> onReady) {
        io.execute(() -> {
            boolean ok = true;
            try {
                ok = ensureBluetoothScoActive();
            } catch (Exception e) {
                Log.w(TAG, "ensureBluetoothScoActiveAsync", e);
                ok = false;
            }
            final boolean result = ok;
            if (onReady != null) main.post(() -> onReady.accept(result));
        });
    }

    /** @deprecated préfère {@link #ensureBluetoothScoActiveAsync}. */
    public void prepareCaptureAsync(java.util.function.Consumer<Boolean> onReady) {
        ensureBluetoothScoActiveAsync(onReady);
    }

    /** Dernière raison d'échec SCO (diag / fallback). */
    public String lastScoFailReason() {
        return lastScoFailReason == null ? "" : lastScoFailReason;
    }

    /** Libère un hold ; SCO réellement coupé quand le compteur tombe à 0. */
    public void releaseBluetoothSco() {
        synchronized (scoLock) {
            int left = scoHoldCount.decrementAndGet();
            if (left > 0) {
                Log.i(TAG, "SCO hold remaining=" + left);
                return;
            }
            if (left < 0) {
                scoHoldCount.set(0);
                return;
            }
            releaseCaptureInternal();
        }
    }

    /** @deprecated préfère {@link #releaseBluetoothSco()}. */
    public void releaseCapture() {
        releaseBluetoothSco();
    }

    public void releaseCaptureAsync() {
        io.execute(() -> {
            try {
                releaseBluetoothSco();
            } catch (Exception e) {
                Log.w(TAG, "releaseCaptureAsync", e);
            }
        });
    }

    /** Établit le lien SCO (déjà sous {@link #scoLock}). */
    private boolean establishScoWithFallbacksLocked() {
        resetScoDiag();
        lastScoAttemptAtMs = System.currentTimeMillis();
        // Duck / pause musique A2DP — sinon le switch HFP/SCO est souvent refusé.
        if (audioManager.isMusicActive()) {
            notePhase("music_active_pre");
            requestScoAudioFocus();
            sleepQuiet(350L);
        }
        boolean ok = false;
        // SCO ordinaire d'abord — c'est le lien qu'établit un appel téléphonique, et
        // celui qu'utilisent les applis d'enregistrement Bluetooth. Mesuré sur écouteurs
        // TWS : 1,4 % d'échantillons à zéro par ce chemin, contre 29 à 56 % par le nôtre.
        // startVoiceRecognition() met le casque dans un mode HFP particulier que beaucoup
        // d'écouteurs servent mal ; on le garde en repli, pas en premier choix.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok = prepareScoLegacy();
            notePhase("legacy=" + ok);
            if (!ok) {
                cleanupFailedScoAttemptLocked();
                sleepQuiet(SCO_RETRY_PAUSE_MS);
                ok = prepareCommunicationDeviceApi31();
                notePhase("comm=" + ok);
            }
            if (!ok) {
                cleanupFailedScoAttemptLocked();
                sleepQuiet(SCO_RETRY_PAUSE_MS);
                ok = prepareViaHeadsetVoiceRecognition();
                notePhase("vr1=" + ok);
            }
            if (!ok) {
                cleanupFailedScoAttemptLocked();
                sleepQuiet(SCO_RETRY_PAUSE_MS);
                ok = prepareViaHeadsetVoiceRecognition();
                notePhase("vr2=" + ok);
            }
        } else {
            ok = prepareScoLegacy();
            notePhase("legacy=" + ok);
            if (!ok) {
                cleanupFailedScoAttemptLocked();
                sleepQuiet(SCO_RETRY_PAUSE_MS);
                ok = prepareViaHeadsetVoiceRecognition();
                notePhase("vr1=" + ok);
            }
            if (!ok) {
                cleanupFailedScoAttemptLocked();
                sleepQuiet(SCO_RETRY_PAUSE_MS);
                ok = prepareViaHeadsetVoiceRecognition();
                notePhase("vr2=" + ok);
            }
            if (!ok) {
                cleanupFailedScoAttemptLocked();
                sleepQuiet(SCO_RETRY_PAUSE_MS);
                ok = prepareScoLegacy();
                notePhase("legacy2=" + ok);
            }
        }
        if (!ok) {
            cleanupFailedScoAttemptLocked();
            if (lastScoFailReason == null || lastScoFailReason.isEmpty()) {
                lastScoFailReason = "all_paths_failed";
            }
            if (countHfpDevices() == 0 && hasBluetoothAudioOutput()) {
                lastScoFailReason = "a2dp_only_no_hfp";
            }
        } else {
            lastScoFailReason = "";
        }
        return ok;
    }

    private void resetScoDiag() {
        lastScoFailReason = "";
        scoPhaseLog.setLength(0);
        scoStateLog.setLength(0);
    }

    private void notePhase(String p) {
        if (scoPhaseLog.length() > 0) scoPhaseLog.append('|');
        scoPhaseLog.append(p);
    }

    private void noteScoState(int state) {
        if (scoStateLog.length() > 0) scoStateLog.append('>');
        scoStateLog.append(scoStateLabel(state));
    }

    /** Remet mode / SCO / VR propres entre tentatives (évite état IN_COMMUNICATION fantôme). */
    private void cleanupFailedScoAttemptLocked() {
        try {
            stopHeadsetVoiceRecognition();
        } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
        } catch (Exception ignored) {}
        try {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
        } catch (Exception ignored) {}
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {}
        scoPrepared = false;
    }

    private int countHfpDevices() {
        BluetoothHeadset headset;
        synchronized (headsetProxyLock) {
            headset = bluetoothHeadset;
        }
        if (headset == null) return -1;
        try {
            java.util.List<BluetoothDevice> devices = headset.getConnectedDevices();
            return devices == null ? 0 : devices.size();
        } catch (SecurityException e) {
            return -2;
        } catch (Exception e) {
            return -3;
        }
    }

    private void releaseCaptureInternal() {
        if (!scoPrepared && audioManager.getMode() == AudioManager.MODE_NORMAL) {
            abandonScoAudioFocus();
            return;
        }
        scoPrepared = false;
            try {
                stopHeadsetVoiceRecognition();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice();
                } else {
                    audioManager.setBluetoothScoOn(false);
                    audioManager.stopBluetoothSco();
                }
                // Toujours stopper le SCO legacy si on l'avait démarré en fallback API31.
                try {
                    audioManager.setBluetoothScoOn(false);
                    audioManager.stopBluetoothSco();
                } catch (Exception ignored) {}
                audioManager.setMode(AudioManager.MODE_NORMAL);
                Log.i(TAG, "capture route released");
            } catch (Exception e) {
                Log.w(TAG, "releaseCapture", e);
            } finally {
                abandonScoAudioFocus();
            }
    }

    public void release() {
        if (pendingRouteNotify != null) {
            main.removeCallbacks(pendingRouteNotify);
            pendingRouteNotify = null;
        }
        synchronized (scoLock) {
            scoHoldCount.set(0);
            releaseCaptureInternal();
            phoneForced = false;
        }
        unregisterObservers();
        synchronized (LOCK) {
            if (shared == this) shared = null;
        }
    }

    public String describeRoute() {
        refreshRouteKind();
        return describeRouteLocked();
    }

    /** Description sans refresh (évite d'annuler {@link #forcePhoneBuiltin()}). */
    private String describeRouteLocked() {
        StringBuilder sb = new StringBuilder();
        sb.append(activeKind.name());
        if (preferredInput != null) {
            sb.append(" id=").append(preferredInput.getId());
            sb.append(" type=").append(deviceTypeLabel(preferredInput.getType()));
            CharSequence name = preferredInput.getProductName();
            if (name != null && name.length() > 0) {
                sb.append(" name=").append(name);
            }
        }
        sb.append(" source=").append(audioSourceLabel(getAudioSource()));
        sb.append(" mode=").append(modeLabel(audioManager.getMode()));
        sb.append(" holds=").append(scoHoldCount.get());
        if (phoneForced) sb.append(" phoneForced");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            sb.append(" scoOn=").append(audioManager.isBluetoothScoOn());
        }
        return sb.toString();
    }

    public void applyPreferredDevice(AudioRecord record) {
        if (record == null || preferredInput == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean ok = record.setPreferredDevice(preferredInput);
            Log.i(TAG, "setPreferredDevice " + ok + " → " + describeRouteLocked());
        }
    }

    private boolean prepareCommunicationDeviceApi31() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        requestScoAudioFocus();
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            Log.w(TAG, "setMode IN_COMMUNICATION", e);
        }
        AudioDeviceInfo target = findBluetoothCommunicationDevice();
        if (target != null) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean connected = new AtomicBoolean(false);
            final AtomicBoolean sawError = new AtomicBoolean(false);
            BroadcastReceiver waiter = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                    noteScoState(state);
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        connected.set(true);
                        latch.countDown();
                    } else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                        sawError.set(true);
                        latch.countDown();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            boolean registered = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(waiter, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(waiter, filter);
                }
                registered = true;
            } catch (Exception e) {
                Log.w(TAG, "register SCO waiter before setCommunicationDevice", e);
                lastScoFailReason = "comm_register_fail";
            }
            try {
                boolean set = audioManager.setCommunicationDevice(target);
                Log.i(TAG, "setCommunicationDevice ok=" + set
                        + " type=" + deviceTypeLabel(target.getType())
                        + " " + describeRouteLocked());
                if (!set) {
                    lastScoFailReason = "setCommunicationDevice_false";
                } else {
                    boolean scoUp = isHeadsetAudioConnected();
                    if (!scoUp && registered) {
                        latch.await(SCO_WAIT_MS, TimeUnit.MILLISECONDS);
                        scoUp = connected.get() || isHeadsetAudioConnected();
                    }
                    if (scoUp) {
                        preferredInput = findBluetoothInputDevice();
                        scoPrepared = true;
                        Log.i(TAG, "communication device SCO ready "
                                + "preferredInput="
                                + (preferredInput != null
                                ? ("id=" + preferredInput.getId()
                                + " type=" + deviceTypeLabel(preferredInput.getType()))
                                : "null")
                                + " " + describeRouteLocked());
                        return true;
                    }
                    lastScoFailReason = sawError.get()
                            ? "comm_sco_ERROR"
                            : "comm_set_ok_sco_timeout";
                    Log.w(TAG, "setCommunicationDevice ok mais SCO non établi reason="
                            + lastScoFailReason);
                    try {
                        audioManager.clearCommunicationDevice();
                    } catch (Exception ignored) {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastScoFailReason = "comm_interrupted";
            } catch (Exception e) {
                Log.w(TAG, "setCommunicationDevice failed", e);
                lastScoFailReason = "comm_exception:" + e.getClass().getSimpleName();
            } finally {
                if (registered) {
                    try {
                        app.unregisterReceiver(waiter);
                    } catch (Exception ignored) {}
                }
            }
        } else {
            Log.w(TAG, "BT audio présent mais pas encore de communication device");
            lastScoFailReason = "no_communication_device";
        }
        // Ne pas enchaîner startBluetoothSco ici : le caller tente VR puis legacy.
        return false;
    }

    private boolean isHeadsetAudioConnected() {
        BluetoothHeadset headset;
        synchronized (headsetProxyLock) {
            headset = bluetoothHeadset;
        }
        if (headset == null) return false;
        try {
            java.util.List<BluetoothDevice> devices = headset.getConnectedDevices();
            if (devices == null) return false;
            for (BluetoothDevice d : devices) {
                if (headset.isAudioConnected(d)) return true;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "isHeadsetAudioConnected", e);
        }
        return false;
    }

    private boolean prepareScoLegacy() {
        if (scoPrepared && hasLiveBluetoothScoInput()) return true;
        requestScoAudioFocus();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean connected = new AtomicBoolean(false);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicBoolean sawConnecting = new AtomicBoolean(false);
        BroadcastReceiver waiter = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connected.set(true);
                    latch.countDown();
                } else if (state == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                    sawConnecting.set(true);
                } else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                    failed.set(true);
                    latch.countDown();
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                        && sawConnecting.get()) {
                    // CONNECTING → DISCONNECTED = refus système (pas le DISCONNECTED initial).
                    failed.set(true);
                    latch.countDown();
                }
                noteScoState(state);
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(waiter, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(waiter, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "register SCO waiter", e);
            return false;
        }
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
            boolean ok = latch.await(SCO_WAIT_MS, TimeUnit.MILLISECONDS);
            // isBluetoothScoOn() reflète la demande app, PAS la connexion réelle — ne pas s'y fier.
            if (connected.get() || hasLiveBluetoothScoInput()) {
                preferredInput = findBluetoothInputDevice();
                if (preferredInput == null) {
                    preferredInput = findInputOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
                }
                scoPrepared = true;
                Log.i(TAG, "SCO connected awaitOk=" + ok + " " + describeRouteLocked());
                return true;
            }
            Log.w(TAG, "SCO timeout/fail ok=" + ok + " failed=" + failed.get()
                    + " scoOn=" + audioManager.isBluetoothScoOn()
                    + " liveInput=" + hasLiveBluetoothScoInput());
            if (failed.get() && sawConnecting.get()) {
                lastScoFailReason = "legacy_CONNECTING_to_DISCONNECTED";
            } else if (failed.get()) {
                lastScoFailReason = "legacy_SCO_ERROR";
            } else {
                lastScoFailReason = "legacy_timeout";
            }
            try {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice();
                }
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception ignored) {}
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            try {
                app.unregisterReceiver(waiter);
            } catch (Exception ignored) {}
        }
    }

    /** Vraie entrée micro SCO (pas juste setBluetoothScoOn / device communication listé). */
    private boolean hasLiveBluetoothScoInput() {
        if (findInputOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO) != null) return true;
        // BLE headset en entrée = micro live (pas juste "disponible en communication").
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && findInputOfType(AudioDeviceInfo.TYPE_BLE_HEADSET) != null) {
            return true;
        }
        return false;
    }

    private void requestScoAudioFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            } catch (Exception e) {
                Log.w(TAG, "requestAudioFocus legacy", e);
            }
            return;
        }
        if (scoFocusRequest != null) return;
        try {
            scoFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(focusChange ->
                            Log.i(TAG, "sco audio focus change=" + focusChange))
                    .build();
            int r = audioManager.requestAudioFocus(scoFocusRequest);
            Log.i(TAG, "requestAudioFocus result=" + r);
        } catch (Exception e) {
            Log.w(TAG, "requestAudioFocus", e);
            scoFocusRequest = null;
        }
    }

    private void abandonScoAudioFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                audioManager.abandonAudioFocus(null);
            } catch (Exception ignored) {}
            return;
        }
        AudioFocusRequest req = scoFocusRequest;
        scoFocusRequest = null;
        if (req == null) return;
        try {
            audioManager.abandonAudioFocusRequest(req);
        } catch (Exception e) {
            Log.w(TAG, "abandonAudioFocus", e);
        }
    }

    private void ensureHeadsetProxy() {
        synchronized (headsetProxyLock) {
            if (bluetoothHeadset != null) return;
            if (headsetProxyRequesting) return;
            headsetProxyRequesting = true;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                headsetProxyRequesting = false;
                return;
            }
            boolean accepted = adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEADSET && proxy instanceof BluetoothHeadset) {
                        synchronized (headsetProxyLock) {
                            bluetoothHeadset = (BluetoothHeadset) proxy;
                            headsetProxyRequesting = false;
                        }
                        Log.i(TAG, "BluetoothHeadset proxy ready");
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        synchronized (headsetProxyLock) {
                            bluetoothHeadset = null;
                            voiceRecognitionDevice = null;
                            voiceRecognitionActive = false;
                            headsetProxyRequesting = false;
                        }
                        Log.i(TAG, "BluetoothHeadset proxy gone");
                    }
                }
            }, BluetoothProfile.HEADSET);
            if (!accepted) {
                headsetProxyRequesting = false;
                Log.w(TAG, "getProfileProxy(HEADSET) refused");
            }
        } catch (SecurityException e) {
            headsetProxyRequesting = false;
            Log.w(TAG, "BLUETOOTH_CONNECT manquant pour Headset proxy", e);
        } catch (Exception e) {
            headsetProxyRequesting = false;
            Log.w(TAG, "ensureHeadsetProxy", e);
        }
    }

    private BluetoothHeadset waitForHeadsetProxy(long timeoutMs) {
        ensureHeadsetProxy();
        long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            synchronized (headsetProxyLock) {
                if (bluetoothHeadset != null) return bluetoothHeadset;
            }
            sleepQuiet(50L);
        }
        // 2ᵉ chance : proxy parfois jamais livré après un SCO flappé — reset + re-demande.
        Log.w(TAG, "Headset proxy timeout — retry getProfileProxy");
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothHeadset stale;
            synchronized (headsetProxyLock) {
                stale = bluetoothHeadset;
                bluetoothHeadset = null;
                headsetProxyRequesting = false;
            }
            if (adapter != null && stale != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.HEADSET, stale);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        ensureHeadsetProxy();
        deadline = System.currentTimeMillis() + Math.max(800L, timeoutMs / 2);
        while (System.currentTimeMillis() < deadline) {
            synchronized (headsetProxyLock) {
                if (bluetoothHeadset != null) return bluetoothHeadset;
            }
            sleepQuiet(50L);
        }
        synchronized (headsetProxyLock) {
            return bluetoothHeadset;
        }
    }

    /**
     * Active le lien audio HFP via {@link BluetoothHeadset#startVoiceRecognition}.
     * Souvent le seul chemin fiable pour le micro casque hors appel (wake / STT).
     */
    private boolean prepareViaHeadsetVoiceRecognition() {
        BluetoothHeadset headset = waitForHeadsetProxy(4_000L);
        if (headset == null) {
            Log.w(TAG, "voiceRecognition: pas de proxy Headset");
            lastScoFailReason = "vr_no_headset_proxy";
            return false;
        }
        java.util.List<BluetoothDevice> connected;
        try {
            connected = headset.getConnectedDevices();
        } catch (SecurityException e) {
            Log.w(TAG, "voiceRecognition: BLUETOOTH_CONNECT", e);
            lastScoFailReason = "vr_bt_permission";
            return false;
        }
        if (connected == null || connected.isEmpty()) {
            Log.w(TAG, "voiceRecognition: aucun casque HFP connecté");
            lastScoFailReason = "vr_no_hfp_device";
            return false;
        }
        requestScoAudioFocus();
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception ignored) {}

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean connectedSco = new AtomicBoolean(false);
        BroadcastReceiver waiter = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connectedSco.set(true);
                    latch.countDown();
                } else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                    latch.countDown();
                }
                noteScoState(state);
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(waiter, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(waiter, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "voiceRecognition register waiter", e);
            return false;
        }
        BluetoothDevice chosen = null;
        boolean started = false;
        try {
            for (BluetoothDevice device : connected) {
                try {
                    if (headset.isAudioConnected(device)) {
                        chosen = device;
                        started = true;
                        connectedSco.set(true);
                        Log.i(TAG, "voiceRecognition: audio déjà connecté");
                        break;
                    }
                    boolean ok = headset.startVoiceRecognition(device);
                    Log.i(TAG, "startVoiceRecognition " + safeBtName(device) + " ok=" + ok);
                    if (ok) {
                        chosen = device;
                        started = true;
                        break;
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "startVoiceRecognition denied", e);
                }
            }
            if (!started || chosen == null) {
                Log.w(TAG, "voiceRecognition: start échoué sur tous les devices");
                lastScoFailReason = "vr_start_false";
                return false;
            }
            if (!connectedSco.get()) {
                latch.await(SCO_WAIT_MS, TimeUnit.MILLISECONDS);
            }
            boolean live = connectedSco.get() || hasLiveBluetoothScoInput()
                    || headset.isAudioConnected(chosen);
            if (!live) {
                try {
                    headset.stopVoiceRecognition(chosen);
                } catch (Exception ignored) {}
                Log.w(TAG, "voiceRecognition: SCO non établi");
                lastScoFailReason = "vr_started_sco_timeout";
                return false;
            }
            voiceRecognitionDevice = chosen;
            voiceRecognitionActive = true;
            preferredInput = findBluetoothInputDevice();
            if (preferredInput == null) {
                preferredInput = findInputOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
            }
            scoPrepared = true;
            Log.i(TAG, "voiceRecognition SCO ok " + describeRouteLocked());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            try {
                app.unregisterReceiver(waiter);
            } catch (Exception ignored) {}
        }
    }

    private void stopHeadsetVoiceRecognition() {
        if (!voiceRecognitionActive) return;
        BluetoothHeadset headset;
        BluetoothDevice device;
        synchronized (headsetProxyLock) {
            headset = bluetoothHeadset;
            device = voiceRecognitionDevice;
            voiceRecognitionActive = false;
            voiceRecognitionDevice = null;
        }
        if (headset == null || device == null) return;
        try {
            boolean ok = headset.stopVoiceRecognition(device);
            Log.i(TAG, "stopVoiceRecognition ok=" + ok);
        } catch (Exception e) {
            Log.w(TAG, "stopVoiceRecognition", e);
        }
    }

    private static String safeBtName(BluetoothDevice device) {
        if (device == null) return "?";
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (SecurityException ignored) {}
        return device.getAddress();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerObservers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    onDevicesChanged("added");
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    onDevicesChanged("removed");
                }
            };
            audioManager.registerAudioDeviceCallback(deviceCallback, main);
        }
        if (receiverRegistered.compareAndSet(false, true)) {
            scoReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(action)) {
                        int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                        Log.i(TAG, "SCO state=" + scoStateLabel(state));
                    } else if (AudioManager.ACTION_HEADSET_PLUG.equals(action)) {
                        int plugged = intent.getIntExtra("state", 0);
                        Log.i(TAG, "headset plug state=" + plugged);
                    }
                    onDevicesChanged(action);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(scoReceiver, filter);
            }
        }
    }

    private void unregisterObservers() {
        if (deviceCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback);
            } catch (Exception ignored) {}
            deviceCallback = null;
        }
        if (receiverRegistered.compareAndSet(true, false) && scoReceiver != null) {
            try {
                app.unregisterReceiver(scoReceiver);
            } catch (Exception ignored) {}
            scoReceiver = null;
        }
    }

    private void onDevicesChanged(String reason) {
        RouteKind before = activeKind;
        int beforeId = preferredInput != null ? preferredInput.getId() : -1;
        refreshRouteKind();
        int afterId = preferredInput != null ? preferredInput.getId() : -1;
        boolean changed = before != activeKind || beforeId != afterId;
        Log.i(TAG, (changed ? "route changed" : "route ping")
                + " (" + reason + ") " + before + " → " + activeKind
                + " | " + describeRoute());
        if (!changed) {
            try {
                JSONObject f = new JSONObject();
                f.put("reason", reason);
                f.put("route", describeRoute());
                PegaseDiagLog.kws(app, "audio_route_ping", f);
            } catch (Exception ignored) {}
            return;
        }
        // Pendant un hold SCO (wake continu ou STT), l'apparition de l'entrée BT_SCO
        // ne doit pas relancer la capture : release→restart flappe SCO → rms=0.
        boolean scoHoldStable = scoHoldCount.get() > 0
                && before == RouteKind.BLUETOOTH_SCO
                && activeKind == RouteKind.BLUETOOTH_SCO;
        boolean scoStateOnly = reason != null
                && reason.contains("SCO_AUDIO_STATE");
        if (scoHoldStable || (scoStateOnly && activeKind == RouteKind.BLUETOOTH_SCO
                && before == RouteKind.BLUETOOTH_SCO)) {
            Log.i(TAG, "route update suppressed (SCO hold/stable) preferredId="
                    + beforeId + "→" + afterId + " holds=" + scoHoldCount.get());
            try {
                JSONObject f = new JSONObject();
                f.put("reason", reason);
                f.put("suppressed", true);
                f.put("holds", scoHoldCount.get());
                f.put("route", describeRoute());
                PegaseDiagLog.kws(app, "audio_route_suppressed", f);
            } catch (Exception ignored) {}
            return;
        }
        if (pendingRouteNotify != null) {
            main.removeCallbacks(pendingRouteNotify);
        }
        pendingRouteNotify = () -> {
            pendingRouteNotify = null;
            try {
                JSONObject f = new JSONObject();
                f.put("reason", reason);
                f.put("before", before.name());
                f.put("after", activeKind.name());
                f.put("route", describeRoute());
                PegaseDiagLog.kws(app, "audio_route_changed", f);
            } catch (Exception ignored) {}
            RouteChangeListener l = routeChangeListener;
            if (l != null) {
                l.onAudioRouteChanged();
            }
        };
        main.postDelayed(pendingRouteNotify, ROUTE_NOTIFY_DEBOUNCE_MS);
    }

    private void refreshRouteKind() {
        if (phoneForced) {
            activeKind = RouteKind.PHONE_BUILTIN;
            if (preferredInput == null
                    || preferredInput.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                preferredInput = findBuiltinMic();
            }
            return;
        }
        AudioDeviceInfo bt = findBluetoothInputDevice();
        if (bt != null) {
            activeKind = RouteKind.BLUETOOTH_SCO;
            preferredInput = bt;
            return;
        }
        // Casque musique (A2DP) : l'entrée SCO n'apparaît souvent qu'après startBluetoothSco.
        if (hasBluetoothAudioOutput() || isScoLikelyAvailable()) {
            activeKind = RouteKind.BLUETOOTH_SCO;
            // garder preferredInput précédent si encore valide, sinon null jusqu'au SCO
            return;
        }
        AudioDeviceInfo wired = findInputOfType(AudioDeviceInfo.TYPE_WIRED_HEADSET);
        if (wired != null) {
            activeKind = RouteKind.WIRED_HEADSET;
            preferredInput = wired;
            return;
        }
        AudioDeviceInfo usb = findInputOfType(AudioDeviceInfo.TYPE_USB_DEVICE);
        if (usb == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            usb = findInputOfType(AudioDeviceInfo.TYPE_USB_HEADSET);
        }
        if (usb != null) {
            activeKind = RouteKind.USB;
            preferredInput = usb;
            return;
        }
        AudioDeviceInfo builtin = findBuiltinMic();
        if (builtin != null) {
            activeKind = RouteKind.PHONE_BUILTIN;
            preferredInput = builtin;
            return;
        }
        activeKind = RouteKind.UNKNOWN;
        preferredInput = null;
    }

    private boolean hasBluetoothAudioOutput() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_HEARING_AID) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                return true;
            }
        }
        return false;
    }

    private boolean isScoLikelyAvailable() {
        try {
            return audioManager.isBluetoothScoAvailableOffCall();
        } catch (Exception e) {
            return false;
        }
    }

    private AudioDeviceInfo findBluetoothInputDevice() {
        // Pour AudioRecord.setPreferredDevice : uniquement une vraie entrée micro.
        // getAvailableCommunicationDevices() renvoie souvent un device "bt_sco" de rôle
        // communication/sortie — setPreferredDevice échoue alors (false) → rms=0.
        AudioDeviceInfo sco = findInputOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        if (sco != null) return sco;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return findInputOfType(AudioDeviceInfo.TYPE_BLE_HEADSET);
        }
        return null;
    }

    private AudioDeviceInfo findBluetoothCommunicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return device;
            }
        }
        return null;
    }

    private AudioDeviceInfo findBuiltinMic() {
        AudioDeviceInfo builtIn = findInputOfType(AudioDeviceInfo.TYPE_BUILTIN_MIC);
        if (builtIn != null) return builtIn;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return findInputOfType(28);
        }
        return null;
    }

    private AudioDeviceInfo findInputOfType(int type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == type && device.isSource()) {
                return device;
            }
        }
        return null;
    }

    private void logRoute(String phase) {
        Log.i(TAG, phase + ": " + describeRoute());
    }

    private static String audioSourceLabel(int source) {
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "VOICE_COMMUNICATION";
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        if (source == MediaRecorder.AudioSource.MIC) return "MIC";
        return String.valueOf(source);
    }

    private static String modeLabel(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            default: return String.valueOf(mode);
        }
    }

    private static String scoStateLabel(int state) {
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) return "CONNECTED";
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTING) return "CONNECTING";
        if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) return "DISCONNECTED";
        if (state == AudioManager.SCO_AUDIO_STATE_ERROR) return "ERROR";
        return String.valueOf(state);
    }

    private static String deviceTypeLabel(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            switch (type) {
                case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BT_SCO";
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BT_A2DP";
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED";
                case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
                default:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        return "BLE_HEADSET";
                    }
                    break;
            }
        }
        return String.format(Locale.US, "type_%d", type);
    }
}
