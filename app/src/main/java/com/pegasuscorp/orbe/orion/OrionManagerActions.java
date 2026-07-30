package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Logique métier Orion (liste / start confirmé / stop / poll 2 phases).
 */
public final class OrionManagerActions {

    private static final String TAG = "OrionManager";
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    private static final AtomicReference<RunPodClient> CLIENT =
            new AtomicReference<>(new RunPodHttpClient());

    private OrionManagerActions() {}

    public static void setClientForTests(RunPodClient client) {
        CLIENT.set(client != null ? client : new RunPodHttpClient());
    }

    public static RunPodClient client() {
        return CLIENT.get();
    }

    public static List<GpuOffer> filterOffers(List<GpuOffer> all, OrionConfig cfg) {
        List<GpuOffer> out = new ArrayList<>();
        if (all == null) return out;
        for (GpuOffer o : all) {
            if (o == null || !o.available) continue;
            if (!cfg.isGpuAllowed(o.id) && !cfg.isGpuAllowed(o.displayName)) continue;
            if (!cfg.withinBudget(o.pricePerHour)) continue;
            out.add(o);
        }
        return out;
    }

    /** GPU Comfy : VRAM ≥ {@link OrionConfig#COMFY_MIN_VRAM_GB}, budget Comfy. */
    public static List<GpuOffer> filterComfyOffers(List<GpuOffer> all) {
        List<GpuOffer> out = new ArrayList<>();
        if (all == null) return out;
        float budget = OrionConfig.COMFY_DEFAULT_BUDGET;
        for (GpuOffer o : all) {
            if (o == null || !o.available) continue;
            if (o.vramGb > 0 && o.vramGb < OrionConfig.COMFY_MIN_VRAM_GB) continue;
            if (o.pricePerHour > budget + 1e-4f) continue;
            out.add(o);
        }
        return out;
    }

