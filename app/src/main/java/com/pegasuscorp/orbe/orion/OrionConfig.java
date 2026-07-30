package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration persistante Orion / RunPod.
 * Volume réseau + data center configurables (un volume RunPod = une région).
 */
public final class OrionConfig {

    /**
     * ID API RunPod (pas le nom affiché).
     * Nom console : immediate_amber_shark · région EU-RO-1 · S3 bucket e9354hq21w.
     */
    public static final String DEFAULT_NETWORK_VOLUME_ID = "e9354hq21w";
    public static final String DEFAULT_DATA_CENTER_ID = "EU-RO-1";

    public static final String DEFAULT_IMAGE =
            "runpod/pytorch:2.1.0-py3.10-cuda11.8.0-devel-ubuntu22.04";
    /** Image Comfy : PyTorch 2.8 (custom_op) — indépendante de imageName Orion. */
    public static final String COMFY_IMAGE =
            "runpod/pytorch:1.0.2-cu1281-torch280-ubuntu2404";
    public static final String STARTUP_COMMAND = "bash /workspace/setup.sh";
    public static final String COMFY_STARTUP_COMMAND = "bash /workspace/setup-comfy.sh";

    /** Filtre GPU Comfy : VRAM minimum (Go). */
    public static final int COMFY_MIN_VRAM_GB = 24;
    /** Budget défaut Comfy ($/h) — cartes plus grosses. */
    public static final float COMFY_DEFAULT_BUDGET = 1.50f;

    public static final float MIN_BUDGET = 0.10f;
    public static final float MAX_BUDGET = 2.00f;
    public static final float DEFAULT_BUDGET = 0.50f;

    public static final int MIN_AUTO_STOP = 10;
    public static final int MAX_AUTO_STOP = 120;
    public static final int DEFAULT_AUTO_STOP = 30;

    /** Fichiers / symboles sensibles — patch minimal ou confirmation obligatoire. */
    public static final Set<String> CRITICAL_FILES = Collections.unmodifiableSet(new HashSet<String>() {{
        add("PegaseSession.java");
        add("ConversationManager.java");
        add("VoiceIntentRouter.java");
        add("ContextAnalyzer.java");
        add("PegasePrompt.java");
        add("GroqChatBackend.java");
        add("MultiProviderBackend.java");
        add("MainActivity.java");
        add("OrionQaChecker.java");
        add("guardPhantom");
    }});

    /** @deprecated utiliser {@link #getNetworkVolumeId(Context)} */
    @Deprecated
    public static final String NETWORK_VOLUME_ID = DEFAULT_NETWORK_VOLUME_ID;

    private static final String PREFS = "orion_config";
    private static final String KEY_GPU_IDS = "allowed_gpu_ids";
    private static final String KEY_BUDGET = "max_budget_per_hour";
    private static final String KEY_AUTO_STOP = "auto_stop_minutes";
    private static final String KEY_IMAGE = "image_name";
    private static final String KEY_VOLUME_ID = "network_volume_id";
    private static final String KEY_DATA_CENTER = "data_center_id";

    public final String networkVolumeId;
    public final String dataCenterId;
    public final List<String> allowedGpuIds;
    public final float maxBudgetPerHour;
    public final int autoStopMinutes;
    public final String imageName;

    private OrionConfig(String networkVolumeId, String dataCenterId,
            List<String> allowedGpuIds, float maxBudgetPerHour,
            int autoStopMinutes, String imageName) {
        this.networkVolumeId = networkVolumeId;
        this.dataCenterId = dataCenterId != null ? dataCenterId : "";
        this.allowedGpuIds = Collections.unmodifiableList(new ArrayList<>(allowedGpuIds));
        this.maxBudgetPerHour = maxBudgetPerHour;
        this.autoStopMinutes = autoStopMinutes;
        this.imageName = imageName;
    }

