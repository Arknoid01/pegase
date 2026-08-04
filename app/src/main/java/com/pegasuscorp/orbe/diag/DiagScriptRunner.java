package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.session.SessionObserver;
import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enchaîne des requêtes prédéfinies avec cooldown, trace stress isolée et rapport final.
 */
public final class DiagScriptRunner {

    public interface Listener {
        void onProgress(int index, int total, String label, String phase);

        void onComplete(DiagScriptResult result);

        void onCannotStart(String reason);
    }

    private static DiagScriptRunner instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private Runnable pendingTimeout;
    private Runnable pendingCooldown;
    private Listener activeListener;
    private boolean stressWasEnabled;
    private long suiteStartedAt;
    private String activeSuiteId = DiagScripts.SUITE_ID;
    private int okCount;
    private int errorCount;
    private int timeoutCount;
    private int skippedCount;
    private Context appContext;
    private final DiagScriptIsolation isolation = new DiagScriptIsolation();

    private DiagScriptRunner() {}

    public static synchronized DiagScriptRunner get() {
        if (instance == null) {
            instance = new DiagScriptRunner();
        }
        return instance;
    }

    public boolean isRunning() {
        return running;
    }

    public void runMiniSuite(Context context, Listener listener) {
        runSuite(context, DiagScripts.miniSuite(), DiagScripts.SUITE_ID, listener);
    }

    /** Suite tags conditionnels (tools_chars + has_ui/f1/life/project). */
    public void runTagsSuite(Context context, Listener listener) {
        runSuite(context, DiagScripts.tagsSuite(), DiagScripts.TAGS_SUITE_ID, listener);
    }

    private void runSuite(Context context, List<DiagScript> scripts, String suiteId,
            Listener listener) {
        Context app = context.getApplicationContext();
        if (running) {
            if (listener != null) listener.onCannotStart("Une suite est déjà en cours.");
            return;
        }
        String block = validateCanRun(app);
        if (block != null) {
            DiagScriptRunStore.markBlocked(app, block);
            if (listener != null) listener.onCannotStart(block);
            return;
        }

        running = true;
        activeListener = listener;
        appContext = app;
        activeSuiteId = suiteId != null ? suiteId : DiagScripts.SUITE_ID;
        okCount = 0;
        errorCount = 0;
        timeoutCount = 0;
        skippedCount = 0;
        suiteStartedAt = System.currentTimeMillis();

        Trace.init(app);
        Trace.clear(app);
        stressWasEnabled = Trace.isStressTest();
        Trace.setStressTest(true);
        DiagScriptRunStore.markStarted(app);
        Trace.state("idle", "script_suite_running");

        int backedUpTurns = isolation.prepareCleanSession(app);
        Trace.scriptSuiteStart(activeSuiteId, scripts.size(), DiagScripts.COOLDOWN_MS,
                backedUpTurns);

        PegaseSession session = PegaseSession.get(app);
        session.init(new SessionContext(Channel.TEXT, false));
        session.enter();

        runStep(app, session, scripts, 0);
    }

    /** Réattache l'UI si l'écran réglages est rouvert pendant un run. */
    public void setListener(Listener listener) {
        activeListener = listener;
    }

    public void cancel(Context context) {
        if (!running) return;
        DiagScriptRunStore.markCancelled(context.getApplicationContext());
        finishCleanup(context.getApplicationContext());
    }

    private void finishCleanup(Context app) {
        isolation.restoreAfterSuite(app);
        clearPending();
        Trace.setStressTest(stressWasEnabled);
        Trace.state("script_suite_running", "idle");
        running = false;
        activeListener = null;
    }

    private static String validateCanRun(Context app) {
        if (ModelStore.useLocalLlm(app)) {
            return "Mini-tests réservés au modèle cloud (désactive le modèle local).";
        }
        if (!ApiKeyStore.hasGroqKey(app) && !ApiKeyStore.hasGeminiKey(app)) {
            return "Configure une clé Groq ou Gemini avant de lancer les mini-tests.";
        }
        return null;
    }

