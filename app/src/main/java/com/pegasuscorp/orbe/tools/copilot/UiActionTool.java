package com.pegasuscorp.orbe.tools.copilot;

import com.pegasuscorp.orbe.copilot.A11yUiExecutor;
import com.pegasuscorp.orbe.copilot.PegaseAccessibilityService;
import com.pegasuscorp.orbe.copilot.UiLoopRunner;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Actions UI copilote v4 — clic, saisie, scroll, retour (accessibility).
 * Supporte aussi une séquence {@code steps[]} (open + click + type…) en un seul appel.
 * Si {@code steps} échoue de façon récupérable → repli automatique {@link UiLoopRunner}.
 */
public final class UiActionTool implements Tool {

    private static final String TAG = "UiActionTool";

    @Override
    public String id() {
        return "ui_action";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.UI;
    }

    @Override
    public String description() {
        return "ui_action — Contrôle l'écran de l'app autorisée. "
                + "Action unique : action:\"click\"|\"type\"|\"scroll\"|\"back\", "
                + "target:str (libellé visible, JAMAIS viewId), value:str, "
                + "direction:\"up\"|\"down\". "
                + "Multi-étapes (préféré si ouvre+clique+tape) : "
                + "steps:[{action,target?,value?,direction?,name?},…] "
                + "max " + A11yUiExecutor.MAX_SEQUENCE_STEPS + " — ex. "
                + "[{action:\"open\",name:\"Brave\"},"
                + "{action:\"click\",target:\"barre d'adresse\"},"
                + "{action:\"type\",value:\"Wikipedia\"}]. "
                + "Pour open : name libellé (Brave), PAS package espacé "
                + "(évite com. android. chrome). "
                + "action:\"back\" pour retour navigateur / système (mot « retour »). "
                + "Une seule ui_action avec steps, pas N appels. "
                + "Si steps échoue (libellé faux, cookie…) → repli auto ui_loop. "
                + "click peut demander confirmation.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        PegaseAccessibilityService svc = CopilotUiSupport.requireService(ctx, cb);
        if (svc == null) return;

        JSONArray steps = params != null ? params.optJSONArray("steps") : null;
        if (steps != null && steps.length() > 0) {
            A11yUiExecutor.runSequence(ctx, svc, steps,
                    wrapStepsWithLoopFallback(ctx, svc, steps, cb));
            return;
        }

        String action = params != null
                ? params.optString("action", "").trim().toLowerCase()
                : "";
        switch (action) {
            case "click":
            case "tap":
                A11yUiExecutor.executeClick(ctx, svc, params, cb);
                break;
            case "type":
            case "set_text":
                A11yUiExecutor.executeType(ctx, svc, params, cb);
                break;
            case "scroll":
                A11yUiExecutor.executeScroll(ctx, svc, params, cb);
                break;
            case "back":
            case "retour":
            case "go_back":
                A11yUiExecutor.executeBack(ctx, svc, cb);
                break;
            case "open":
            case "launch":
            case "open_app": {
                // Une seule étape open via steps pour settle waits cohérents
                JSONArray one = new JSONArray();
                one.put(params);
                A11yUiExecutor.runSequence(ctx, svc, one,
                        wrapStepsWithLoopFallback(ctx, svc, one, cb));
                break;
            }
            default:
                cb.onError("Action ui_action inconnue : " + action
                        + " — ou passe steps=[{action,...},…]");
        }
    }

    /**
     * Sur erreur récupérable de séquence : relance {@link UiLoopRunner}
     * avec le plan steps + message d'échec comme contexte.
     */
    static ToolCallback wrapStepsWithLoopFallback(Context ctx,
            PegaseAccessibilityService svc, JSONArray steps, ToolCallback cb) {
        return new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                cb.onSuccess(result);
            }

            @Override
            public void onSuccessAndExit(ToolResult result) {
                cb.onSuccessAndExit(result);
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                cb.onConfirmNeeded(question, onConfirm, onCancel);
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                cb.onChoiceNeeded(title, labels, onChosen, onCancel);
            }

            @Override
            public void onProgress(String message) {
                cb.onProgress(message);
            }

            @Override
            public void onError(String error) {
                if (!StepsToLoopFallback.shouldFallback(error)) {
                    cb.onError(error);
                    return;
                }
                String goal = StepsToLoopFallback.goalFromSteps(steps);
                String trace = StepsToLoopFallback.traceFromFailure(steps, error);
                Log.i(TAG, "steps failed → ui_loop fallback: " + error);
                UiLoopRunner.run(ctx, svc, goal, cb,
                        new UiLoopRunner.Seed("échec steps : " + error, trace));
            }
        };
    }
}
