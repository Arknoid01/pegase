package com.pegasuscorp.orbe.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prépare la route micro pour le KWS — téléphone ou Bluetooth SCO / communication device.
 * Réagit aux changements de périphérique en cours d'écoute (casque branché après démarrage).
 */
public final class KwsAudioRouteManager {

    private static final String TAG = "KwsAudioRoute";
    private static final long SCO_WAIT_MS = 4_000L;
    /** Évite les rafales SCO CONNECTING→CONNECTED sur le même device. */
    private static final long ROUTE_NOTIFY_DEBOUNCE_MS = 300L;

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

    private volatile RouteChangeListener routeChangeListener;
    private volatile RouteKind activeKind = RouteKind.UNKNOWN;
    private volatile AudioDeviceInfo preferredInput;
    private volatile boolean scoPrepared;
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);

    private AudioDeviceCallback deviceCallback;
    private BroadcastReceiver scoReceiver;
    private Runnable pendingRouteNotify;

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

    /** Source recommandée selon la route active. */
    public int getAudioSource() {
        switch (activeKind) {
            case BLUETOOTH_SCO:
            case WIRED_HEADSET:
                return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            default:
                // MIC = comportement historique (meilleur taux de détection KWS sur plusieurs OEM).
                return MediaRecorder.AudioSource.MIC;
        }
    }

    /** Après échec SCO / communication device — forcer le micro téléphone. */
    public void forcePhoneBuiltin() {
        activeKind = RouteKind.PHONE_BUILTIN;
        preferredInput = findBuiltinMic();
        scoPrepared = false;
        Log.i(TAG, "forced phone builtin " + describeRoute());
    }

    /**
     * Active SCO / communication device si Bluetooth. Bloquant — appeler hors UI thread.
     * @return true si prêt à ouvrir {@link AudioRecord}
     */
    public boolean prepareCapture() {
        refreshRouteKind();
        logRoute("prepareCapture");
        if (activeKind != RouteKind.BLUETOOTH_SCO) {
            scoPrepared = false;
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return prepareCommunicationDeviceApi31();
        }
        return prepareScoLegacy();
    }

    public void releaseCapture() {
        if (!scoPrepared) return;
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
        releaseCapture();
        unregisterObservers();
    }

    /** Chaîne lisible pour les logs KWS (téléphone vs Bluetooth SCO, etc.). */
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
            Log.w(TAG, "BT connecté mais aucun communication device — fallback téléphone");
            activeKind = RouteKind.PHONE_BUILTIN;
            preferredInput = findBuiltinMic();
            return true;
        }
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            boolean set = audioManager.setCommunicationDevice(target);
            preferredInput = target;
            scoPrepared = set;
            Log.i(TAG, "setCommunicationDevice ok=" + set + " " + describeRoute());
            return set;
        } catch (Exception e) {
            Log.w(TAG, "setCommunicationDevice failed", e);
            return false;
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
        app.registerReceiver(waiter, filter);
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.startBluetoothSco();
            boolean ok = latch.await(SCO_WAIT_MS, TimeUnit.MILLISECONDS);
            if (connected.get()) {
                audioManager.setBluetoothScoOn(true);
                preferredInput = findBluetoothInputDevice();
                scoPrepared = true;
                Log.i(TAG, "SCO connected " + describeRoute());
                return true;
            }
            Log.w(TAG, "SCO timeout ok=" + ok + " scoOn=" + audioManager.isBluetoothScoOn());
            return audioManager.isBluetoothScoOn();
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
        // Ne pas appeler releaseCapture() ici — le thread KWS peut être dans AudioRecord.read().
        // La libération SCO/mode se fait dans SherpaKwsEngine.closeMic() après routeChanged.
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
        // TYPE_BUILTIN_ECHO_REFERENCE (= 28) — API 31+ ; littéral pour stubs SDK incomplets
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
