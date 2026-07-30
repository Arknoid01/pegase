package com.pegasuscorp.orbe.orion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Client RunPod factice pour tests unitaires. */
public final class FakeRunPodClient implements RunPodClient {

    public final List<GpuOffer> offers = new ArrayList<>();
    public final List<NetworkVolume> volumes = new ArrayList<>();
    public final List<JSONObject> startBodies = new CopyOnWriteArrayList<>();
    public final List<String> stoppedPods = new CopyOnWriteArrayList<>();
    public final List<String> terminatedPods = stoppedPods; // alias tests
    public final AtomicInteger statusPolls = new AtomicInteger();
    public final AtomicInteger ollamaPolls = new AtomicInteger();

    public String nextPodId = "pod_test_abc";
    public String podStatusSequence = "STARTING,STARTING,RUNNING";
    public boolean ollamaReadyAfterPolls = true;
    public int ollamaFailCount = 0;
    public final AtomicBoolean forceOllamaNeverReady = new AtomicBoolean(false);
    /** Si false : Ollama joignable mais sans modèle (déclenche ensure/pull). */
    public final AtomicBoolean modelsPresent = new AtomicBoolean(true);

    private int statusIdx = 0;
    private int ollamaFailsDone = 0;

    @Override
    public List<GpuOffer> listGpuTypes(String apiKey) {
        return new ArrayList<>(offers);
    }

    @Override
    public List<NetworkVolume> listNetworkVolumes(String apiKey) {
        return new ArrayList<>(volumes);
    }

    @Override
    public String startPod(String apiKey, JSONObject podBody) {
        startBodies.add(podBody);
        return nextPodId;
    }

    @Override
    public String getPodStatus(String apiKey, String podId) {
        statusPolls.incrementAndGet();
        String[] seq = podStatusSequence.split(",");
        if (statusIdx >= seq.length) return seq[seq.length - 1].trim();
        return seq[statusIdx++].trim();
    }

    @Override
    public void terminatePod(String apiKey, String podId) {
        stoppedPods.add(podId);
    }

    @Override
    public boolean isOllamaReachable(String ollamaUrl, String orionToken) {
        ollamaPolls.incrementAndGet();
        return computeReachableConsumingFails();
    }

    @Override
    public boolean isOllamaReady(String ollamaUrl, String orionToken) {
        ollamaPolls.incrementAndGet();
        if (forceOllamaNeverReady.get()) return false;
        // Ne pas re-consommer ollamaFailCount : le poll « reachable » l'a déjà fait.
        if (ollamaFailsDone < ollamaFailCount) return false;
        return ollamaReadyAfterPolls && modelsPresent.get();
    }

    private boolean computeReachableConsumingFails() {
        if (forceOllamaNeverReady.get()) return false;
        if (ollamaFailsDone < ollamaFailCount) {
            ollamaFailsDone++;
            return false;
        }
        return ollamaReadyAfterPolls;
    }
}