    public static OrionConfig load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        List<String> gpus = parseGpuIds(p.getString(KEY_GPU_IDS, "[]"));
        float budget = p.getFloat(KEY_BUDGET, DEFAULT_BUDGET);
        budget = clamp(budget, MIN_BUDGET, MAX_BUDGET);
        int autoStop = p.getInt(KEY_AUTO_STOP, DEFAULT_AUTO_STOP);
        autoStop = Math.max(MIN_AUTO_STOP, Math.min(MAX_AUTO_STOP, autoStop));
        String image = p.getString(KEY_IMAGE, DEFAULT_IMAGE);
        if (TextUtils.isEmpty(image)) image = DEFAULT_IMAGE;
        String vol = p.getString(KEY_VOLUME_ID, DEFAULT_NETWORK_VOLUME_ID);
        if (TextUtils.isEmpty(vol)) vol = DEFAULT_NETWORK_VOLUME_ID;
        // Migration : ancien défaut = nom affiché, pas l'ID API
        if ("immediate_amber_shark".equalsIgnoreCase(vol.trim())) {
            vol = DEFAULT_NETWORK_VOLUME_ID;
            p.edit().putString(KEY_VOLUME_ID, vol)
                    .putString(KEY_DATA_CENTER, DEFAULT_DATA_CENTER_ID)
                    .apply();
        }
        String dc = p.getString(KEY_DATA_CENTER, DEFAULT_DATA_CENTER_ID);
        if (TextUtils.isEmpty(dc)) dc = DEFAULT_DATA_CENTER_ID;
        return new OrionConfig(vol.trim(), dc.trim(), gpus, budget, autoStop, image);
    }

    public static String getNetworkVolumeId(Context ctx) {
        return load(ctx).networkVolumeId;
    }

    public static String getDataCenterId(Context ctx) {
        return load(ctx).dataCenterId;
    }

    public static void saveNetworkVolumeId(Context ctx, String volumeId) {
        String v = volumeId == null ? "" : volumeId.trim();
        prefs(ctx).edit().putString(KEY_VOLUME_ID,
                v.isEmpty() ? DEFAULT_NETWORK_VOLUME_ID : v).apply();
    }

    public static void saveDataCenterId(Context ctx, String dataCenterId) {
        prefs(ctx).edit().putString(KEY_DATA_CENTER,
                dataCenterId == null ? "" : dataCenterId.trim()).apply();
    }

    /** Enregistre volume + région (après résolution API). */
    public static void saveVolumeAndDataCenter(Context ctx, String volumeId, String dataCenterId) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        String v = volumeId == null ? "" : volumeId.trim();
        ed.putString(KEY_VOLUME_ID, v.isEmpty() ? DEFAULT_NETWORK_VOLUME_ID : v);
        ed.putString(KEY_DATA_CENTER, dataCenterId == null ? "" : dataCenterId.trim());
        ed.apply();
    }

    public static void saveAllowedGpuIds(Context ctx, List<String> ids) {
        JSONArray arr = new JSONArray();
        if (ids != null) {
            for (String id : ids) {
                if (!TextUtils.isEmpty(id)) arr.put(id.trim());
            }
        }
        prefs(ctx).edit().putString(KEY_GPU_IDS, arr.toString()).apply();
    }

    public static void saveMaxBudgetPerHour(Context ctx, float budget) {
        prefs(ctx).edit().putFloat(KEY_BUDGET, clamp(budget, MIN_BUDGET, MAX_BUDGET)).apply();
    }

    public static void saveAutoStopMinutes(Context ctx, int minutes) {
        int m = Math.max(MIN_AUTO_STOP, Math.min(MAX_AUTO_STOP, minutes));
        prefs(ctx).edit().putInt(KEY_AUTO_STOP, m).apply();
    }

    public static void saveImageName(Context ctx, String image) {
        prefs(ctx).edit().putString(KEY_IMAGE,
                TextUtils.isEmpty(image) ? DEFAULT_IMAGE : image.trim()).apply();
    }

    /** Payload pod (volume + startup + env + dataCenter). */
    public static JSONObject buildPodConfig(Context ctx) {
        try {
            OrionConfig cfg = load(ctx);
            String token = com.pegasuscorp.orbe.chat.ApiKeyStore.getOrionToken(ctx);
            JSONObject o = new JSONObject()
                    .put("networkVolumeId", cfg.networkVolumeId)
                    .put("startupCommand", STARTUP_COMMAND)
                    .put("ports", RunPodHttpClient.orionPorts())
                    .put("env", new JSONArray()
                            // Doit matcher setup.sh : modeles sur le volume, pas ~/.ollama
                            .put(envVar("OLLAMA_MODELS", "/workspace/ollama-models"))
                            .put(envVar("OLLAMA_HOST", "0.0.0.0:11434"))
                            .put(envVar("OLLAMA_LOAD_TIMEOUT", "20m"))
                            .put(envVar("OLLAMA_KEEP_ALIVE", "-1"))
                            .put(envVar("OLLAMA_API_KEY", token))
                            .put(envVar("ORION_TOKEN", token)));
            if (!TextUtils.isEmpty(cfg.dataCenterId)) {
                o.put("dataCenterId", cfg.dataCenterId);
            }
            return o;
        } catch (Exception e) {
            throw new IllegalStateException("buildPodConfig", e);
        }
    }

    /** Payload pod Comfy — setup-comfy.sh, sans vars Ollama. */
    public static JSONObject buildComfyPodConfig(Context ctx) {
        try {
            OrionConfig cfg = load(ctx);
            String token = com.pegasuscorp.orbe.chat.ApiKeyStore.getOrionToken(ctx);
            JSONObject o = new JSONObject()
                    .put("networkVolumeId", cfg.networkVolumeId)
                    .put("startupCommand", COMFY_STARTUP_COMMAND)
                    .put("ports", RunPodHttpClient.comfyPorts())
                    .put("env", new JSONArray()
                            .put(envVar("ORION_TOKEN", token))
                            .put(envVar("ORION_FILESERVER", "1"))
                            .put(envVar("FILE_PORT", "3000")));
            if (!TextUtils.isEmpty(cfg.dataCenterId)) {
                o.put("dataCenterId", cfg.dataCenterId);
            }
            return o;
        } catch (Exception e) {
            throw new IllegalStateException("buildComfyPodConfig", e);
        }
    }

    static JSONObject envVar(String key, String value) throws Exception {
        return new JSONObject().put("key", key).put("value", value == null ? "" : value);
    }

    private static List<String> parseGpuIds(String raw) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "").trim();
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isGpuAllowed(String gpuId) {
        if (allowedGpuIds == null || allowedGpuIds.isEmpty()) return true;
        if (gpuId == null) return false;
        String want = gpuId.trim().toLowerCase(Locale.ROOT);
        for (String id : allowedGpuIds) {
            if (id != null && id.trim().toLowerCase(Locale.ROOT).equals(want)) return true;
            if (id != null && want.contains(id.trim().toLowerCase(Locale.ROOT))) return true;
            if (id != null && id.trim().toLowerCase(Locale.ROOT).contains(want)) return true;
        }
        return false;
    }

    public boolean withinBudget(float pricePerHour) {
        return pricePerHour <= maxBudgetPerHour + 1e-4f;
    }

    public static boolean isCriticalFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) return false;
        String name = filename.trim();
        for (String critical : CRITICAL_FILES) {
            if (critical == null || critical.isEmpty()) continue;
            if (critical.contains(".")) {
                if (name.equalsIgnoreCase(critical)) return true;
            } else if (name.toLowerCase(Locale.ROOT).contains(critical.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Fichier ciblé ou mention explicite dans le texte mission. */
    public static boolean isCriticalTarget(String filename, String text) {
        if (isCriticalFilename(filename)) return true;
        if (text == null || text.trim().isEmpty()) return false;
        String fold = text.toLowerCase(Locale.ROOT);
        for (String critical : CRITICAL_FILES) {
            if (critical == null || critical.isEmpty()) continue;
            String needle = critical.toLowerCase(Locale.ROOT);
            if (fold.contains(needle)) return true;
            if (needle.endsWith(".java")) {
                String base = needle.substring(0, needle.length() - 5);
                if (fold.contains(base)) return true;
            }
        }
        return false;
    }
}