    public static String formatOfferList(List<GpuOffer> offers) {
        if (offers == null || offers.isEmpty()) {
            return "Aucun pod dans ton budget actuellement.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < offers.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ").append(offers.get(i).shortLabel());
        }
        return sb.toString();
    }

    public static String confirmQuestion(GpuOffer offer) {
        return offer.shortLabel() + " — je lance ?";
    }

    /** Corps start avec volume + région + env. */
    public static JSONObject buildStartBody(Context ctx, GpuOffer offer) throws Exception {
        return buildStartBody(ctx, offer, PodMode.ORION);
    }

    public static JSONObject buildStartBody(Context ctx, GpuOffer offer, PodMode mode)
            throws Exception {
        OrionConfig cfg = OrionConfig.load(ctx);
        boolean comfy = mode == PodMode.COMFY;
        JSONObject body = comfy
                ? OrionConfig.buildComfyPodConfig(ctx)
                : OrionConfig.buildPodConfig(ctx);
        body.put("name", comfy ? "comfy" : "orion");
        body.put("gpuTypeId", offer.id);
        body.put("gpuTypeIds", new org.json.JSONArray().put(offer.id));
        // Comfy force COMFY_IMAGE (torch ≥2.4) ; Orion garde imageName configurable
        body.put("imageName", comfy ? OrionConfig.COMFY_IMAGE : cfg.imageName);
        body.put("gpuCount", 1);
        body.put("networkVolumeId", cfg.networkVolumeId);
        if (!TextUtils.isEmpty(cfg.dataCenterId)) {
            body.put("dataCenterId", cfg.dataCenterId);
        }
        return body;
    }

    /**
     * Vérifie le volume sur le compte RunPod, résout la région, met à jour la config.
     * @return volume résolu
     */
    public static NetworkVolume resolveNetworkVolume(Context ctx, String apiKey)
            throws Exception {
        OrionConfig cfg = OrionConfig.load(ctx);
        List<NetworkVolume> vols = client().listNetworkVolumes(apiKey);
        if (vols == null || vols.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun network volume sur ce compte RunPod. "
                            + "Crée-en un dans Storage (console RunPod) puis colle son ID "
                            + "dans Réglages Orion.");
        }
        NetworkVolume match = null;
        for (NetworkVolume v : vols) {
            if (cfg.networkVolumeId.equals(v.id)) {
                match = v;
                break;
            }
        }
        // Tolère le nom affiché (ex. immediate_amber_shark) au lieu de l'ID API
        if (match == null) {
            for (NetworkVolume v : vols) {
                if (cfg.networkVolumeId.equalsIgnoreCase(v.name)) {
                    match = v;
                    OrionConfig.saveVolumeAndDataCenter(ctx, v.id, v.dataCenterId);
                    Log.i(TAG, "Volume résolu par nom → id " + v.id);
                    break;
                }
            }
        }
        if (match == null) {
            if (vols.size() == 1) {
                match = vols.get(0);
                OrionConfig.saveVolumeAndDataCenter(ctx, match.id, match.dataCenterId);
                Log.i(TAG, "Volume auto-sélectionné : " + match.label());
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("Volume « ").append(cfg.networkVolumeId)
                        .append(" » introuvable sur ce compte.\nVolumes disponibles :\n");
                for (NetworkVolume v : vols) {
                    sb.append("• ").append(v.label()).append('\n');
                }
                sb.append("Copie l'ID dans Réglages → Orion → Volume réseau.");
                throw new IllegalStateException(sb.toString().trim());
            }
        } else if (!TextUtils.isEmpty(match.dataCenterId)
                && !match.dataCenterId.equals(cfg.dataCenterId)) {
            OrionConfig.saveVolumeAndDataCenter(ctx, match.id, match.dataCenterId);
        }
        return match;
    }

    public static void listPods(Context ctx, ToolCallback cb) {
        BG.execute(() -> {
            try {
                String key = ApiKeyStore.getRunpodApiKey(ctx);
                if (TextUtils.isEmpty(key)) {
                    fail(cb, "Clé API RunPod manquante — Paramètres → Services connectés → Orion.");
                    return;
                }
                OrionConfig cfg = OrionConfig.load(ctx);
                List<GpuOffer> filtered = filterOffers(client().listGpuTypes(key), cfg);
                if (filtered.isEmpty()) {
                    ok(cb, "Aucun pod dans ton budget actuellement "
                            + String.format(Locale.US, "(max $%.2f/h).", cfg.maxBudgetPerHour));
                    return;
                }
                ok(cb, "GPU dans ton budget :\n" + formatOfferList(filtered)
                        + "\n\nDis « lance Orion » puis confirme pour démarrer.");
            } catch (Exception e) {
                fail(cb, "Liste GPU impossible : " + msg(e));
            }
        });
    }

    public static void status(Context ctx, ToolCallback cb) {
        OrionStateStore store = OrionStateStore.get();
        store.attach(ctx);
        ok(cb, store.statusSummary());
    }

    /**
     * Start : sans confirm → proposes + {@link ToolCallback#onConfirmNeeded}.
     * Avec confirm=true (+ gpu_id optionnel) → démarre vraiment.
     */
    public static void start(Context ctx, JSONObject params, ToolCallback cb) {
        startWithMode(ctx, params, cb, PodMode.ORION);
    }

    /** Même parcours que {@link #start} — pod Comfy (setup-comfy.sh, VRAM ≥ 24). */
    public static void startComfy(Context ctx, JSONObject params, ToolCallback cb) {
        startWithMode(ctx, params != null ? params : new JSONObject(), cb, PodMode.COMFY);
    }

    private static void startWithMode(Context ctx, JSONObject params, ToolCallback cb,
            PodMode mode) {
        BG.execute(() -> {
            try {
                OrionStateStore store = OrionStateStore.get();
                store.attach(ctx);
                if (store.isPodBusyOrOnline()) {
                    ok(cb, store.mutualExclusionMessage(mode));
                    return;
                }
                String key = ApiKeyStore.getRunpodApiKey(ctx);
                if (TextUtils.isEmpty(key)) {
                    fail(cb, "Clé API RunPod manquante — configure Orion dans les paramètres.");
                    return;
                }

                boolean confirm = params != null && params.optBoolean("confirm", false);
                List<GpuOffer> filtered = mode == PodMode.COMFY
                        ? filterComfyOffers(client().listGpuTypes(key))
                        : filterOffers(client().listGpuTypes(key), OrionConfig.load(ctx));
                if (filtered.isEmpty()) {
                    ok(cb, mode == PodMode.COMFY
                            ? "Aucun GPU Comfy (≥" + OrionConfig.COMFY_MIN_VRAM_GB
                            + " Go, max $" + String.format(Locale.US, "%.2f",
                            OrionConfig.COMFY_DEFAULT_BUDGET) + "/h)."
                            : "Aucun pod dans ton budget actuellement.");
                    return;
                }

                GpuOffer selected = selectOffer(filtered, params, store);
                String choiceTitle = mode == PodMode.COMFY
                        ? "Choisir un GPU pour Comfy" : "Choisir un GPU pour Orion";
                String cancelMsg = mode == PodMode.COMFY
                        ? "Démarrage Comfy annulé." : "Démarrage Orion annulé.";
                if (selected == null) {
                    store.setPendingOffer(null);
                    final String apiKey = key;
                    final List<GpuOffer> offers = new ArrayList<>(filtered);
                    String[] labels = new String[offers.size()];
                    for (int i = 0; i < offers.size(); i++) {
                        labels[i] = (i + 1) + ". " + offers.get(i).shortLabel();
                    }
                    cb.onChoiceNeeded(
                            choiceTitle,
                            labels,
                            index -> {
                                if (index < 0 || index >= offers.size()) {
                                    ok(cb, "Choix GPU invalide.");
                                    return;
                                }
                                GpuOffer pick = offers.get(index);
                                store.setPendingOffer(pick);
                                store.setLoadStep(OrionLoadStep.CONFIRM, OrionLoadStep.TOTAL,
                                        "En attente de confirmation");
                                String question = confirmQuestion(pick);
                                cb.onConfirmNeeded(question,
                                        () -> BG.execute(() ->
                                                runConfirmedStart(ctx, apiKey, pick, mode, cb)),
                                        () -> {
                                            store.clearPendingOffer();
                                            store.clearLoadProgress();
                                            ok(cb, cancelMsg);
                                        });
                            },
                            () -> ok(cb, cancelMsg));
                    return;
                }

                if (!confirm) {
                    store.setPendingOffer(selected);
                    store.setLoadStep(OrionLoadStep.CONFIRM, OrionLoadStep.TOTAL,
                            "En attente de confirmation");
                    String question = confirmQuestion(selected);
                    cb.onConfirmNeeded(question,
                            () -> BG.execute(() ->
                                    runConfirmedStart(ctx, key, selected, mode, cb)),
                            () -> {
                                store.clearPendingOffer();
                                store.clearLoadProgress();
                                ok(cb, cancelMsg);
                            });
                    return;
                }

                runConfirmedStart(ctx, key, selected, mode, cb);
            } catch (Exception e) {
                String label = mode == PodMode.COMFY ? "Comfy" : "Orion";
                OrionStateStore.get().setBlocked("Démarrage " + label + " impossible : " + msg(e));
                OrionStateStore.get().markOffline();
                fail(cb, "Démarrage " + label + " impossible : " + msg(e));
            }
        });
    }

    private static GpuOffer selectOffer(List<GpuOffer> filtered, JSONObject params,
            OrionStateStore store) {
        if (filtered.size() == 1) return filtered.get(0);
        String gpuId = params != null ? params.optString("gpu_id", "").trim() : "";
        if (gpuId.isEmpty() && params != null) {
            gpuId = params.optString("gpuId", "").trim();
        }
        int index = params != null ? params.optInt("index", -1) : -1;
        if (index >= 1 && index <= filtered.size()) {
            return filtered.get(index - 1);
        }
        if (!gpuId.isEmpty()) {
            for (GpuOffer o : filtered) {
                if (gpuId.equalsIgnoreCase(o.id)
                        || o.displayName.toLowerCase(Locale.ROOT)
                        .contains(gpuId.toLowerCase(Locale.ROOT))) {
                    return o;
                }
            }
        }
        GpuOffer pending = store.getPendingOffer();
        if (pending != null) {
            for (GpuOffer o : filtered) {
                if (o.id.equals(pending.id)) return o;
            }
        }
        return null;
    }

    private static void runConfirmedStart(Context ctx, String apiKey, GpuOffer offer,
            ToolCallback cb) {
        runConfirmedStart(ctx, apiKey, offer, PodMode.ORION, cb);
    }

    private static void runConfirmedStart(Context ctx, String apiKey, GpuOffer offer,
            PodMode mode, ToolCallback cb) {
        OrionStateStore store = OrionStateStore.get();
        store.attach(ctx);
        // Garde-fou central — bouton, voix, outil (avant startPod facturé)
        if (store.isPodBusyOrOnline()) {
            ok(cb, store.mutualExclusionMessage(mode));
            return;
        }
        try {
            NetworkVolume volume = resolveNetworkVolume(ctx, apiKey);
            JSONObject body = buildStartBody(ctx, offer, mode);
            body.put("networkVolumeId", volume.id);
            if (!TextUtils.isEmpty(volume.dataCenterId)) {
                body.put("dataCenterId", volume.dataCenterId);
            }

            stepProgress(store, cb, OrionLoadStep.START_POD,
                    "Volume " + volume.id
                            + (TextUtils.isEmpty(volume.dataCenterId) ? ""
                            : " @ " + volume.dataCenterId) + "…");
            // Re-check juste avant l'API (race choix GPU)
            if (store.isPodBusyOrOnline()) {
                ok(cb, store.mutualExclusionMessage(mode));
                return;
            }
            store.markStarting("pending", offer, mode);
            String podId = client().startPod(apiKey, body);
            store.markStarting(podId, offer, mode);
            store.clearPendingOffer();
            OrionSessionArchive.openSession(ctx);

            // Phase 1 — Pod READY
            stepProgress(store, cb, OrionLoadStep.WAIT_POD, "0s");
            boolean podReady = pollPodReady(apiKey, podId, store, cb);
            if (!podReady) {
                OrionSessionArchive.closeSession(ctx);
                blockAndFail(store, cb,
                        "Le pod n'a pas démarré à temps (timeout "
                                + (OrionStateStore.phaseTimeoutMs / 1000) + "s).",
                        OrionLoadStep.WAIT_POD);
                return;
            }

            if (mode == PodMode.COMFY) {
                finishComfyReady(ctx, store, cb, podId, offer);
                return;
            }

            String url = OrionStateStore.buildOllamaUrl(podId);
            store.setOllamaUrl(url);
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA, "0s");

            // Phase 2 — Ollama joignable + modèle (pull auto si vide)
            // Essaie :11435 (proxy) puis :11434 (direct) pendant le boot setup.sh
            String token = ApiKeyStore.getOrionToken(ctx);
            String directUrl = OrionStateStore.buildOllamaDirectUrl(podId);
            int ollamaState = pollOllamaReady(url, directUrl, token, store, cb);
            // L'URL effective (proxy ou direct) a pu être écrite dans le store
            if (ollamaState == OLLAMA_READY) {
                store.markReady();
                stepProgress(store, cb, OrionLoadStep.READY, OrionOllamaClient.MODEL + " prêt");
                String msg = String.format(Locale.US,
                        "Orion en ligne ! %s prêt. %s, $%.2f/h",
                        OrionOllamaClient.MODEL, offer.displayName, offer.pricePerHour);
                ok(cb, msg);
                return;
            }

            if (ollamaState == OLLAMA_UP_NO_MODEL) {
                // Pod + Ollama OK — ne pas tuer (le pull peut finir sur le volume)
                blockKeepPod(store, cb,
                        "Ollama tourne mais « " + OrionOllamaClient.MODEL
                                + " » n'est pas encore listé.\n"
                                + "Le pod reste allumé (~$"
                                + String.format(Locale.US, "%.2f", offer.pricePerHour)
                                + "/h). Attends 5–15 min puis relance Orion "
                                + "(ou vérifie setup.sh / ORION_TOKEN sur le volume).",
                        OrionLoadStep.WAIT_OLLAMA);
                return;
            }

            // Toujours pas joignable — garder le pod (setup.sh peut encore tourner)
            // plutôt que tuer et recommencer un cold start coûteux.
            blockKeepPod(store, cb,
                    "Proxy RunPod / Ollama pas encore joignable après "
                            + (OrionStateStore.ollamaBootTimeoutMs / 60_000L)
                            + " min.\n"
                            + "Le pod reste allumé (~$"
                            + String.format(Locale.US, "%.2f", offer.pricePerHour)
                            + "/h) — setup.sh peut encore installer.\n"
                            + "Attends 2–5 min puis « lance Orion » à nouveau "
                            + "(ou vérifie ORION_TOKEN = token Pégase).",
                    OrionLoadStep.WAIT_OLLAMA);
        } catch (Exception e) {
            OrionSessionArchive.closeSession(ctx);
            String label = mode == PodMode.COMFY ? "Comfy" : "Orion";
            blockAndFail(store, cb, "Échec démarrage " + label + " : " + msg(e),
                    OrionLoadStep.START_POD);
        }
    }

    /** Phase 2 Comfy : attendre que la page ComfyUI (8188) réponde. */
    private static void finishComfyReady(Context ctx, OrionStateStore store,
            ToolCallback cb, String podId, GpuOffer offer) {
        stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA, "ComfyUI… 0s");
        String comfyUrl = OrionStateStore.buildComfyUiUrl(podId);
        boolean up = pollComfyUiReady(comfyUrl, store, cb);
        if (!up) {
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA, "Timeout ComfyUI…");
            try {
                String key = ApiKeyStore.getRunpodApiKey(ctx);
                if (!TextUtils.isEmpty(key)) client().terminatePod(key, podId);
            } catch (Exception e) {
                Log.w(TAG, "stop after ComfyUI timeout", e);
            }
            OrionSessionArchive.closeSession(ctx);
            blockAndFail(store, cb,
                    "ComfyUI pas joignable à temps (port 8188).\n"
                            + "Pod arrêté. Vérifie setup-comfy.sh / pip sur le volume.",
                    OrionLoadStep.WAIT_OLLAMA);
            return;
        }
        store.markReady();
        stepProgress(store, cb, OrionLoadStep.READY, "page 8188 OK");
        String msg = String.format(Locale.US,
                "ComfyUI en ligne ! %s, $%.2f/h\n%s",
                offer.displayName, offer.pricePerHour, comfyUrl);
        ok(cb, msg);
    }

    /** GET proxy ComfyUI :8188 — prêt quand la page répond (pas 5xx). */
    private static boolean pollComfyUiReady(String comfyUrl,
            OrionStateStore store, ToolCallback cb) {
        if (TextUtils.isEmpty(comfyUrl)) return false;
        long started = System.currentTimeMillis();
        long deadline = started + OrionStateStore.ollamaBootTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(comfyUrl + "/").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(8_000);
                conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
                int code = conn.getResponseCode();
                conn.disconnect();
                // 200 / 3xx / 4xx = proxy + ComfyUI joignables (pas 502 boot)
                if (code > 0 && code < 500) return true;
            } catch (Exception e) {
                Log.w(TAG, "poll ComfyUI", e);
            }
            long elapsedSec = (System.currentTimeMillis() - started) / 1000L;
            long leftMin = Math.max(0L,
                    (deadline - System.currentTimeMillis() + 59_000L) / 60_000L);
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                    "ComfyUI… " + elapsedSec + "s (reste ~" + leftMin + " min)");
            sleep(OrionStateStore.pollOllamaMs);
        }
        return false;
    }

    private static final int OLLAMA_FAIL = 0;
    private static final int OLLAMA_READY = 1;
    /** Tags OK mais aucun modèle utilisable (pull en cours / volume). */
    private static final int OLLAMA_UP_NO_MODEL = 2;

    private static boolean pollPodReady(String apiKey, String podId,
            OrionStateStore store, ToolCallback cb) {
        long started = System.currentTimeMillis();
        long deadline = started + OrionStateStore.phaseTimeoutMs;
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                String st = client().getPodStatus(apiKey, podId);
                if (st != null) {
                    lastStatus = st;
                    String u = st.toUpperCase(Locale.ROOT);
                    if ("EXITED".equals(u) || u.contains("TERMINAT") || u.contains("FAILED")) {
                        stepProgress(store, cb, OrionLoadStep.WAIT_POD,
                                "statut " + st);
                        return false;
                    }
                    if (u.contains("RUNNING") || u.contains("READY")) return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "poll pod", e);
                lastStatus = e.getMessage() != null ? e.getMessage() : "erreur réseau";
            }
            long elapsedSec = (System.currentTimeMillis() - started) / 1000L;
            String detail = "cold start · " + elapsedSec + "s"
                    + (TextUtils.isEmpty(lastStatus) ? "" : " · " + lastStatus);
            stepProgress(store, cb, OrionLoadStep.WAIT_POD, detail);
            sleep(OrionStateStore.pollPodMs);
        }
        return false;
    }

    /**
     * 1) Attendre Ollama joignable · 2) pull auto si aucun modèle · 3) attendre modèle prêt.
     * Essaie d'abord {@code proxyUrl} (:11435) puis {@code directUrl} (:11434) au boot.
     * @return {@link #OLLAMA_READY}, {@link #OLLAMA_UP_NO_MODEL} ou {@link #OLLAMA_FAIL}
     */
    private static int pollOllamaReady(String proxyUrl, String token,
            OrionStateStore store, ToolCallback cb) {
        return pollOllamaReady(proxyUrl, null, token, store, cb);
    }

    private static int pollOllamaReady(String proxyUrl, String directUrl, String token,
            OrionStateStore store, ToolCallback cb) {
        long started = System.currentTimeMillis();
        long reachableDeadline = started + OrionStateStore.ollamaBootTimeoutMs;
        boolean reachable = false;
        String workingUrl = proxyUrl;
        while (System.currentTimeMillis() < reachableDeadline) {
            String hit = probeOllamaUrls(proxyUrl, directUrl, token);
            if (hit != null) {
                workingUrl = hit;
                store.setOllamaUrl(hit);
                reachable = true;
                break;
            }
            long elapsedSec = (System.currentTimeMillis() - started) / 1000L;
            long left = Math.max(0L, (reachableDeadline - System.currentTimeMillis()) / 1000L);
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                    "Ollama… " + elapsedSec + "s (reste ~" + left
                            + "s · essaie :11435 puis :11434, 502 normal au boot)");
            sleep(OrionStateStore.pollOllamaMs);
        }
        if (!reachable) return OLLAMA_FAIL;

        final String url = workingUrl;

        // Laisser setup.sh indexer /workspace/ollama-models avant un pull redondant.
        long waitModelsDeadline = System.currentTimeMillis()
                + OrionStateStore.modelIndexWaitMs;
        while (System.currentTimeMillis() < waitModelsDeadline) {
            try {
                if (client().isOllamaReady(url, token)) return OLLAMA_READY;
            } catch (Exception e) {
                Log.w(TAG, "poll ollama model wait", e);
                noteAuthProgress(e, store, cb);
            }
            long elapsedSec = (System.currentTimeMillis() - started) / 1000L;
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                    "index volume… " + elapsedSec + "s");
            sleep(OrionStateStore.pollOllamaMs);
        }

        try {
            if (!client().isOllamaReady(url, token)) {
                stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                        "pull " + OrionOllamaClient.MODEL + " (~19 Go)");
                boolean ensured = OrionOllamaClient.ensurePreferredModel(url, token,
                        msg -> stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA, msg));
                if (!ensured) {
                    Log.w(TAG, "ensurePreferredModel returned false");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ensure model", e);
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                    "pull erreur : " + msg(e));
        }

        long modelDeadline = System.currentTimeMillis() + OrionStateStore.pullTimeoutMs;
        while (System.currentTimeMillis() < modelDeadline) {
            try {
                if (client().isOllamaReady(url, token)) return OLLAMA_READY;
            } catch (Exception e) {
                Log.w(TAG, "poll ollama ready", e);
            }
            long elapsedSec = (System.currentTimeMillis() - started) / 1000L;
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                    "chargement VRAM / pull… " + elapsedSec + "s");
            sleep(OrionStateStore.pollOllamaMs);
        }
        // Ollama a répondu au moins une fois → ne pas traiter comme un échec total
        return OLLAMA_UP_NO_MODEL;
    }

    /**
     * @return URL qui répond (models présent), proxy préféré, sinon null.
     */
    private static String probeOllamaUrls(String proxyUrl, String directUrl, String token) {
        if (!TextUtils.isEmpty(proxyUrl)) {
            try {
                if (client().isOllamaReachable(proxyUrl, token)) return proxyUrl;
            } catch (Exception e) {
                Log.w(TAG, "probe proxy 11435", e);
            }
        }
        if (!TextUtils.isEmpty(directUrl) && !directUrl.equals(proxyUrl)) {
            try {
                if (client().isOllamaReachable(directUrl, token)) return directUrl;
            } catch (Exception e) {
                Log.w(TAG, "probe direct 11434", e);
            }
        }
        return null;
    }

    private static void noteAuthProgress(Exception e, OrionStateStore store, ToolCallback cb) {
        if (e == null || store == null) return;
        String m = e.getMessage() != null ? e.getMessage() : "";
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("401") || low.contains("403") || low.contains("auth")) {
            stepProgress(store, cb, OrionLoadStep.WAIT_OLLAMA,
                    "401/403 — vérifie ORION_TOKEN (boot peut encore tourner)…");
        }
    }

    public static void stop(Context ctx, ToolCallback cb) {
        BG.execute(() -> stopSync(ctx, cb, false));
    }

    /** Auto-stop silencieux (pas de confirmation). */
    public static void stopQuiet(Context ctx) {
        stopSync(ctx, new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                Log.i(TAG, "auto-stop: " + (result != null ? result.text : ""));
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable cancel) {
                if (ok != null) ok.run();
            }
            @Override public void onError(String error) {
                Log.w(TAG, "auto-stop error: " + error);
            }
        }, true);
    }

    private static void stopSync(Context ctx, ToolCallback cb, boolean quiet) {
        OrionStateStore store = OrionStateStore.get();
        store.attach(ctx);
        try {
            String podId = store.getPodId();
            if (TextUtils.isEmpty(podId) || store.getStatus() == OrionStatus.OFFLINE) {
                ok(cb, "Orion est déjà hors ligne.");
                return;
            }
            float cost = store.estimatedCost();
            String gpu = store.getGpuLabel();
            String modeLabel = store.getPodMode().label();
            store.markStopping();
            String key = ApiKeyStore.getRunpodApiKey(ctx);
            if (!TextUtils.isEmpty(key)) {
                try {
                    client().terminatePod(key, podId);
                } catch (Exception e) {
                    Log.w(TAG, "terminatePod API", e);
                }
            }
            store.markOffline();
            OrionSessionArchive.closeSession(ctx);
            String msg = String.format(Locale.US,
                    "%s éteint — pod RunPod supprimé%s. Coût session ~$%.3f.\n"
                            + "Le volume réseau est conservé ; un nouveau pod sera créé au prochain lancement.",
                    modeLabel,
                    gpu != null ? " (" + gpu + ")" : "",
                    cost);
            ok(cb, msg);
        } catch (Exception e) {
            store.markOffline();
            OrionSessionArchive.closeSession(ctx);
            fail(cb, "Arrêt : " + msg(e));
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stepProgress(OrionStateStore store, ToolCallback cb,
            int step, String detail) {
        if (store != null) {
            store.setLoadStep(step, OrionLoadStep.TOTAL, detail);
        }
        PodMode mode = store != null ? store.getPodMode() : PodMode.ORION;
        String line = OrionLoadStep.line(step, OrionLoadStep.TOTAL, detail, mode);
        progress(cb, line);
    }

    private static void blockAndFail(OrionStateStore store, ToolCallback cb,
            String error, int step) {
        if (store != null) {
            store.setLoadStep(step, OrionLoadStep.TOTAL, null);
            store.setBlocked(error);
            store.markOffline();
        }
        fail(cb, error);
    }

    /** Échec soft : pod + Ollama restent (pull / index en cours). */
    private static void blockKeepPod(OrionStateStore store, ToolCallback cb,
            String error, int step) {
        if (store != null) {
            store.setLoadStep(step, OrionLoadStep.TOTAL, null);
            store.setBlocked(error);
            // Garde STARTING + podId / ollamaUrl pour réessai sans recréer
            store.markWaitingModel();
        }
        fail(cb, error);
    }

    private static void progress(ToolCallback cb, String msg) {
        if (cb != null) cb.onProgress(msg);
    }

    private static void ok(ToolCallback cb, String text) {
        if (cb != null) cb.onSuccess(ToolResult.text(text));
    }

    private static void fail(ToolCallback cb, String err) {
        if (cb != null) cb.onError(err);
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