    private void runStep(Context app, PegaseSession session, List<DiagScript> scripts, int index) {
        if (!running) return;

        if (index >= scripts.size()) {
            finishSuite(app, scripts.size());
            return;
        }

        DiagScript script = scripts.get(index);
        if (script.requiresTavily && !ApiKeyStore.hasTavilyKey(app)) {
            skippedCount++;
            Trace.scriptStep(script.id, index, script.query, "skipped", "Clé Tavily manquante");
            notifyProgress(index, scripts.size(), script.label, "ignoré (pas de clé Tavily)");
            scheduleNext(app, session, scripts, index + 1, 500L);
            return;
        }

        notifyProgress(index, scripts.size(), script.label, "envoi…");
        Trace.scriptStep(script.id, index, script.query, "start", null);

        Channel ch = script.channel != null ? script.channel : Channel.TEXT;
        session.init(new SessionContext(ch, false));

        AtomicBoolean finished = new AtomicBoolean(false);
        pendingTimeout = () -> {
            if (!finished.compareAndSet(false, true)) return;
            timeoutCount++;
            Trace.scriptStep(script.id, index, script.query, "timeout", null);
            notifyProgress(index, scripts.size(), script.label, "timeout");
            scheduleNext(app, session, scripts, index + 1, DiagScripts.COOLDOWN_MS);
        };
        main.postDelayed(pendingTimeout, DiagScripts.STEP_TIMEOUT_MS);

        session.send(script.query, new SessionObserver() {
            @Override
            public void onReply(String text, boolean toolFired) {
                if (toolFired) return;
                if (!finished.compareAndSet(false, true)) return;
                main.removeCallbacks(pendingTimeout);
                okCount++;
                Trace.scriptStep(script.id, index, script.query, "ok",
                        text != null ? text : "");
                notifyProgress(index, scripts.size(), script.label, "OK");
                scheduleNext(app, session, scripts, index + 1, DiagScripts.COOLDOWN_MS);
            }

            @Override
            public void onToolResult(ToolResult result) { }

            @Override
            public void onError(String message) {
                if (!finished.compareAndSet(false, true)) return;
                main.removeCallbacks(pendingTimeout);
                errorCount++;
                Trace.scriptStep(script.id, index, script.query, "error", message);
                notifyProgress(index, scripts.size(), script.label, "erreur");
                scheduleNext(app, session, scripts, index + 1, DiagScripts.COOLDOWN_MS);
            }
        });
    }

    private void scheduleNext(Context app, PegaseSession session, List<DiagScript> scripts,
            int nextIndex, long delayMs) {
        if (!running) return;
        notifyProgress(nextIndex, scripts.size(), null,
                delayMs >= DiagScripts.COOLDOWN_MS ? "pause " + (delayMs / 1000) + " s…" : "suite…");
        pendingCooldown = () -> runStep(app, session, scripts, nextIndex);
        main.postDelayed(pendingCooldown, delayMs);
    }

    private void finishSuite(Context app, int stepCount) {
        clearPending();
        long duration = System.currentTimeMillis() - suiteStartedAt;
        Trace.scriptSuiteEnd(activeSuiteId, okCount, errorCount + timeoutCount, duration);

        DiagScriptResult result;
        try {
            result = DiagReport.generateScriptResult(app, stepCount, okCount, errorCount,
                    timeoutCount, skippedCount, duration);
        } catch (Exception e) {
            result = new DiagScriptResult(stepCount, okCount, errorCount, timeoutCount,
                    skippedCount, -1, 0, -1, duration, null);
        }

        DiagScriptRunStore.markFinished(app, result);
        Listener listener = activeListener;
        finishCleanup(app);
        if (listener != null) {
            listener.onComplete(result);
        }
    }

    private void clearPending() {
        if (pendingTimeout != null) {
            main.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        if (pendingCooldown != null) {
            main.removeCallbacks(pendingCooldown);
            pendingCooldown = null;
        }
    }

    private void notifyProgress(int index, int total, String label, String phase) {
        if (appContext != null) {
            DiagScriptRunStore.markProgress(appContext, index, total, label, phase);
        }
        Listener listener = activeListener;
        if (listener != null) {
            listener.onProgress(index, total, label, phase);
        }
    }
}
