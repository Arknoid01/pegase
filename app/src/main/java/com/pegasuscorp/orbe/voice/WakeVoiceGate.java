package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Deuxième étage du mot d'éveil : la voix qui vient de dire « Hey Pégase » est-elle
 * celle du propriétaire ?
 * <p>
 * openWakeWord se trompe avec une confiance de 0,99 sur la radio : monter son seuil
 * n'y change rien. On compare donc l'empreinte vocale des ~2 s qui précèdent le hit à
 * une empreinte « Hey Pégase » construite depuis les enregistrements du wake word
 * ({@link WakeOwwStore#listSampleFiles}). L'empreinte de {@link SpeakerProfileStore}
 * (phrases libres de 2 s) sépare mal sur une phrase aussi courte — calibré le
 * 2026-10-21 : propriétaire ≥ 0,74, voix de synthèse ≤ 0,67 avec l'empreinte wake.
 * <p>
 * Réglages dans un fichier (pas des SharedPreferences) : lus dans le process
 * {@code :voice}, écrits depuis le launcher.
 */
public final class WakeVoiceGate {

    private static final String TAG = "WakeVoiceGate";
    public static final float DEFAULT_THRESHOLD = 0.65f;
    private static final String SETTINGS_FILE = "wake_voice.json";
    private static final String PROFILE_FILE = "wake_voice_profile.json";
    private static final String HITS_DIR = "diag/wake_hits";
    private static final int MAX_SAVED_HITS = 60;

    public static final class Verdict {
        public final boolean accepted;
        /** Similarité cosinus, ou NaN si la vérification n'a pas eu lieu. */
        public final float similarity;
        public final String reason;

        Verdict(boolean accepted, float similarity, String reason) {
            this.accepted = accepted;
            this.similarity = similarity;
            this.reason = reason;
        }
    }

    private static float[] cachedProfile;
    private static int cachedSources = -1;

    private WakeVoiceGate() {}

    // --- Réglages ---

    public static boolean isEnabled(Context context) {
        return readSettings(context).optBoolean("enabled", true);
    }

    public static float getThreshold(Context context) {
        double t = readSettings(context).optDouble("threshold", DEFAULT_THRESHOLD);
        return clampThreshold((float) t);
    }

    public static void setEnabled(Context context, boolean enabled) {
        JSONObject s = readSettings(context);
        try { s.put("enabled", enabled); } catch (Exception ignored) {}
        writeSettings(context, s);
    }

    public static void setThreshold(Context context, float threshold) {
        JSONObject s = readSettings(context);
        try { s.put("threshold", (double) clampThreshold(threshold)); } catch (Exception ignored) {}
        writeSettings(context, s);
    }

    private static float clampThreshold(float t) {
        return Math.max(0.40f, Math.min(0.90f, t));
    }

    // --- Vérification ---

    /** Charge le modèle et l'empreinte avant la première écoute (évite ~5 s au 1er hit). */
    public static void warmUp(Context context) {
        if (!isEnabled(context) || !SpeakerModelStore.isModelReady(context)) return;
        try {
            profile(context);
        } catch (Throwable t) {
            Log.w(TAG, "warmUp failed", t);
        }
    }

    /** Bloquant (~100-300 ms) : à appeler depuis le thread du moteur de wake. */
    public static Verdict check(Context context, float[] samples) {
        if (!isEnabled(context)) return new Verdict(true, Float.NaN, "off");
        if (!SpeakerModelStore.isModelReady(context)) {
            return new Verdict(true, Float.NaN, "no_model");
        }
        float[] profile = profile(context);
        if (profile == null) return new Verdict(true, Float.NaN, "no_profile");
        float[] emb = SpeakerVerifierEngine.getInstance().embedBlocking(context, samples);
        if (emb == null) return new Verdict(true, Float.NaN, "no_embedding");
        float sim = dot(normalize(emb), profile);
        boolean ok = sim >= getThreshold(context);
        return new Verdict(ok, sim, ok ? "owner" : "other_voice");
    }

    private static synchronized float[] profile(Context context) {
        File[] files = WakeOwwStore.listSampleFiles(context);
        int sources = files == null ? 0 : files.length;
        if (sources < 3) return null;
        if (cachedProfile != null && cachedSources == sources) return cachedProfile;

        File cache = new File(speakerDir(context), PROFILE_FILE);
        try {
            if (cache.isFile()) {
                JSONObject o = new JSONObject(readText(cache));
                JSONArray arr = o.getJSONArray("embedding");
                if (o.optInt("sources") == sources && arr.length() > 0) {
                    float[] p = new float[arr.length()];
                    for (int i = 0; i < p.length; i++) p[i] = (float) arr.getDouble(i);
                    cachedProfile = p;
                    cachedSources = sources;
                    return p;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "profile cache unreadable", e);
        }

        float[] sum = null;
        int used = 0;
        for (File f : files) {
            float[] pcm = readWav16k(f);
            if (pcm == null) continue;
            float[] e = SpeakerVerifierEngine.getInstance().embedBlocking(context, pcm);
            if (e == null) continue;
            e = normalize(e);
            if (sum == null) sum = new float[e.length];
            for (int i = 0; i < e.length; i++) sum[i] += e[i];
            used++;
        }
        if (sum == null || used < 3) return null;
        float[] p = normalize(sum);
        try {
            JSONArray arr = new JSONArray();
            for (float v : p) arr.put((double) v);
            writeText(cache, new JSONObject()
                    .put("sources", sources).put("used", used).put("embedding", arr).toString());
        } catch (Exception e) {
            Log.w(TAG, "profile cache write failed", e);
        }
        Log.i(TAG, "wake voice profile built from " + used + "/" + sources + " samples");
        cachedProfile = p;
        cachedSources = sources;
        return p;
    }

    // --- Archive des déclenchements (réentraînement : hard negatives) ---

    /** Garde l'audio du hit dans files/diag/wake_hits (les 60 derniers). */
    public static void saveHit(Context context, float[] samples, float owwScore, Verdict v) {
        if (samples == null || samples.length == 0) return;
        try {
            File dir = new File(context.getFilesDir(), HITS_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String spk = Float.isNaN(v.similarity)
                    ? "na" : String.format(Locale.US, "%.2f", v.similarity);
            String name = String.format(Locale.US, "%s_oww%.2f_spk%s_%s.wav",
                    stamp, owwScore, spk, v.accepted ? "ok" : "rej");
            writeWav16k(new File(dir, name), samples);
            File[] all = dir.listFiles((d, n) -> n.endsWith(".wav"));
            if (all != null && all.length > MAX_SAVED_HITS) {
                Arrays.sort(all, Comparator.comparing(File::getName));
                for (int i = 0; i < all.length - MAX_SAVED_HITS; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    all[i].delete();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "saveHit failed", e);
        }
    }

    // --- Utilitaires ---

    private static float dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float s = 0f;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    private static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        float[] out = new float[v.length];
        if (n == 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    /** WAV PCM 16 bits mono 16 kHz (format de WakeWordRecordActivity). */
    static float[] readWav16k(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] all = readAll(in);
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 12;
            while (pos + 8 <= all.length) {
                String id = new String(all, pos, 4, StandardCharsets.US_ASCII);
                int size = bb.getInt(pos + 4);
                if ("data".equals(id)) {
                    int start = pos + 8;
                    int n = Math.max(0, Math.min(size, all.length - start)) / 2;
                    float[] out = new float[n];
                    for (int i = 0; i < n; i++) out[i] = bb.getShort(start + 2 * i) / 32768f;
                    return out;
                }
                pos += 8 + size + (size & 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "readWav " + f.getName(), e);
        }
        return null;
    }

    private static void writeWav16k(File f, float[] samples) throws Exception {
        int dataLen = samples.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1)
                .putInt(AudioCapture.SAMPLE_RATE).putInt(AudioCapture.SAMPLE_RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
        for (float s : samples) {
            bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(s * 32767f))));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bb.array());
        }
    }

    private static File speakerDir(Context context) {
        File dir = new File(context.getFilesDir(), "speaker");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static JSONObject readSettings(Context context) {
        File f = new File(speakerDir(context), SETTINGS_FILE);
        try {
            if (f.isFile()) return new JSONObject(readText(f));
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private static void writeSettings(Context context, JSONObject s) {
        try {
            writeText(new File(speakerDir(context), SETTINGS_FILE), s.toString());
        } catch (Exception e) {
            Log.w(TAG, "settings write failed", e);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp);
    }
}
