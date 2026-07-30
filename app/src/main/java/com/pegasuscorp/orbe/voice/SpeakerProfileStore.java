package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Empreinte vocale enregistrée (plusieurs échantillons « Pégase »).
 */
public final class SpeakerProfileStore {

    private static final String OWNER_ID = "owner";
    private static final float DEFAULT_THRESHOLD = 0.58f;

    private static SpeakerProfileStore instance;

    private final File profileFile;
    private final List<float[]> samples = new ArrayList<>();
    private boolean requireOwnerVoice = true;
    private float threshold = DEFAULT_THRESHOLD;

    private SpeakerProfileStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "speaker");
        if (!dir.exists()) dir.mkdirs();
        profileFile = new File(dir, "profile.json");
        load();
    }

    public static synchronized SpeakerProfileStore getInstance(Context context) {
        if (instance == null) {
            instance = new SpeakerProfileStore(context.getApplicationContext());
        }
        return instance;
    }

    public static String ownerId() {
        return OWNER_ID;
    }

    public boolean isEnrolled() {
        return !samples.isEmpty();
    }

    public boolean isRequireOwnerVoice() {
        return requireOwnerVoice;
    }

    public void setRequireOwnerVoice(boolean require) {
        requireOwnerVoice = require;
        save();
    }

    public float getThreshold() {
        return threshold;
    }

    public void setThreshold(float value) {
        threshold = Math.max(0.35f, Math.min(0.85f, value));
        save();
    }

    public int getSampleCount() {
        return samples.size();
    }

    public List<float[]> getSamples() {
        return new ArrayList<>(samples);
    }

    public void addSample(float[] embedding) {
        if (embedding == null || embedding.length == 0) return;
        samples.add(embedding);
        while (samples.size() > 5) samples.remove(0);
        save();
    }

    public void clear() {
        samples.clear();
        save();
    }

    private void load() {
        samples.clear();
        if (!profileFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            requireOwnerVoice = root.optBoolean("requireOwnerVoice", true);
            threshold = (float) root.optDouble("threshold", DEFAULT_THRESHOLD);
            JSONArray arr = root.optJSONArray("samples");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray emb = arr.getJSONArray(i);
                float[] vec = new float[emb.length()];
                for (int j = 0; j < emb.length(); j++) {
                    vec[j] = (float) emb.getDouble(j);
                }
                samples.add(vec);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (float[] vec : samples) {
                JSONArray emb = new JSONArray();
                for (float v : vec) emb.put(v);
                arr.put(emb);
            }
            JSONObject root = new JSONObject()
                    .put("requireOwnerVoice", requireOwnerVoice)
                    .put("threshold", threshold)
                    .put("samples", arr);
            try (FileOutputStream out = new FileOutputStream(profileFile)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
