package com.pegasuscorp.orbe.orion;

import android.text.TextUtils;

import com.pegasuscorp.orbe.tools.HttpJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client HTTP RunPod (REST v1 + GraphQL gpuTypes).
 * Pas de nouvelle lib — {@link HttpJson}.
 */
public final class RunPodHttpClient implements RunPodClient {

    static final String REST = "https://rest.runpod.io/v1";
    static final String GRAPHQL = "https://api.runpod.io/graphql";

    @Override
    public List<GpuOffer> listGpuTypes(String apiKey) throws Exception {
        String query = "{ gpuTypes { id displayName memoryInGb securePrice communityPrice "
                + "communitySpotPrice secureSpotPrice } }";
        JSONObject body = new JSONObject().put("query", query);
        Map<String, String> headers = auth(apiKey);
        headers.put("Content-Type", "application/json");
        JSONObject resp = HttpJson.postJson(GRAPHQL + "?api_key=" + apiKey, headers, body);
        JSONArray types = resp.optJSONObject("data") != null
                ? resp.getJSONObject("data").optJSONArray("gpuTypes")
                : null;
        List<GpuOffer> out = new ArrayList<>();
        if (types == null) return out;
        for (int i = 0; i < types.length(); i++) {
            JSONObject g = types.getJSONObject(i);
            String id = g.optString("id", "");
            if (id.isEmpty()) continue;
            float price = firstPositive(
                    (float) g.optDouble("securePrice", 0),
                    (float) g.optDouble("communityPrice", 0),
                    (float) g.optDouble("secureSpotPrice", 0),
                    (float) g.optDouble("communitySpotPrice", 0));
            int vram = g.optInt("memoryInGb", 0);
            String name = g.optString("displayName", id);
            out.add(new GpuOffer(id, name, vram, price, price > 0));
        }
        return out;
    }

