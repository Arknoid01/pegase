package com.pegasuscorp.orbe.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
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
 * Prépare la route micro pour KWS + STT — téléphone ou Bluetooth SCO / communication device.
 * Instance partagée : le wake word et {@link VoiceManager} doivent partager le même SCO.
 */
public final class KwsAudioRouteManager {

    private static final String TAG = "KwsAudioRoute";
    private static final long SCO_WAIT_MS = 4_000L;
    private static final long ROUTE_NOTIFY_DEBOUNCE_MS = 300L;

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
    /** Sérialise prepare/release — VoiceManager (async) et KWS (thread capture) partagent le SCO. */
    private final Object scoLock = new Object();
    private final AtomicInteger scoHoldCount = new AtomicInteger(0);

    private volatile RouteChangeListener routeChangeListener;
    private volatile RouteKind activeKind = RouteKind.UNKNOWN;
    private volatile AudioDeviceInfo preferredInput;
    private volatile boolean scoPrepared;
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);

    private AudioDeviceCallback deviceCallback;
    private BroadcastReceiver scoReceiver;
    private Runnable pendingRouteNotify;

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

    public int getAudioSource() {
        switch (activeKind) {
            case BLUETOOTH_SCO:
            case WIRED_HEADSET:
                return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            default:
                return MediaRecorder.AudioSource.MIC;
        }
    }

    public void forcePhoneBuiltin() {
        activeKind = RouteKind.PHONE_BUILTIN;
        preferredInput = findBuiltinMic();
        scoPrepared = false;
        Log.i(TAG, "forced phone builtin " + describeRoute());
    }

    /**
     * Active SCO / communication device si Bluetooth. Bloquant — hors UI thread.
     * Ref-count : chaque {@code prepare} incrémente un hold (même hors BT) pour que
     * {@link #releaseCapture()} ne coupe jamais le SCO d'un autre client.
     */
    public boolean prepareCapture() {
        synchronized (scoLock) {
            refreshRouteKind();
            logRoute("prepareCapture");
            int holds = scoHoldCount.incrementAndGet();
            if (activeKind != RouteKind.BLUETOOTH_SCO) {
                return true;
            }
            if (holds > 1 && scoPrepared) {
                Log.i(TAG, "SCO already held count=" + holds);
                return true;
            }
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ok = prepareCommunicationDeviceApi31();
            } else {
                ok = prepareScoLegacy();
            }
            if (!ok) {
                scoHoldCount.decrementAndGet();
                Log.w(TAG, "prepareCapture failed — caller peut fallback téléphone");
            }
            return ok;
        }
    }

    /**
     * Prépare la route STT en arrière-plan puis appelle {@code onReady} sur le main thread
     * avec le succès de {@link #prepareCapture()}.
     */
    public void prepareCaptureAsync(java.util.function.Consumer<Boolean> onReady) {
        io.execute(() -> {
            boolean ok = true;
            try {
                ok = prepareCapture();
            } catch (Exception e) {
                Log.w(TAG, "prepareCaptureAsync", e);
                ok = false;
            }
            final boolean result = ok;
            if (onReady != null) main.post(() -> onReady.accept(result));
        });
    }

    /** Libère un hold ; SCO réellement coupé quand le compteur tombe à 0. */
    public void releaseCapture() {
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

    public void releaseCaptureAsync() {
        io.execute(() -> {
            try {
                releaseCapture();
            } catch (Exception e) {
                Log.w(TAG, "releaseCaptureAsync", e);
            }
        });
    }

    private void releaseCaptureInternal() {
        if (!scoPrepared && audioManager.getMode() == AudioManager.MODE_NORMAL) return;
        scoPrepared = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            } else {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            }
            audioManager.setMode(AudioManager.MODE_NORMAL);
            Log.i(TAG, "capture route released");
        } catch (Exception e) {
            Log.w(TAG, "releaseCapture", e);
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
        }
        unregisterObservers();
        synchronized (LOCK) {
            if (shared == this) shared = null;
        }
    }

    public String describeRoute() {
        refreshRouteKind();
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            sb.append(" scoOn=").append(audioManager.isBluetoothScoOn());
        }
        return sb.toString();
    }

    public void applyPreferredDevice(AudioRecord record) {
        if (record == null || preferredInput == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean ok = record.setPreferredDevice(preferredInput);
            Log.i(TAG, "setPreferredDevice " + ok + " → " + describeRoute());
        }
    }

    private boolean prepareCommunicationDeviceApi31() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AudioDeviceInfo target = findBluetoothCommunicationDevice();
        if (target == null) {
            // Souvent l'entrée SCO n'existe qu'après startBluetoothSco.
            Log.w(TAG, "BT audio présent mais pas encore de communication device — SCO legacy");
            return prepareScoLegacy();
        }
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            boolean set = audioManager.setCommunicationDevice(target);
            preferredInput = target;
            scoPrepared = set;
            Log.i(TAG, "setCommunicationDevice ok=" + set + " " + describeRoute());
            if (!set) {
                Log.w(TAG, "setCommunicationDevice false — fallback SCO legacy");
                return prepareScoLegacy();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setCommunicationDevice failed — fallback SCO legacy", e);
            return prepareScoLegacy();
        }
    }

    private boolean prepareScoLegacy() {
        if (scoPrepared && audioManager.isBluetoothScoOn()) return true;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean connected = new AtomicBoolean(false);
        BroadcastReceiver waiter = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connected.set(true);
                    latch.countDown();
                } else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                    latch.countDown();
                }
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
            boolean ok = latch.await(SCO_WAIT_MS, TimeUnit.MILLISECONDS);
            if (connected.get() || audioManager.isBluetoothScoOn()) {
                audioManager.setBluetoothScoOn(true);
                preferredInput = findBluetoothInputDevice();
                scoPrepared = true;
                Log.i(TAG, "SCO connected awaitOk=" + ok + " " + describeRoute());
                return true;
            }
            Log.w(TAG, "SCO timeout ok=" + ok + " scoOn=" + audioManager.isBluetoothScoOn());
            // Ne pas laisser MODE_IN_COMMUNICATION sans SCO
            try {
                audioManager.stopBluetoothSco();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo comm = findBluetoothCommunicationDevice();
            if (comm != null) return comm;
        }
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
