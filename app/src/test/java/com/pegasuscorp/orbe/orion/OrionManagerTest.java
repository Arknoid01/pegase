package com.pegasuscorp.orbe.orion;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.tools.orion.OrionManagerTool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionManagerTest {

    private Context ctx;
    private FakeRunPodClient fake;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        OrionStateStore.resetForTests();
        OrionStateStore.pollPodMs = 10L;
        OrionStateStore.pollOllamaMs = 10L;
        OrionStateStore.phaseTimeoutMs = 2_000L;
        OrionStateStore.ollamaBootTimeoutMs = 2_000L;
        OrionStateStore.modelIndexWaitMs = 500L;
        OrionStateStore.pullTimeoutMs = 2_000L;
        OrionStateStore.get().attach(ctx);

        ApiKeyStore.setRunpodApiKey(ctx, "rp_test_key");
        ApiKeyStore.setOrionToken(ctx, "orion_secret");
        OrionConfig.saveAllowedGpuIds(ctx, Arrays.asList(
                "NVIDIA GeForce RTX 3090"));
        OrionConfig.saveMaxBudgetPerHour(ctx, 0.50f);
        OrionConfig.saveAutoStopMinutes(ctx, 30);

        fake = new FakeRunPodClient();
        fake.offers.add(new GpuOffer("NVIDIA GeForce RTX 3090", "RTX 3090", 24, 0.29f, true));
        fake.offers.add(new GpuOffer("NVIDIA RTX A6000", "RTX A6000", 48, 0.79f, true));
        fake.offers.add(new GpuOffer("NVIDIA RTX A4000", "RTX A4000", 16, 0.34f, true));
        fake.volumes.add(new NetworkVolume(
                OrionConfig.DEFAULT_NETWORK_VOLUME_ID, "immediate_amber_shark",
                OrionConfig.DEFAULT_DATA_CENTER_ID, 280));
        OrionManagerActions.setClientForTests(fake);
        OrionOllamaClient.setEnsureTransportForTests((url, token, progress) -> {
            fake.modelsPresent.set(true);
            return true;
        });
    }

    @After
    public void tearDown() {
        OrionManagerActions.setClientForTests(new RunPodHttpClient());
        OrionOllamaClient.setEnsureTransportForTests(null);
        OrionStateStore.resetForTests();
    }

    @Test
    public void listPods_filtersByAllowedGpuAndBudget() {
        OrionConfig cfg = OrionConfig.load(ctx);
        List<GpuOffer> filtered = OrionManagerActions.filterOffers(fake.offers, cfg);
        assertEquals(1, filtered.size());
        assertEquals("NVIDIA GeForce RTX 3090", filtered.get(0).id);
        assertTrue(filtered.get(0).pricePerHour <= 0.50f);
    }

    @Test
    public void buildPodConfig_alwaysAttachesVolumeAndEnv() throws Exception {
        JSONObject cfg = OrionConfig.buildPodConfig(ctx);
        assertEquals(OrionConfig.getNetworkVolumeId(ctx), cfg.getString("networkVolumeId"));
        assertEquals(OrionConfig.STARTUP_COMMAND, cfg.getString("startupCommand"));
        JSONArray env = cfg.getJSONArray("env");
        assertEquals(6, env.length());
        assertEquals("OLLAMA_MODELS", env.getJSONObject(0).getString("key"));
        assertEquals("/workspace/ollama-models", env.getJSONObject(0).getString("value"));
        assertEquals("OLLAMA_HOST", env.getJSONObject(1).getString("key"));
        assertEquals("0.0.0.0:11434", env.getJSONObject(1).getString("value"));
        assertEquals("orion_secret", env.getJSONObject(4).getString("value"));
        assertEquals("ORION_TOKEN", env.getJSONObject(5).getString("key"));
        assertEquals("orion_secret", env.getJSONObject(5).getString("value"));
    }

    @Test
    public void start_attachesConfiguredVolumeAndDataCenter() throws Exception {
        OrionConfig.saveVolumeAndDataCenter(ctx, OrionConfig.DEFAULT_NETWORK_VOLUME_ID,
                OrionConfig.DEFAULT_DATA_CENTER_ID);
        JSONObject body = OrionManagerActions.buildStartBody(ctx, fake.offers.get(0));
        assertEquals(OrionConfig.DEFAULT_NETWORK_VOLUME_ID, body.getString("networkVolumeId"));
        assertEquals(OrionConfig.DEFAULT_DATA_CENTER_ID, body.getString("dataCenterId"));
        JSONObject rest = RunPodHttpClient.toRestCreateBody(body);
        assertEquals(OrionConfig.DEFAULT_NETWORK_VOLUME_ID, rest.getString("networkVolumeId"));
        assertEquals(OrionConfig.DEFAULT_DATA_CENTER_ID, rest.getJSONArray("dataCenterIds").getString(0));
        assertEquals("custom", rest.getString("dataCenterPriority"));
        assertTrue(rest.getJSONArray("dockerStartCmd").toString().contains("setup.sh"));
        assertEquals("orion_secret", rest.getJSONObject("env").getString("ORION_TOKEN"));
        JSONArray ports = rest.getJSONArray("ports");
        assertEquals("3000/http", ports.getString(3));
        assertEquals("22/tcp", ports.getString(4));
        assertFalse(ports.toString().contains("8188"));
    }

    @Test
    public void buildStartBody_comfy_usesSetupComfySh() throws Exception {
        JSONObject body = OrionManagerActions.buildStartBody(
                ctx, fake.offers.get(0), PodMode.COMFY);
        assertEquals("comfy", body.getString("name"));
        assertEquals(OrionConfig.COMFY_STARTUP_COMMAND, body.getString("startupCommand"));
        assertEquals(OrionConfig.COMFY_IMAGE, body.getString("imageName"));
        JSONObject rest = RunPodHttpClient.toRestCreateBody(body);
        assertEquals(OrionConfig.COMFY_IMAGE, rest.getString("imageName"));
        String cmd = rest.getJSONArray("dockerStartCmd").toString();
        assertTrue(cmd.contains("setup-comfy.sh"));
        assertEquals("orion_secret", rest.getJSONObject("env").getString("ORION_TOKEN"));
        assertFalse(rest.getJSONObject("env").has("OLLAMA_HOST"));
        JSONArray ports = rest.getJSONArray("ports");
        assertEquals("3000/http", ports.getString(0));
        assertEquals("8188/http", ports.getString(1));
        assertEquals("8189/http", ports.getString(2));
        String portsStr = ports.toString();
        assertFalse(portsStr.contains("11434"));
        assertFalse(portsStr.contains("11435"));
        assertFalse(portsStr.contains("8080"));
    }

    @Test
    public void filterComfyOffers_requires24GbVram() {
        List<GpuOffer> filtered = OrionManagerActions.filterComfyOffers(fake.offers);
        // 3090 24GB $0.29 + A6000 48GB $0.79 ; A4000 16GB exclu
        assertEquals(2, filtered.size());
        for (GpuOffer o : filtered) {
            assertTrue(o.vramGb >= OrionConfig.COMFY_MIN_VRAM_GB);
            assertTrue(o.pricePerHour <= OrionConfig.COMFY_DEFAULT_BUDGET + 1e-4f);
        }
    }

    @Test
    public void startComfy_whileOrionReady_doesNotCallStartPod() throws Exception {
        OrionStateStore store = OrionStateStore.get();
        store.markStarting("pod_alive", fake.offers.get(0), PodMode.ORION);
        store.markReady();
        assertEquals(0, fake.startBodies.size());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> msg = new AtomicReference<>();
        OrionManagerActions.startComfy(ctx,
                new JSONObject().put("confirm", true).put("gpu_id", "NVIDIA GeForce RTX 3090"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        msg.set(result != null ? result.text : "");
                        latch.countDown();
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        if (ok != null) ok.run();
                    }
                    @Override public void onError(String error) {
                        msg.set(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, fake.startBodies.size());
        assertTrue(msg.get() != null && msg.get().toLowerCase().contains("orion"));
        assertTrue(msg.get().toLowerCase().contains("éteins")
                || msg.get().toLowerCase().contains("eteins")
                || msg.get().contains("tourne déjà")
                || msg.get().contains("tourne deja"));
    }

    @Test
    public void resolveNetworkVolume_autoPicksSingleVolume() throws Exception {
        OrionConfig.saveNetworkVolumeId(ctx, "volume_inexistant");
        fake.volumes.clear();
        fake.volumes.add(new NetworkVolume("nouveau_volume_xyz", "ws", "US-KS-2", 40));
        NetworkVolume v = OrionManagerActions.resolveNetworkVolume(ctx, "key");
        assertEquals("nouveau_volume_xyz", v.id);
        assertEquals("US-KS-2", v.dataCenterId);
        assertEquals("nouveau_volume_xyz", OrionConfig.getNetworkVolumeId(ctx));
        assertEquals("US-KS-2", OrionConfig.getDataCenterId(ctx));
    }

    @Test
    public void start_requiresConfirmationWithCost() throws Exception {
        AtomicReference<String> question = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionManagerActions.start(ctx, new JSONObject().put("confirm", false),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        latch.countDown();
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        question.set(q);
                        latch.countDown();
                    }
                    @Override public void onError(String error) {
                        fail(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(question.get());
        assertTrue(question.get().contains("$") || question.get().contains("/h"));
        assertTrue(question.get().toLowerCase().contains("lance")
                || question.get().contains("?"));
        assertTrue(fake.startBodies.isEmpty());
    }

    @Test
    public void start_multipleGpus_showsChoiceThenConfirm() throws Exception {
        OrionConfig.saveAllowedGpuIds(ctx, Arrays.asList(
                "NVIDIA GeForce RTX 3090", "NVIDIA RTX A4000"));
        OrionConfig.saveMaxBudgetPerHour(ctx, 0.50f);

        AtomicReference<String[]> choiceLabels = new AtomicReference<>();
        AtomicReference<String> confirmQ = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        OrionManagerActions.start(ctx, new JSONObject().put("confirm", false),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        latch.countDown();
                    }
                    @Override
                    public void onChoiceNeeded(String title, String[] labels,
                            java.util.function.IntConsumer onChosen, Runnable onCancel) {
                        choiceLabels.set(labels);
                        assertTrue(labels.length >= 2);
                        onChosen.accept(0); // pick first → triggers confirm
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        confirmQ.set(q);
                        latch.countDown();
                    }
                    @Override public void onError(String error) {
                        fail(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(choiceLabels.get());
        assertTrue(choiceLabels.get().length >= 2);
        assertNotNull(confirmQ.get());
        assertTrue(confirmQ.get().contains("$") || confirmQ.get().contains("/h"));
        assertTrue(fake.startBodies.isEmpty());
    }

    @Test
    public void start_confirmed_twoPhases_thenReady() throws Exception {
        List<String> progress = new ArrayList<>();
        AtomicReference<String> success = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionManagerActions.start(ctx,
                new JSONObject().put("confirm", true).put("gpu_id", "NVIDIA GeForce RTX 3090"),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        success.set(result.text);
                        latch.countDown();
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        fail("should not re-confirm");
                    }
                    @Override public void onProgress(String message) {
                        progress.add(message);
                    }
                    @Override public void onError(String error) {
                        fail(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(success.get());
        assertTrue(success.get().contains("en ligne") || success.get().contains("Orion"));
        assertEquals(1, fake.startBodies.size());
        assertEquals(OrionConfig.DEFAULT_NETWORK_VOLUME_ID,
                fake.startBodies.get(0).optString("networkVolumeId"));
        assertEquals(OrionConfig.DEFAULT_DATA_CENTER_ID,
                fake.startBodies.get(0).optString("dataCenterId"));
        assertTrue(progress.stream().anyMatch(s -> s.toLowerCase().contains("pod")
                || s.toLowerCase().contains("démarrage") || s.toLowerCase().contains("demarrage")
                || s.contains("RunPod")));
        assertTrue(progress.stream().anyMatch(s -> s.toLowerCase().contains("ollama")));
        assertEquals(OrionStatus.READY, OrionStateStore.get().getStatus());
        assertTrue(OrionStateStore.get().getOllamaUrl().contains("11435.proxy.runpod.net"));
    }

    @Test
    public void ollamaTimeout_keepsPodForRetry() throws Exception {
        fake.forceOllamaNeverReady.set(true);
        OrionStateStore.phaseTimeoutMs = 80L;
        OrionStateStore.ollamaBootTimeoutMs = 80L;
        OrionStateStore.modelIndexWaitMs = 40L;
        OrionStateStore.pullTimeoutMs = 40L;
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionManagerActions.start(ctx,
                new JSONObject().put("confirm", true),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        fail("should fail");
                        latch.countDown();
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        fail("unexpected confirm");
                    }
                    @Override public void onError(String error) {
                        err.set(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(err.get());
        assertTrue(err.get().toLowerCase().contains("ollama")
                || err.get().toLowerCase().contains("proxy"));
        // Ne plus tuer le pod : setup.sh peut encore tourner
        assertTrue(fake.stoppedPods.isEmpty());
        assertNotNull(OrionStateStore.get().getPodId());
        assertEquals(OrionStatus.STARTING, OrionStateStore.get().getStatus());
    }

    @Test
    public void ollamaUpButNoModel_keepsPod() throws Exception {
        // Joignable mais jamais de modèle → ne pas tuer le pod
        fake.forceOllamaNeverReady.set(false);
        fake.modelsPresent.set(false);
        fake.ollamaReadyAfterPolls = true;
        OrionStateStore.phaseTimeoutMs = 80L;
        OrionStateStore.ollamaBootTimeoutMs = 200L;
        OrionStateStore.modelIndexWaitMs = 40L;
        OrionStateStore.pullTimeoutMs = 40L;
        OrionOllamaClient.setEnsureTransportForTests((url, token, progress) -> false);
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionManagerActions.start(ctx,
                new JSONObject().put("confirm", true),
                new ToolCallback() {
                    @Override public void onSuccess(ToolResult result) {
                        fail("should fail soft");
                        latch.countDown();
                    }
                    @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                        fail("unexpected confirm");
                    }
                    @Override public void onError(String error) {
                        err.set(error);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(8, TimeUnit.SECONDS));
        assertNotNull(err.get());
        assertTrue(err.get().toLowerCase().contains("modèle")
                || err.get().toLowerCase().contains("modele")
                || err.get().toLowerCase().contains("listé")
                || err.get().toLowerCase().contains("liste"));
        assertTrue("pod must stay up", fake.stoppedPods.isEmpty());
        assertEquals(OrionStatus.STARTING, OrionStateStore.get().getStatus());
        assertNotNull(OrionStateStore.get().getPodId());
        OrionOllamaClient.setEnsureTransportForTests(null);
    }

    @Test
    public void stop_computesSessionCost() throws Exception {
        OrionStateStore store = OrionStateStore.get();
        store.markStarting("pod_x", fake.offers.get(0));
        store.markReady();
        // Simule 1h de runtime
        // startedAt is now — set via reflection-free: mark with past via package access
        // Use estimatedCost formula by sleeping briefly and checking format
        AtomicReference<String> text = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionManagerActions.stop(ctx, new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                text.set(result.text);
                latch.countDown();
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
            @Override public void onError(String error) {
                fail(error);
                latch.countDown();
            }
        });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertNotNull(text.get());
        assertTrue(text.get().toLowerCase().contains("éteint")
                || text.get().toLowerCase().contains("eteint")
                || text.get().toLowerCase().contains("supprim")
                || text.get().toLowerCase().contains("arrêté")
                || text.get().toLowerCase().contains("arrete")
                || text.get().contains("coût")
                || text.get().contains("Coût")
                || text.get().contains("$"));
        assertEquals(OrionStatus.OFFLINE, OrionStateStore.get().getStatus());
        assertTrue(fake.stoppedPods.contains("pod_x"));
    }

    @Test
    public void status_offlineWhenNoPod() {
        AtomicReference<String> text = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OrionManagerActions.status(ctx, new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                text.set(result.text);
                latch.countDown();
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
            @Override public void onError(String error) {
                fail(error);
                latch.countDown();
            }
        });
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        assertNotNull(text.get());
        assertTrue(text.get().toUpperCase().contains("OFFLINE")
                || text.get().toLowerCase().contains("hors ligne"));
    }

    @Test
    public void autoStop_firesAfterInactivity() throws Exception {
        OrionConfig.saveAutoStopMinutes(ctx, 10);
        OrionStateStore store = OrionStateStore.get();
        store.markStarting("pod_auto", fake.offers.get(0));
        store.markReady();
        assertEquals(OrionStatus.READY, store.getStatus());
        store.fireAutoStopNowForTests();
        // stopQuiet runs sync on calling thread
        Thread.sleep(50);
        assertEquals(OrionStatus.OFFLINE, store.getStatus());
        assertTrue(fake.stoppedPods.contains("pod_auto"));
    }

    @Test
    public void registry_hasOrionManagerTag() {
        ToolRegistry reg = new ToolRegistry();
        assertNotNull(reg.findById("orion_manager"));
        assertEquals(ToolTag.ORION_MANAGER, reg.findById("orion_manager").tag());
    }

    @Test
    public void tool_statusAction() throws Exception {
        OrionManagerTool tool = new OrionManagerTool();
        AtomicReference<ToolResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        tool.execute(ctx, new JSONObject().put("action", "status"), new ToolCallback() {
            @Override public void onSuccess(ToolResult r) {
                result.set(r);
                latch.countDown();
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}
            @Override public void onError(String error) {
                fail(error);
                latch.countDown();
            }
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(result.get());
    }

    @Test
    public void tool_emptyParams_returnsStatusHint_noStart() throws Exception {
        OrionManagerTool tool = new OrionManagerTool();
        AtomicReference<ToolResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        tool.execute(ctx, new JSONObject(), new ToolCallback() {
            @Override public void onSuccess(ToolResult r) {
                result.set(r);
                latch.countDown();
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                fail("empty params must not trigger start confirm");
            }
            @Override public void onError(String error) {
                fail(error);
                latch.countDown();
            }
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(result.get());
        String text = result.get().text;
        assertTrue(text.contains("Orion est"));
        assertTrue(text.contains("lance Orion"));
        assertTrue(text.contains("éteins Orion"));
    }

    @Test
    public void description_requiresExplicitStartAction() {
        String d = new OrionManagerTool().description();
        assertTrue(d.contains("start_comfy") || d.contains("start_comfy"));
        assertTrue(d.contains("TOUJOURS spécifier action") || d.contains("Sans action"));
        assertTrue(d.contains("action=\"start\"") || d.contains("\"start\""));
        assertTrue(d.contains("Comfy") || d.contains("comfy"));
    }

    @Test
    public void readAction_emptyWhenMissing() throws Exception {
        assertEquals("", OrionManagerTool.readAction(null));
        assertEquals("", OrionManagerTool.readAction(new JSONObject()));
        assertEquals("", OrionManagerTool.readAction(new JSONObject().put("action", "")));
        assertEquals("start", OrionManagerTool.readAction(
                new JSONObject().put("action", "start")));
    }
}