package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.copilot.CopilotUiSupport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boucle a11y goal-driven : snapshot → 1 décision LLM → 1 action → relecture.
 * Contrairement à {@code ui_action.steps}, replanifie à chaque geste.
 */
public final class UiLoopRunner {

    private static final String TAG = "UiLoop";
    public static final int MAX_TURNS = 10;
    private static final long SETTLE_MS = 450L;
    private static final long ACTION_TIMEOUT_MS = 90_000L;
    private static final int SCREEN_CHARS = 1_800;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pegase-ui-loop");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    public static final class Seed {
        public final String lastResult;
        public final String trace;

        public Seed(String lastResult, String trace) {
            this.lastResult = lastResult != null ? lastResult : "(démarrage)";
            this.trace = trace != null ? trace : "";
        }
    }

    private UiLoopRunner() {}

    public static void run(Context ctx, PegaseAccessibilityService svc,
            String goal, ToolCallback cb) {
        run(ctx, svc, goal, cb, null);
    }

    public static void run(Context ctx, PegaseAccessibilityService svc,
            String goal, ToolCallback cb, Seed seed) {
        if (cb == null) return;
        if (ctx == null || svc == null) {
            cb.onError("Service d'accessibilité indisponible.");
            return;
        }
        String g = goal != null ? goal.trim() : "";
        if (g.isEmpty()) {
            cb.onError("Indique un objectif (goal), ex. « cherche Wikipedia astronomie ».");
            return;
        }
        Context app = ctx.getApplicationContext();
        CopilotUiSupport.notifyProgress(app, cb,
                seed != null ? "Reprise en boucle UI…" : "Boucle UI…");
        IO.execute(() -> runLoop(app, svc, g, cb, seed));
    }

    private static void runLoop(Context ctx, PegaseAccessibilityService svc,
            String goal, ToolCallback cb, Seed seed) {
        String lastResult = seed != null ? seed.lastResult : "(démarrage)";
        StringBuilder trace = new StringBuilder();
        String lastPkg = "";
        if (seed != null && !TextUtils.isEmpty(seed.trace)) {
            trace.append(seed.trace);
        }
        try {
            for (int turn = 1; turn <= MAX_TURNS; turn++) {
                final int turnNum = turn;
                mainHandler().post(() -> cb.onProgress("Boucle UI " + turnNum + "/" + MAX_TURNS));

                ScreenSnap snap = captureScreen(ctx, svc);
                if (snap == null || TextUtils.isEmpty(snap.text)) {
                    deliverError(cb, "Impossible de lire l'écran (tour " + turn + ").");
                    return;
                }
                if (!TextUtils.isEmpty(snap.packageName)) lastPkg = snap.packageName;
                if (!CopilotPrefs.isPackageAllowed(ctx, snap.packageName)
                        && !TextUtils.isEmpty(snap.packageName)) {
                    deliverError(cb, "App hors whitelist copilote (" + snap.packageName + ").");
                    return;
                }

                CopilotAppHints hints = CopilotAppHintsStore.get(ctx, snap.packageName);
                String prompt = buildTurnPrompt(goal, snap, hints, turn, lastResult, trace);
                String raw;
                try {
                    raw = PegaseSession.get(ctx).completeCopilotReflectionSync(prompt);
                } catch (Exception e) {
                    Log.w(TAG, "llm turn " + turn, e);
                    deliverError(cb, "Boucle UI : le modèle n'a pas répondu (tour " + turn + ").");
                    return;
                }

                UiLoopDecision decision = UiLoopDecision.parse(raw);
                Log.i(TAG, "turn=" + turn + " kind=" + decision.kind
                        + " action=" + decision.action
                        + " reason=" + decision.reason);

                switch (decision.kind) {
                    case FINISH_OK: {
                        String msg = !TextUtils.isEmpty(decision.reason)
                                ? decision.reason.trim()
                                : "";
                        finishWithOptionalLearn(ctx, lastPkg, goal, trace, "ok", cb,
                                () -> deliverSuccess(cb, ToolResult.text(msg)));
                        return;
                    }
                    case FINISH_FAIL: {
                        String msg = !TextUtils.isEmpty(decision.reason)
                                ? decision.reason.trim()
                                : "Objectif non atteint.";
                        finishWithOptionalLearn(ctx, lastPkg, goal, trace, "fail", cb,
                                () -> deliverError(cb, msg));
                        return;
                    }
                    case FINISH_NEED_CONFIRM: {
                        String q = !TextUtils.isEmpty(decision.reason)
                                ? decision.reason.trim()
                                : "Confirmer pour continuer ?";
                        CountDownLatch latch = new CountDownLatch(1);
                        AtomicReference<Boolean> ok = new AtomicReference<>(false);
                        mainHandler().post(() -> cb.onConfirmNeeded(q,
                                () -> {
                                    ok.set(true);
                                    latch.countDown();
                                },
                                () -> {
                                    ok.set(false);
                                    latch.countDown();
                                }));
                        if (!awaitLatch(latch, ACTION_TIMEOUT_MS)) {
                            deliverError(cb, "Confirmation expirée.");
                            return;
                        }
                        if (!Boolean.TRUE.equals(ok.get())) {
                            deliverError(cb, "Boucle annulée.");
                            return;
                        }
                        lastResult = "utilisateur a confirmé : " + q;
                        appendTrace(trace, turn, "confirm", lastResult);
                        continue;
                    }
                    case INVALID: {
                        lastResult = "décision invalide : " + decision.reason;
                        appendTrace(trace, turn, "invalid", lastResult);
                        if (turn >= 3 && decision.reason != null
                                && decision.reason.contains("JSON")) {
                            deliverError(cb, "Boucle UI : le modèle ne renvoie pas de JSON valide.");
                            return;
                        }
                        continue;
                    }
                    case ACTION:
                        break;
                    default:
                        deliverError(cb, "État de boucle inconnu.");
                        return;
                }

                String err = executeAction(ctx, svc, decision, cb);
                if (err != null) {
                    lastResult = "échec « " + decision.action + " » : " + err;
                    appendTrace(trace, turn, decision.action, lastResult);
                    // Continue : la boucle peut se rattraper (Sanna-style)
                    sleepQuiet(SETTLE_MS);
                    continue;
                }
                lastResult = "ok « " + decision.action + " »"
                        + describeAction(decision);
                appendTrace(trace, turn, decision.action, lastResult);
                sleepQuiet(SETTLE_MS);
                A11yUiExecutor.waitTreeSettle(svc, 1_200L);
            }
            finishWithOptionalLearn(ctx, lastPkg, goal, trace, "timeout", cb,
                    () -> deliverError(cb, "Boucle UI : plafond de " + MAX_TURNS
                            + " tours atteint sans finish_task."));
        } catch (Exception e) {
            Log.e(TAG, "runLoop", e);
            deliverError(cb, "Boucle UI interrompue.");
        }
    }

    private static void finishWithOptionalLearn(Context ctx, String pkg, String goal,
            StringBuilder trace, String outcome, ToolCallback cb, Runnable deliver) {
        CopilotHintsLearner.maybeLearnThen(ctx, pkg, goal,
                trace != null ? trace.toString() : "", outcome, cb, deliver);
    }

    /** Exposé tests / settle après action. */
    static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String executeAction(Context ctx, PegaseAccessibilityService svc,
            UiLoopDecision decision, ToolCallback parentCb) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>(null);
        ToolCallback bridge = new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                done.countDown();
            }

            @Override
            public void onSuccessAndExit(ToolResult result) {
                done.countDown();
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                // Gèle la boucle : une seule confirmation utilisateur, puis reprend.
                mainHandler().post(() -> parentCb.onConfirmNeeded(question,
                        () -> {
                            if (onConfirm != null) onConfirm.run();
                        },
                        () -> {
                            error.set("Clic annulé.");
                            if (onCancel != null) onCancel.run();
                            done.countDown();
                        }));
            }

            @Override
            public void onProgress(String message) {
                parentCb.onProgress(message);
            }

            @Override
            public void onError(String err) {
                error.set(err != null ? err : "erreur");
                done.countDown();
            }
        };

        JSONObject params = decision.params;
        String action = decision.action;
        mainHandler().post(() -> {
            switch (action) {
                case "click":
                    A11yUiExecutor.executeClick(ctx, svc, params, bridge);
                    break;
                case "type":
                    A11yUiExecutor.executeType(ctx, svc, params, bridge);
                    break;
                case "scroll":
                    A11yUiExecutor.executeScroll(ctx, svc, params, bridge);
                    break;
                case "back":
                    A11yUiExecutor.executeBack(ctx, svc, bridge);
                    break;
                case "open": {
                    JSONArray one = new JSONArray();
                    one.put(params);
                    A11yUiExecutor.runSequence(ctx, svc, one, bridge);
                    break;
                }
                default:
                    error.set("action inconnue : " + action);
                    done.countDown();
                    break;
            }
        });
        if (!awaitLatch(done, ACTION_TIMEOUT_MS)) {
            return "délai dépassé";
        }
        return error.get();
    }

    private static boolean awaitLatch(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String describeAction(UiLoopDecision d) {
        String t = d.params.optString("target", d.params.optString("name", ""));
        String v = d.params.optString("value", "");
        if (!t.isEmpty() && !v.isEmpty()) return " target=" + t + " value=" + v;
        if (!t.isEmpty()) return " target=" + t;
        if (!v.isEmpty()) return " value=" + v;
        return "";
    }

    private static void appendTrace(StringBuilder trace, int turn, String action, String result) {
        if (trace.length() > 0) trace.append('\n');
        trace.append(turn).append(". ").append(action).append(" → ").append(result);
        if (trace.length() > 900) {
            trace.delete(0, trace.length() - 700);
            trace.insert(0, "…\n");
        }
    }

    static String buildTurnPrompt(String goal, ScreenSnap snap, CopilotAppHints hints,
            int turn, String lastResult, CharSequence trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu pilotes l'écran Android pas à pas (boucle a11y).\n");
        sb.append("Objectif : ").append(goal).append('\n');
        sb.append("Tour ").append(turn).append('/').append(MAX_TURNS).append(".\n");
        sb.append("Dernier résultat : ").append(lastResult).append('\n');
        if (trace != null && trace.length() > 0) {
            sb.append("Historique court :\n").append(trace).append('\n');
        }
        sb.append("App : ").append(snap.packageName).append('\n');
        if (hints != null && !hints.isEmpty()) {
            sb.append(hints.toPromptSection());
        }
        sb.append("Écran actuel (libellés visibles) :\n");
        sb.append(ScreenPiiRedactor.redact(snap.text)).append('\n');
        sb.append("Réponds UNIQUEMENT un JSON, sans markdown :\n");
        sb.append("- Une action : {\"action\":\"click|type|scroll|back|open\",");
        sb.append("\"target\":\"libellé visible\",\"value\":\"…\",\"direction\":\"up|down\",");
        sb.append("\"name\":\"libellé app\"}\n");
        sb.append("- Ou fin : {\"finish_task\":\"ok|fail|need_confirm\",\"reason\":\"…\"}\n");
        sb.append("Règles : une seule action par tour ; JAMAIS viewId ; ");
        sb.append("si cookie/dialog → dismiss puis continue ; ");
        sb.append("si cible absente → autre libellé ou scroll ; ");
        sb.append("finish_task ok seulement si l'objectif est atteint à l'écran.\n");
        return sb.toString();
    }

    private static ScreenSnap captureScreen(Context ctx, PegaseAccessibilityService svc) {
        AccessibilityNodeInfo root = A11yRootPicker.preferForegroundRoot(svc);
        if (root == null) {
            // Repli snapshot fichier
            String pkg = ScreenContextStore.getLastPackage(ctx);
            String text = A11yTreeExtractor.extractPlainText(ctx);
            if (TextUtils.isEmpty(text)) text = ScreenContextStore.getLastText(ctx);
            if (TextUtils.isEmpty(text)) return null;
            return new ScreenSnap(pkg, clip(text, SCREEN_CHARS));
        }
        try {
            String pkg = A11yRootPicker.packageOf(root);
            A11yTreeExtractor.writeSnapshot(ctx, root, pkg);
            String text = A11yTreeExtractor.extractPlainText(ctx);
            if (TextUtils.isEmpty(text)) {
                text = ScreenContextStore.getLastText(ctx);
            }
            if (!TextUtils.isEmpty(text) && !TextUtils.isEmpty(pkg)) {
                ScreenContextStore.update(ctx, pkg, text);
            }
            if (TextUtils.isEmpty(text)) return null;
            return new ScreenSnap(pkg, clip(text, SCREEN_CHARS));
        } finally {
            root.recycle();
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static void deliverSuccess(ToolCallback cb, ToolResult result) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onSuccess(result);
        } else {
            mainHandler().post(() -> cb.onSuccess(result));
        }
    }

    private static void deliverError(ToolCallback cb, String err) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onError(err);
        } else {
            mainHandler().post(() -> cb.onError(err));
        }
    }

    static final class ScreenSnap {
        final String packageName;
        final String text;

        ScreenSnap(String packageName, String text) {
            this.packageName = packageName != null ? packageName : "";
            this.text = text != null ? text : "";
        }
    }
}
