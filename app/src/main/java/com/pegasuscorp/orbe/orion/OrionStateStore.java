package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * État runtime Orion — singleton process (survit aux rotations d'écran).
 * Persiste podId / url / status pour survie process kill légère.
 */
public final class OrionStateStore {

    public interface Observer {
        void onOrionStateChanged(OrionStatus status);
    }

    private static final String TAG = "OrionStateStore";
    private static final String PREFS = "orion_runtime";
    private static final String KEY_POD = "pod_id";
    private static final String KEY_URL = "ollama_url";
    private static final String KEY_STATUS = "status";
    private static final String KEY_STARTED = "started_at";
    private static final String KEY_PRICE = "price_per_hour";
    private static final String KEY_GPU = "gpu_label";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_ERROR_AT = "last_error_at";
    private static final String KEY_POD_MODE = "pod_mode";

    private static final AtomicReference<OrionStateStore> INSTANCE = new AtomicReference<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private Runnable autoStopRunnable;

    private String podId;
    private String ollamaUrl;
    private OrionStatus status = OrionStatus.OFFLINE;
    private PodMode podMode = PodMode.ORION;
    private long startedAt;
    private float pricePerHour;
    private String gpuLabel;
    private GpuOffer pendingOffer;
    private String statusDetail;
    private int loadStep;
    private int loadStepTotal;
    private String lastError;
    private volatile Context appContext;

    /** Intervalle poll / tests — overridable. */
    public static volatile long pollPodMs = 5_000L;
    public static volatile long pollOllamaMs = 3_000L;
    public static volatile long phaseTimeoutMs = 120_000L;
    /**
     * Ollama derrière le proxy RunPod (cold start) — plus long que le pod.
     * Phase 1 (pod) ne bloque plus à tort sur desiredStatus ; la phase 2 doit
     * couvrir le cold start complet du conteneur (pull image, apt-get, install
     * Ollama + code-server via setup.sh).
     */
    public static volatile long ollamaBootTimeoutMs = 12L * 60L * 1000L;
    /** Attente index volume avant de lancer un pull. */
    public static volatile long modelIndexWaitMs = 2L * 60L * 1000L;
    /** Pull modèle ~19 Go — distinct du poll Ollama. */
    public static volatile long pullTimeoutMs = 45L * 60L * 1000L;

    private OrionStateStore() {}

    public static OrionStateStore get() {
        OrionStateStore s = INSTANCE.get();
        if (s == null) {
            s = new OrionStateStore();
            if (!INSTANCE.compareAndSet(null, s)) {
                s = INSTANCE.get();
            }
        }
        return s;
    }

    /** Tests. */
    public static void resetForTests() {
        OrionStateStore s = INSTANCE.getAndSet(null);
        if (s != null) {
            s.cancelAutoStop();
            s.observers.clear();
            s.clearMemory();
        }
        pollPodMs = 5_000L;
        pollOllamaMs = 3_000L;
        phaseTimeoutMs = 120_000L;
        ollamaBootTimeoutMs = 12L * 60L * 1000L;
        modelIndexWaitMs = 2L * 60L * 1000L;
        pullTimeoutMs = 45L * 60L * 1000L;
    }

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public synchronized void attach(Context ctx) {
        if (ctx == null) return;
        appContext = ctx.getApplicationContext();
        loadPrefs(appContext);
    }

    public synchronized String getPodId() {
        return podId;
    }

    public synchronized String getOllamaUrl() {
        return ollamaUrl;
    }

    /** URL VS Code / code-server (port 8080). */
    public synchronized String getCodeServerUrl() {
        return buildCodeServerUrl(podId);
    }

    /** URL serveur fichiers / preview live (port 3000). */
    public synchronized String getFileServerUrl() {
        return buildFileServerUrl(podId);
    }

    /** URL ComfyUI (port 8188) — reconstruite à la volée depuis le podId courant. */
    public synchronized String getComfyUiUrl() {
        return buildComfyUiUrl(podId);
    }

    public synchronized OrionStatus getStatus() {
        return status != null ? status : OrionStatus.OFFLINE;
    }

    public synchronized PodMode getPodMode() {
        return podMode != null ? podMode : PodMode.ORION;
    }

    public synchronized void setPodMode(PodMode mode) {
        this.podMode = mode != null ? mode : PodMode.ORION;
        persist();
    }

    /**
     * True si un pod (Orion ou Comfy) est déjà en cours / en ligne.
     * Garde-fou anti double facturation — tous déclencheurs.
     */
    public synchronized boolean isPodBusyOrOnline() {
        return status == OrionStatus.READY
                || status == OrionStatus.STARTING
                || status == OrionStatus.BUSY
                || status == OrionStatus.STOPPING;
    }

    /** Message d'exclusion selon le mode déjà actif. */
    public synchronized String mutualExclusionMessage(PodMode requested) {
        PodMode current = getPodMode();
        String other = current.label();
        String want = requested != null ? requested.label() : "pod";
        return other + " tourne déjà (" + status + "). Éteins " + other
                + " avant de lancer " + want + ".";
    }

    public synchronized String getStatusDetail() {
        return statusDetail;
    }

    /** Étape courante (0 = aucune). */
    public synchronized int getLoadStep() {
        return loadStep;
    }

    public synchronized int getLoadStepTotal() {
        return loadStepTotal;
    }

    /** Dernière erreur de démarrage (sticky jusqu'au prochain start réussi). */
    public synchronized String getLastError() {
        return lastError;
    }

    /**
     * Ligne UI / orale : étape + détail, ou erreur sticky si hors ligne.
     */
    public synchronized String progressLine() {
        PodMode mode = getPodMode();
        if (!TextUtils.isEmpty(lastError) && (status == OrionStatus.OFFLINE
                || status == OrionStatus.STARTING)) {
            String stepBit = loadStep > 0
                    ? OrionLoadStep.line(loadStep, Math.max(loadStepTotal, OrionLoadStep.TOTAL),
                    null, mode)
                    + "\n"
                    : "";
            return stepBit + "⚠ Bloqué : " + lastError;
        }
        if (loadStep > 0 && status == OrionStatus.STARTING) {
            return OrionLoadStep.line(loadStep, Math.max(loadStepTotal, OrionLoadStep.TOTAL),
                    statusDetail, mode);
        }
        return statusDetail;
    }

    public synchronized long getStartedAt() {
        return startedAt;
    }

    public synchronized float getPricePerHour() {
        return pricePerHour;
    }

    public synchronized String getGpuLabel() {
        return gpuLabel;
    }

    public synchronized float estimatedCost() {
        if (startedAt <= 0 || pricePerHour <= 0) return 0f;
        float hours = (System.currentTimeMillis() - startedAt) / 3_600_000f;
        return Math.max(0f, hours * pricePerHour);
    }

    public synchronized long uptimeMs() {
        if (startedAt <= 0) return 0L;
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    public synchronized String formatUptime() {
        long ms = uptimeMs();
        long sec = ms / 1000L;
        long min = sec / 60L;
        long hrs = min / 60L;
        sec %= 60;
        min %= 60;
        if (hrs > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hrs, min, sec);
        }
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    public synchronized void setPendingOffer(GpuOffer offer) {
        this.pendingOffer = offer;
    }

    public synchronized GpuOffer getPendingOffer() {
        return pendingOffer;
    }

    public synchronized void clearPendingOffer() {
        pendingOffer = null;
    }

    public void setStatusDetail(String detail) {
        synchronized (this) {
            this.statusDetail = detail;
        }
        notifyObservers();
    }

    /** Met à jour l'étape de chargement (notifie les observers). */
    public void setLoadStep(int step, int total, String detail) {
        synchronized (this) {
            this.loadStep = Math.max(0, step);
            this.loadStepTotal = Math.max(0, total);
            this.statusDetail = detail;
            if (step > 0) {
                this.lastError = null;
            }
        }
        notifyObservers();
    }

    /** Échec à une étape — garde l'étape pour l'UI « bloqué ». */
    public void setBlocked(String error) {
        synchronized (this) {
            this.lastError = error != null ? error.trim() : "Erreur Orion";
            if (!TextUtils.isEmpty(error)) {
                this.statusDetail = error.trim();
            }
        }
        persist();
        syncProjectObject();
        notifyObservers();
    }

    public void clearLoadProgress() {
        synchronized (this) {
            this.loadStep = 0;
            this.loadStepTotal = 0;
            this.statusDetail = null;
        }
        notifyObservers();
    }

    public synchronized void markStarting(String podId, GpuOffer offer) {
        markStarting(podId, offer, getPodMode());
    }

    public synchronized void markStarting(String podId, GpuOffer offer, PodMode mode) {
        this.podId = podId;
        this.podMode = mode != null ? mode : PodMode.ORION;
        this.status = OrionStatus.STARTING;
        this.statusDetail = this.podMode == PodMode.COMFY
                ? "Pod Comfy démarre..." : "Pod démarre...";
        this.lastError = null;
        this.loadStep = OrionLoadStep.START_POD;
        this.loadStepTotal = OrionLoadStep.TOTAL;
        this.startedAt = System.currentTimeMillis();
        if (offer != null) {
            this.pricePerHour = offer.pricePerHour;
            this.gpuLabel = offer.displayName;
        }
        this.ollamaUrl = this.podMode == PodMode.COMFY
                ? null : buildOllamaUrl(podId);
        persist();
        notifyObservers();
    }

    public synchronized void markReady() {
        this.status = OrionStatus.READY;
        this.statusDetail = null;
        this.lastError = null;
        this.loadStep = OrionLoadStep.READY;
        this.loadStepTotal = OrionLoadStep.TOTAL;
        if (startedAt <= 0) startedAt = System.currentTimeMillis();
        persist();
        scheduleAutoStop();
        syncProjectObject();
        notifyObservers();
        // Efface le compteur d'étapes après un court délai UI
        handler.postDelayed(this::clearLoadProgress, 2_000L);
    }

    /**
     * Ollama joignable, modèle pas encore listé — pod conservé, UI « bloqué » + STARTING.
     */
    public synchronized void markWaitingModel() {
        this.status = OrionStatus.STARTING;
        if (TextUtils.isEmpty(statusDetail) && !TextUtils.isEmpty(lastError)) {
            this.statusDetail = lastError;
        }
        persist();
        cancelAutoStop();
        syncProjectObject();
        notifyObservers();
    }

    public synchronized void markBusy() {
        if (status == OrionStatus.READY) {
            status = OrionStatus.BUSY;
            statusDetail = "Orion génère...";
            persist();
        }
        pingActivity();
        notifyObservers();
    }

    public synchronized void markStopping() {
        this.status = OrionStatus.STOPPING;
        this.statusDetail = "Arrêt…";
        persist();
        cancelAutoStop();
        notifyObservers();
    }

    public synchronized void markOffline() {
        this.podId = null;
        this.ollamaUrl = null;
        this.status = OrionStatus.OFFLINE;
        this.podMode = PodMode.ORION;
        // Conserve lastError + loadStep pour afficher le blocage
        if (TextUtils.isEmpty(lastError)) {
            this.statusDetail = null;
            this.loadStep = 0;
            this.loadStepTotal = 0;
        }
        this.startedAt = 0;
        this.pricePerHour = 0;
        this.gpuLabel = null;
        clearPendingOffer();
        persist();
        cancelAutoStop();
        syncProjectObject();
        notifyObservers();
    }

    public synchronized void setOllamaUrl(String url) {
        this.ollamaUrl = migrateOllamaUrlToProxy(url);
        persist();
        notifyObservers();
    }

    /** Anciennes sessions pointaient sur :11434 — bascule vers le proxy :11435. */
    static String migrateOllamaUrlToProxy(String url) {
        if (TextUtils.isEmpty(url)) return url;
        return url.replace("-11434.proxy.runpod.net", "-11435.proxy.runpod.net");
    }

    /** Reset le timer d'arrêt auto (appel OrionCodeTool / activité). */
    public void pingActivity() {
        scheduleAutoStop();
    }

    public void scheduleAutoStop() {
        cancelAutoStop();
        Context ctx = appContext;
        if (ctx == null) return;
        if (getStatus() != OrionStatus.READY && getStatus() != OrionStatus.BUSY) return;
        int minutes = OrionConfig.load(ctx).autoStopMinutes;
        final long delay = Math.max(1, minutes) * 60_000L;
        autoStopRunnable = () -> {
            Log.i(TAG, "Auto-stop Orion après inactivité (" + minutes + " min)");
            try {
                OrionManagerActions.stopQuiet(ctx);
            } catch (Exception e) {
                Log.w(TAG, "auto-stop failed", e);
            }
        };
        handler.postDelayed(autoStopRunnable, delay);
    }

    public void cancelAutoStop() {
        if (autoStopRunnable != null) {
            handler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
    }

    /** Tests : déclenche immédiatement le runnable auto-stop. */
    public void fireAutoStopNowForTests() {
        if (autoStopRunnable != null) {
            Runnable r = autoStopRunnable;
            cancelAutoStop();
            r.run();
        }
    }

    public static String buildOllamaUrl(String podId) {
        if (TextUtils.isEmpty(podId)) return null;
        // 11435 = ollamaproxy (journal agentique) → relaie vers Ollama :11434
        return "https://" + podId + "-11435.proxy.runpod.net";
    }

    /** Ollama direct (sans proxy) — fallback boot si :11435 pas encore up. */
    public static String buildOllamaDirectUrl(String podId) {
        if (TextUtils.isEmpty(podId)) return null;
        return "https://" + podId + "-11434.proxy.runpod.net";
    }

    public static String buildCodeServerUrl(String podId) {
        if (TextUtils.isEmpty(podId)) return null;
        return "https://" + podId + "-8080.proxy.runpod.net";
    }

    public static String buildFileServerUrl(String podId) {
        if (TextUtils.isEmpty(podId)) return null;
        return "https://" + podId + "-3000.proxy.runpod.net";
    }

    /** Proxy RunPod ComfyUI — même schéma que le fileserver, port 8188. */
    public static String buildComfyUiUrl(String podId) {
        if (TextUtils.isEmpty(podId)) return null;
        return "https://" + podId + "-8188.proxy.runpod.net";
    }

    public synchronized String statusSummary() {
        OrionStatus st = getStatus();
        String mode = getPodMode().label();
        if (st == OrionStatus.OFFLINE || TextUtils.isEmpty(podId)) {
            if (!TextUtils.isEmpty(lastError)) {
                return mode + " bloqué : " + lastError;
            }
            return mode + " est hors ligne (OFFLINE).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(mode).append(" : ").append(st.name());
        if (st == OrionStatus.STARTING && loadStep > 0) {
            sb.append('\n').append(OrionLoadStep.line(loadStep,
                    Math.max(loadStepTotal, OrionLoadStep.TOTAL), statusDetail, getPodMode()));
        }
        if (!TextUtils.isEmpty(gpuLabel)) sb.append(" · ").append(gpuLabel);
        if (pricePerHour > 0) {
            sb.append(String.format(Locale.US, " · ~$%.2f/h", pricePerHour));
        }
        float cost = estimatedCost();
        if (cost > 0) {
            sb.append(String.format(Locale.US, " · coût session ~$%.3f", cost));
        }
        if (getPodMode() == PodMode.COMFY) {
            String comfyUrl = buildComfyUiUrl(podId);
            if (!TextUtils.isEmpty(comfyUrl)) {
                sb.append("\nURL : ").append(comfyUrl);
            }
        } else if (!TextUtils.isEmpty(ollamaUrl)) {
            sb.append("\nURL : ").append(ollamaUrl);
        }
        return sb.toString();
    }

    private void notifyObservers() {
        OrionStatus st = getStatus();
        handler.post(() -> {
            for (Observer o : observers) {
                try {
                    o.onOrionStateChanged(st);
                } catch (Exception e) {
                    Log.w(TAG, "observer", e);
                }
            }
        });
    }

    /** Tests : force une notif synchrone après set. */
    public void notifyObserversForTests() {
        OrionStatus st = getStatus();
        List<Observer> copy = new ArrayList<>(observers);
        for (Observer o : copy) {
            o.onOrionStateChanged(st);
        }
    }

    private void clearMemory() {
        podId = null;
        ollamaUrl = null;
        status = OrionStatus.OFFLINE;
        statusDetail = null;
        loadStep = 0;
        loadStepTotal = 0;
        lastError = null;
        startedAt = 0;
        pricePerHour = 0;
        gpuLabel = null;
        pendingOffer = null;
        podMode = PodMode.ORION;
    }

    private void loadPrefs(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        podId = emptyToNull(p.getString(KEY_POD, null));
        ollamaUrl = emptyToNull(p.getString(KEY_URL, null));
        ollamaUrl = migrateOllamaUrlToProxy(ollamaUrl);
        if (!TextUtils.isEmpty(ollamaUrl) && !ollamaUrl.equals(p.getString(KEY_URL, null))) {
            // Persiste la migration 11434 → 11435 (proxy agentique)
            p.edit().putString(KEY_URL, ollamaUrl).apply();
        }
        startedAt = p.getLong(KEY_STARTED, 0L);
        pricePerHour = p.getFloat(KEY_PRICE, 0f);
        gpuLabel = emptyToNull(p.getString(KEY_GPU, null));
        lastError = emptyToNull(p.getString(KEY_LAST_ERROR, null));
        podMode = PodMode.fromPersisted(p.getString(KEY_POD_MODE, PodMode.ORION.name()));
        try {
            status = OrionStatus.valueOf(p.getString(KEY_STATUS, OrionStatus.OFFLINE.name()));
        } catch (Exception e) {
            status = OrionStatus.OFFLINE;
        }
        if (TextUtils.isEmpty(podId)) status = OrionStatus.OFFLINE;
    }

    private void persist() {
        Context ctx = appContext;
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_POD, podId)
                .putString(KEY_URL, ollamaUrl)
                .putString(KEY_STATUS, status != null ? status.name() : OrionStatus.OFFLINE.name())
                .putString(KEY_POD_MODE, (podMode != null ? podMode : PodMode.ORION).name())
                .putLong(KEY_STARTED, startedAt)
                .putFloat(KEY_PRICE, pricePerHour)
                .putString(KEY_GPU, gpuLabel)
                .putString(KEY_LAST_ERROR, lastError != null ? lastError : "")
                .putLong(KEY_LAST_ERROR_AT, TextUtils.isEmpty(lastError) ? 0L : System.currentTimeMillis())
                .apply();
    }

    private void syncProjectObject() {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            com.pegasuscorp.orbe.objects.ProjectObjectStore.getInstance(ctx).syncOrion(ctx);
        } catch (Exception e) {
            Log.w(TAG, "syncProjectObject", e);
        }
    }

    private static String emptyToNull(String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }
}
