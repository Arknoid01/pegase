package com.pegasuscorp.orbe.orion;

import org.json.JSONObject;

import java.util.List;

/** Client RunPod — injectable pour tests. */
public interface RunPodClient {

    List<GpuOffer> listGpuTypes(String apiKey) throws Exception;

    /** Volumes réseau du compte (id + dataCenterId). */
    List<NetworkVolume> listNetworkVolumes(String apiKey) throws Exception;

    /**
     * Déploie / démarre un pod avec networkVolumeId (+ dataCenterId recommandé).
     * @return podId
     */
    String startPod(String apiKey, JSONObject podBody) throws Exception;

    /** Statut réel (RUNNING / STARTING / EXITED…) — pas seulement desiredStatus. */
    String getPodStatus(String apiKey, String podId) throws Exception;

    /**
     * Termine / supprime le pod (DELETE) — pas un simple stop/pause.
     * Le volume réseau /workspace est conservé.
     */
    void terminatePod(String apiKey, String podId) throws Exception;

    /** GET {ollamaUrl}/api/tags — Ollama joignable (liste modèles éventuellement vide). */
    boolean isOllamaReachable(String ollamaUrl, String orionToken) throws Exception;

    /** Ollama joignable ET au moins un modèle utilisable. */
    boolean isOllamaReady(String ollamaUrl, String orionToken) throws Exception;
}
