package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Contexte écran extrait localement (a11y / OCR) — partagé via un fichier dans
 * {@code files/copilot/} (même répertoire que le snapshot a11y).
 * Fichier et non SharedPreferences : écrit depuis le process {@code :copilot}
 * (CopilotAnalysisEngine) et lu depuis le process principal (CopilotScreenContext) —
 * les SharedPreferences sont cachées par process et ne se propagent pas.
 * Le cloud ne reçoit que le texte filtré, jamais l'image ni les positions brutes.
 */
public final class ScreenContextStore {

    private static final String FILE_NAME = "screen_context.json";
    private static final int MAX_READ_BYTES = 256_000;

    private ScreenContextStore() {}

    public static void update(Context ctx, String packageName, String plainText) {
        update(ctx, packageName, plainText, System.currentTimeMillis());
    }

    /** Visible pour les tests (timestamp contrôlé). */
    static void update(Context ctx, String packageName, String plainText, long timestampMs) {
        if (ctx == null) return;
        try {
            JSONObject doc = new JSONObject();
            doc.put("package", packageName != null ? packageName : "");
            doc.put("text", plainText != null ? plainText : "");
            doc.put("ts", timestampMs);
            File dir = dir(ctx);
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(dir, FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            }
            File out = new File(dir, FILE_NAME);
            if (!tmp.renameTo(out)) {
                // Rename atomique impossible (rare) — écriture directe en dernier recours.
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public static String getLastText(Context ctx) {
        JSONObject doc = read(ctx);
        return doc != null ? doc.optString("text", "") : "";
    }

    public static String getLastPackage(Context ctx) {
        JSONObject doc = read(ctx);
        return doc != null ? doc.optString("package", "") : "";
    }

    public static long getLastTimestampMs(Context ctx) {
        JSONObject doc = read(ctx);
        return doc != null ? doc.optLong("ts", 0L) : 0L;
    }

    private static JSONObject read(Context ctx) {
        if (ctx == null) return null;
        File f = new File(dir(ctx), FILE_NAME);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_READ_BYTES)];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static File dir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "copilot");
    }
}
