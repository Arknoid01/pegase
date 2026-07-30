package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/** Wi-Fi et Bluetooth : état et panneaux système. */
public final class ConnectivityTool implements Tool {

    @Override
    public String id() {
        return "connectivity";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.CONNECTIVITY;
    }

    @Override
    public String description() {
        return "connectivity(target:\"wifi\"|\"bluetooth\", "
                + "action:\"status\"|\"on\"|\"off\"|\"toggle\"|\"panel\") — "
                + "Wi-Fi et Bluetooth : status=état actuel, on/off/toggle=activer/désactiver, "
                + "panel=ouvre le panneau système Android. "
                + "Utilise pour « active le wifi », « bluetooth on/off », « état du réseau ».";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String target = params.optString("target", "wifi").toLowerCase();
        String action = params.optString("action", "status").toLowerCase();
        if ("bluetooth".equals(target) || "bt".equals(target)) {
            handleBluetooth(ctx, action, cb);
        } else {
            handleWifi(ctx, action, cb);
        }
    }

    private static void handleWifi(Context ctx, String action, ToolCallback cb) {
        switch (action) {
            case "on":
            case "off":
            case "toggle":
            case "panel":
            case "open":
                openWifiPanel(ctx, cb);
                break;
            case "status":
            default:
                cb.onSuccess(ToolResult.text(readWifiStatus(ctx)));
        }
    }

    private static void handleBluetooth(Context ctx, String action, ToolCallback cb) {
        switch (action) {
            case "on":
                if (trySetBluetooth(ctx, true)) {
                    cb.onSuccess(ToolResult.text("Bluetooth activé."));
                } else {
                    openBluetoothSettings(ctx, cb, "Voici les réglages Bluetooth.");
                }
                break;
            case "off":
                if (trySetBluetooth(ctx, false)) {
                    cb.onSuccess(ToolResult.text("Bluetooth désactivé."));
                } else {
                    openBluetoothSettings(ctx, cb, "Voici les réglages Bluetooth.");
                }
                break;
            case "toggle":
                Boolean enabled = readBluetoothEnabled();
                if (enabled != null && trySetBluetooth(ctx, !enabled)) {
                    cb.onSuccess(ToolResult.text(!enabled ? "Bluetooth activé." : "Bluetooth désactivé."));
                } else {
                    openBluetoothSettings(ctx, cb, "Voici les réglages Bluetooth.");
                }
                break;
            case "panel":
            case "open":
                openBluetoothSettings(ctx, cb, "J'ouvre les réglages Bluetooth.");
                break;
            case "status":
            default:
                cb.onSuccess(ToolResult.text(readBluetoothStatus()));
        }
    }

    private static String readWifiStatus(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "Je n'ai pas accès au Wi-Fi.";
        boolean on = wm.isWifiEnabled();
        if (!on) return "Le Wi-Fi est désactivé.";
        String ssid = null;
        try {
            if (wm.getConnectionInfo() != null) {
                ssid = wm.getConnectionInfo().getSSID();
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                }
            }
        } catch (Exception ignored) {}
        if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
            return "Wi-Fi activé, connecté à " + ssid + ".";
        }
        return "Wi-Fi activé.";
    }

    private static String readBluetoothStatus() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "Bluetooth indisponible sur cet appareil.";
        return adapter.isEnabled() ? "Bluetooth activé." : "Bluetooth désactivé.";
    }

    private static Boolean readBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;
        return adapter.isEnabled();
    }

    private static void openWifiPanel(Context ctx, ToolCallback cb) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent panel = new Intent(Settings.Panel.ACTION_WIFI)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(panel);
                cb.onSuccessAndExit(ToolResult.text("Voici le panneau Wi-Fi."));
                return;
            }
        } catch (Exception ignored) {}
        Intent settings = new Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(settings);
        cb.onSuccessAndExit(ToolResult.text("J'ouvre les réglages Wi-Fi."));
    }

    private static void openBluetoothSettings(Context ctx, ToolCallback cb, String reply) {
        Intent settings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(settings);
        cb.onSuccessAndExit(ToolResult.text(reply));
    }

    private static boolean trySetBluetooth(Context ctx, boolean enable) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        try {
            return enable ? adapter.enable() : adapter.disable();
        } catch (Exception e) {
            return false;
        }
    }
}