    @Override
    public List<NetworkVolume> listNetworkVolumes(String apiKey) throws Exception {
        Map<String, String> headers = auth(apiKey);
        JSONArray arr = HttpJson.getArray(REST + "/networkvolumes", headers, 10_000, 30_000);
        List<NetworkVolume> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            if (id.isEmpty()) continue;
            out.add(new NetworkVolume(
                    id,
                    o.optString("name", ""),
                    o.optString("dataCenterId", ""),
                    o.optInt("size", 0)));
        }
        return out;
    }

    @Override
    public String startPod(String apiKey, JSONObject podBody) throws Exception {
        if (podBody == null) throw new IllegalArgumentException("podBody null");
        JSONObject restBody = toRestCreateBody(podBody);
        Map<String, String> headers = auth(apiKey);
        headers.put("Content-Type", "application/json");
        JSONObject resp = HttpJson.postJson(REST + "/pods", headers, restBody);
        String id = resp.optString("id", "");
        if (TextUtils.isEmpty(id)) {
            throw new IllegalStateException("Réponse RunPod sans id pod");
        }
        return id;
    }

    @Override
    public String getPodStatus(String apiKey, String podId) throws Exception {
        Map<String, String> headers = auth(apiKey);
        JSONObject resp = HttpJson.get(REST + "/pods/" + podId, headers, 10_000, 30_000);

        // desiredStatus vaut souvent RUNNING dès la création — ne reflète pas le boot réel.
        String desired = resp.optString("desiredStatus", "").trim();
        String desiredUp = desired.toUpperCase(Locale.ROOT);
        if (desiredUp.contains("EXITED") || desiredUp.contains("TERMINAT")
                || desiredUp.contains("FAILED")) {
            return desiredUp;
        }
        // Fallback si desiredStatus vide : status racine
        if (desiredUp.isEmpty()) {
            String status = resp.optString("status", "").trim().toUpperCase(Locale.ROOT);
            if (status.contains("EXITED") || status.contains("TERMINAT")
                    || status.contains("FAILED")) {
                return status;
            }
        }

        // REST v1 n'a pas runtime.uptimeInSeconds (GraphQL seulement).
        // publicIp / portMappings restent vides tant que le pod initialise.
        if (isRestPodActuallyUp(resp)) {
            return "RUNNING";
        }
        return "STARTING";
    }

    /**
     * Signaux REST que le container a bien une IP / des ports publics
     * (docs RunPod : vides pendant l'init).
     */
    private static boolean isRestPodActuallyUp(JSONObject resp) {
        if (resp == null) return false;
        String publicIp = resp.optString("publicIp", "").trim();
        if (!publicIp.isEmpty() && !"null".equalsIgnoreCase(publicIp)) {
            return true;
        }
        JSONObject mappings = resp.optJSONObject("portMappings");
        if (mappings != null && mappings.length() > 0) {
            return true;
        }
        // Bonus si un jour runtime GraphQL-like apparaît dans la réponse
        JSONObject runtime = resp.optJSONObject("runtime");
        if (runtime != null) {
            if (runtime.optLong("uptimeInSeconds", 0L) > 0L) return true;
            JSONArray ports = runtime.optJSONArray("ports");
            if (ports != null && ports.length() > 0) return true;
        }
        return false;
    }

    @Override
    public void terminatePod(String apiKey, String podId) throws Exception {
        Map<String, String> headers = auth(apiKey);
        // DELETE = terminate (stop ne fait que pause et facture encore le disque local)
        HttpJson.delete(REST + "/pods/" + podId, headers);
    }

    @Override
    public boolean isOllamaReachable(String ollamaUrl, String orionToken) throws Exception {
        JSONObject tags = fetchTags(ollamaUrl, orionToken);
        return tags != null && tags.has("models");
    }

    @Override
    public boolean isOllamaReady(String ollamaUrl, String orionToken) throws Exception {
        JSONObject tags = fetchTags(ollamaUrl, orionToken);
        if (tags == null) return false;
        // Pas seulement « Ollama up » : il faut au moins un modèle utilisable
        // (sinon /api/generate renvoie 404 model not found).
        return OrionOllamaClient.hasUsableModel(OrionOllamaClient.modelNamesFromTags(tags));
    }

    /** Un seul GET /api/tags pour {@link #isOllamaReachable} et {@link #isOllamaReady}. */
    private static JSONObject fetchTags(String ollamaUrl, String orionToken) throws Exception {
        if (TextUtils.isEmpty(ollamaUrl)) return null;
        String base = OrionOllamaClient.normalizeBase(ollamaUrl);
        Map<String, String> headers = new HashMap<>();
        if (!TextUtils.isEmpty(orionToken)) {
            headers.put("Authorization", "Bearer " + orionToken);
        }
        return HttpJson.get(base + "/api/tags", headers, 8_000, 15_000);
    }

    /** Convertit le payload Orion (startupCommand + env[] ) → REST Create Pod. */
    static JSONObject toRestCreateBody(JSONObject orionPayload) throws Exception {
        JSONObject rest = new JSONObject();
        rest.put("name", orionPayload.optString("name", "orion"));
        String volumeId = orionPayload.optString("networkVolumeId", "").trim();
        if (volumeId.isEmpty()) {
            volumeId = OrionConfig.DEFAULT_NETWORK_VOLUME_ID;
        }
        rest.put("networkVolumeId", volumeId);
        rest.put("gpuCount", orionPayload.optInt("gpuCount", 1));
        rest.put("volumeMountPath", "/workspace");
        JSONArray ports = orionPayload.optJSONArray("ports");
        if (ports == null || ports.length() == 0) {
            // Défaut Orion (rétrocompat) — Comfy passe ports explicites
            boolean comfy = "comfy".equalsIgnoreCase(orionPayload.optString("name", ""))
                    || orionPayload.optString("startupCommand", "")
                    .contains("setup-comfy");
            ports = comfy ? comfyPorts() : orionPorts();
        }
        rest.put("ports", ports);
        // Orion : 11434 Ollama · 11435 proxy · 8080 code-server · 3000 fileserver
        // Comfy : 3000 fileserver · 8188 ComfyUI · 8189 list_outputs
        rest.put("containerDiskInGb", orionPayload.optInt("containerDiskInGb", 20));

        String dataCenterId = orionPayload.optString("dataCenterId", "").trim();
        if (!dataCenterId.isEmpty()) {
            // Force le pod dans la région du volume
            rest.put("dataCenterIds", new JSONArray().put(dataCenterId));
            rest.put("dataCenterPriority", "custom");
        }

        String gpuTypeId = orionPayload.optString("gpuTypeId", "");
        if (gpuTypeId.isEmpty() && orionPayload.optJSONArray("gpuTypeIds") != null
                && orionPayload.getJSONArray("gpuTypeIds").length() > 0) {
            gpuTypeId = orionPayload.getJSONArray("gpuTypeIds").getString(0);
        }
        if (!gpuTypeId.isEmpty()) {
            rest.put("gpuTypeIds", new JSONArray().put(gpuTypeId));
        }

        String image = orionPayload.optString("imageName", OrionConfig.DEFAULT_IMAGE);
        rest.put("imageName", image);

        String cmd = orionPayload.optString("startupCommand", OrionConfig.STARTUP_COMMAND);
        JSONArray dockerCmd = new JSONArray();
        for (String part : cmd.trim().split("\\s+")) {
            if (!part.isEmpty()) dockerCmd.put(part);
        }
        rest.put("dockerStartCmd", dockerCmd);

        JSONObject envObj = new JSONObject();
        JSONArray envArr = orionPayload.optJSONArray("env");
        if (envArr != null) {
            for (int i = 0; i < envArr.length(); i++) {
                JSONObject e = envArr.optJSONObject(i);
                if (e == null) continue;
                envObj.put(e.optString("key"), e.optString("value", ""));
            }
        } else if (orionPayload.optJSONObject("env") != null) {
            envObj = orionPayload.getJSONObject("env");
        }
        rest.put("env", envObj);
        return rest;
    }

    /** Ports HTTP/TCP exposés pour un pod Orion (Ollama + preview). */
    static JSONArray orionPorts() {
        return new JSONArray()
                .put("11434/http")
                .put("11435/http")
                .put("8080/http")
                .put("3000/http")
                .put("22/tcp");
    }

    /** Ports pour un pod Comfy — pas d'Ollama / code-server. */
    static JSONArray comfyPorts() {
        return new JSONArray()
                .put("3000/http")
                .put("8188/http")
                .put("8189/http")
                .put("22/tcp");
    }

    private static Map<String, String> auth(String apiKey) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        return h;
    }

    private static float firstPositive(float... vals) {
        for (float v : vals) {
            if (v > 0f) return v;
        }
        return 0f;
    }
}
