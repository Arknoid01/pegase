package com.pegasuscorp.orbe.sync;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Identité stable de l'appareil pour le journal de sync.
 */
public final class SyncDevice {

    public final String deviceId;
    public final String deviceLabel;
    public final String platform;
    public final long createdAtMs;

    SyncDevice(String deviceId, String deviceLabel, String platform, long createdAtMs) {
        this.deviceId = deviceId != null ? deviceId : "";
        this.deviceLabel = deviceLabel != null ? deviceLabel : "";
        this.platform = platform != null ? platform : "android";
        this.createdAtMs = createdAtMs;
    }

    public static synchronized SyncDevice get(Context context) {
        Context app = context.getApplicationContext();
        File file = PegaseFileSystem.get(app).syncDeviceJson();
        SyncDevice loaded = read(file);
        if (loaded != null) return loaded;
        String androidId = Settings.Secure.getString(
                app.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.isEmpty()) {
            androidId = UUID.randomUUID().toString();
        }
        String id = "android-" + androidId;
        String label = Build.MANUFACTURER + " " + Build.MODEL;
        SyncDevice created = new SyncDevice(id, label.trim(), "android", System.currentTimeMillis());
        write(file, created);
        return created;
    }

    static synchronized void resetForTests(Context context) {
        File f = PegaseFileSystem.get(context.getApplicationContext()).syncDeviceJson();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static SyncDevice read(File file) {
        if (!file.isFile()) return null;
        try {
            byte[] raw = readAll(file);
            if (raw.length == 0) return null;
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String id = o.optString("deviceId", "").trim();
            if (id.isEmpty()) return null;
            return new SyncDevice(
                    id,
                    o.optString("deviceLabel", ""),
                    o.optString("platform", "android"),
                    o.optLong("createdAtMs", System.currentTimeMillis()));
        } catch (Exception e) {
            return null;
        }
    }

    static void write(File file, SyncDevice device) {
        try {
            JSONObject o = new JSONObject();
            o.put("deviceId", device.deviceId);
            o.put("deviceLabel", device.deviceLabel);
            o.put("platform", device.platform);
            o.put("createdAtMs", device.createdAtMs);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64_000)];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }
}
