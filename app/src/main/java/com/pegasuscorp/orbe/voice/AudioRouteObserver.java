package com.pegasuscorp.orbe.voice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Source audio unique pour le process {@code :voice} : mise à jour uniquement par
 * callbacks système (devices + profil HFP). Pas de {@code refresh}/{@code check}/polling.
 *
 * <p>Ne remplace pas encore {@link KwsAudioRouteManager} (préparation SCO) — uniquement
 * la décision {@link AudioSource}.
 */
public final class AudioRouteObserver implements WakeCoordinator.AudioSourceReadable {

    private static final String TAG = "AudioRouteObserver";

    public enum AudioSource {
        BLUETOOTH_HFP,
        PHONE_BUILTIN
    }

    public interface Listener {
        void onAudioSourceChanged(AudioSource source);
    }

    private final Context app;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Set<Integer> scoInputDeviceIds = new HashSet<>();
    private volatile boolean hfpProfileConnected;
    private volatile AudioSource current = AudioSource.PHONE_BUILTIN;
    private volatile Listener listener;

    private AudioDeviceCallback deviceCallback;
    private BroadcastReceiver hfpReceiver;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;
    private boolean profileProxyRequested;
    private boolean released;

    public AudioRouteObserver(Context context) {
        app = context.getApplicationContext();
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        registerDeviceCallback();
        registerHfpConnectionReceiver();
        requestHeadsetProfile();
    }

    /** Lecture synchrone de l'état caché (jamais de query active). */
    @Override
    public AudioSource currentSource() {
        return current;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void release() {
        if (released) return;
        released = true;
        listener = null;
        if (deviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback);
            } catch (Exception ignored) {}
            deviceCallback = null;
        }
        if (hfpReceiver != null) {
            try {
                app.unregisterReceiver(hfpReceiver);
            } catch (Exception ignored) {}
            hfpReceiver = null;
        }
        if (bluetoothAdapter != null && bluetoothHeadset != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            } catch (Exception ignored) {}
        }
        bluetoothHeadset = null;
        profileProxyRequested = false;
    }

    private void registerDeviceCallback() {
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                boolean changed = false;
                if (addedDevices != null) {
                    for (AudioDeviceInfo d : addedDevices) {
                        if (isBluetoothScoInput(d)) {
                            changed |= scoInputDeviceIds.add(d.getId());
                        }
                    }
                }
                if (changed) recomputeFromCallbacks("devices_added");
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                boolean changed = false;
                if (removedDevices != null) {
                    for (AudioDeviceInfo d : removedDevices) {
                        if (isBluetoothScoInput(d)) {
                            changed |= scoInputDeviceIds.remove(d.getId());
                        }
                    }
                }
                if (changed) recomputeFromCallbacks("devices_removed");
            }
        };
        audioManager.registerAudioDeviceCallback(deviceCallback, main);
        // Android livre les devices déjà connectés via onAudioDevicesAdded au register.
    }

    private void registerHfpConnectionReceiver() {
        hfpReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (!BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                boolean connected = state == BluetoothProfile.STATE_CONNECTED;
                if (hfpProfileConnected == connected) return;
                hfpProfileConnected = connected;
                recomputeFromCallbacks("hfp_connection_state");
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(hfpReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(hfpReceiver, filter);
        }
    }

    private void requestHeadsetProfile() {
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Exception e) {
            Log.w(TAG, "no BluetoothAdapter", e);
            return;
        }
        if (bluetoothAdapter == null || profileProxyRequested) return;
        profileProxyRequested = true;
        try {
            bluetoothAdapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HEADSET
                            || !(proxy instanceof BluetoothHeadset)) {
                        return;
                    }
                    bluetoothHeadset = (BluetoothHeadset) proxy;
                    // Une seule lecture au callback proxy — pas de refresh public / polling.
                    applyConnectedDevicesFromProxy(bluetoothHeadset);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile != BluetoothProfile.HEADSET) return;
                    bluetoothHeadset = null;
                    if (hfpProfileConnected) {
                        hfpProfileConnected = false;
                        recomputeFromCallbacks("hfp_proxy_gone");
                    }
                }
            }, BluetoothProfile.HEADSET);
        } catch (Exception e) {
            profileProxyRequested = false;
            Log.w(TAG, "getProfileProxy HEADSET failed", e);
        }
    }

    private void applyConnectedDevicesFromProxy(BluetoothHeadset headset) {
        boolean any = false;
        try {
            List<BluetoothDevice> devices = headset.getConnectedDevices();
            any = devices != null && !devices.isEmpty();
        } catch (SecurityException se) {
            Log.w(TAG, "getConnectedDevices denied", se);
        } catch (Exception e) {
            Log.w(TAG, "getConnectedDevices", e);
        }
        if (hfpProfileConnected == any) return;
        hfpProfileConnected = any;
        recomputeFromCallbacks("hfp_proxy_ready");
    }

    private void recomputeFromCallbacks(String reason) {
        // Décision stricte : profil HFP CONNECTED → BLUETOOTH_HFP, sinon téléphone.
        // Les devices SCO (AudioDeviceCallback) ne font qu'informer le log ; pas de polling.
        AudioSource next = hfpProfileConnected
                ? AudioSource.BLUETOOTH_HFP
                : AudioSource.PHONE_BUILTIN;
        if (next == current) {
            Log.d(TAG, "source ping (" + reason + ") " + current
                    + " hfp=" + hfpProfileConnected
                    + " scoInputs=" + scoInputDeviceIds.size());
            return;
        }
        AudioSource prev = current;
        current = next;
        Log.i(TAG, "source " + prev + " → " + next + " (" + reason + ")"
                + " hfp=" + hfpProfileConnected
                + " scoInputs=" + scoInputDeviceIds.size());
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onAudioSourceChanged(next));
        }
    }

    private static boolean isBluetoothScoInput(AudioDeviceInfo d) {
        return d != null
                && d.isSource()
                && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }
}
