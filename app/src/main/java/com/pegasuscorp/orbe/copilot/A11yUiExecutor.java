package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.copilot.CopilotUiSupport;

import org.json.JSONObject;

/**
 * Exécute les actions UI copilote v4 sur un scan a11y frais.
 */
public final class A11yUiExecutor {

    private A11yUiExecutor() {}

    public static A11yUiMatcher.Criteria parseCriteria(JSONObject params) {
        A11yUiMatcher.Criteria c = new A11yUiMatcher.Criteria();
        c.text = params.optString("target", params.optString("text", "")).trim();
        c.viewId = params.optString("view_id", params.optString("viewId", "")).trim();
        return c;
    }

    public static boolean isForegroundAllowed(Context ctx, AccessibilityNodeInfo root) {
        if (ctx == null || root == null) return false;
        CharSequence pkg = root.getPackageName();
        String packageName = pkg != null ? pkg.toString() : "";
        return CopilotPrefs.isPackageAllowed(ctx, packageName);
    }

    public static void highlightTarget(Context ctx, A11yUiMatcher.Target target) {
        if (ctx == null || target == null || !target.hasBounds()) return;
        ElementHighlightService.showActionTarget(ctx, target.left, target.top,
                target.right, target.bottom, target.text);
    }

    public static void executeClick(Context ctx, PegaseAccessibilityService svc,
            JSONObject params, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        A11yUiMatcher.Criteria criteria = parseCriteria(params);
        if (criteria.isEmpty()) {
            cb.onError("Indique la cible à cliquer (target ou view_id).");
            return;
        }
        withRoot(svc, root -> {
            if (!isForegroundAllowed(ctx, root)) {
                cb.onError("Cette app n'est pas autorisée pour le copilote.");
                return;
            }
            A11yUiMatcher.Target target = A11yUiMatcher.find(root, criteria);
            if (target == null) {
                cb.onError("Je ne trouve pas cet élément à l'écran.");
                return;
            }
            highlightTarget(ctx, target);
            A11yClickPolicy.Level level = A11yClickPolicy.evaluate(target);
            if (level == A11yClickPolicy.Level.NEVER) {
                performClick(root, criteria, target, cb);
                return;
            }
            String question = A11yClickPolicy.buildConfirmQuestion(target, level);
            cb.onConfirmNeeded(question,
                    () -> {
                        CopilotUiSupport.notifyActionInProgress(ctx, cb);
                        withRoot(svc, r -> performClick(r, criteria, target, cb),
                                () -> cb.onError("Service d'accessibilité pas encore prêt — réessaie."));
                    },
                    () -> cb.onError("Clic annulé."));
        }, () -> cb.onError("Service d'accessibilité pas encore prêt — réessaie."));
    }

    public static void executeType(Context ctx, PegaseAccessibilityService svc,
            JSONObject params, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        String value = params.optString("value", params.optString("text_value", "")).trim();
        if (value.isEmpty()) {
            cb.onError("Indique le texte à saisir (value).");
            return;
        }
        A11yUiMatcher.Criteria criteria = parseCriteria(params);
        withRoot(svc, root -> {
            if (!isForegroundAllowed(ctx, root)) {
                cb.onError("Cette app n'est pas autorisée pour le copilote.");
                return;
            }
            AccessibilityNodeInfo node = criteria.isEmpty()
                    ? A11yUiMatcher.findEditableRoot(root)
                    : A11yUiMatcher.findNode(root, criteria);
            if (node == null) {
                cb.onError("Je ne trouve pas le champ à remplir.");
                return;
            }
            try {
                highlightTarget(ctx, A11yUiMatcher.targetFromNode(node));
                boolean ok = A11yUiMatcher.performSetText(node, value);
                if (ok) cb.onSuccess(ToolResult.text("Texte saisi."));
                else cb.onError("Impossible de saisir le texte sur cet élément.");
            } finally {
                node.recycle();
            }
        }, () -> cb.onError("Service d'accessibilité pas encore prêt — réessaie."));
    }

    public static void executeScroll(Context ctx, PegaseAccessibilityService svc,
            JSONObject params, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        String direction = params.optString("direction", "down");
        withRoot(svc, root -> {
            if (!isForegroundAllowed(ctx, root)) {
                cb.onError("Cette app n'est pas autorisée pour le copilote.");
                return;
            }
            boolean ok = A11yUiMatcher.performScroll(root, direction);
            if (ok) cb.onSuccess(ToolResult.text("Défilement effectué."));
            else cb.onError("Impossible de faire défiler cette page.");
        }, () -> cb.onError("Service d'accessibilité pas encore prêt — réessaie."));
    }

    public static void executeBack(Context ctx, PegaseAccessibilityService svc, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        if (svc == null) {
            cb.onError("Service d'accessibilité pas encore prêt — réessaie.");
            return;
        }
        boolean ok = svc.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        if (ok) cb.onSuccess(ToolResult.text("Retour arrière."));
        else cb.onError("Impossible de revenir en arrière.");
    }

    private static void performClick(AccessibilityNodeInfo root, A11yUiMatcher.Criteria criteria,
            A11yUiMatcher.Target preview, ToolCallback cb) {
        AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, criteria);
        if (node == null) {
            cb.onError("Je ne trouve plus l'élément à cliquer.");
            return;
        }
        try {
            boolean ok = A11yUiMatcher.performClick(node);
            if (ok) {
                String label = preview != null && !TextUtils.isEmpty(preview.text)
                        ? preview.text : "l'élément";
                cb.onSuccess(ToolResult.text("Clic sur « " + label + " »."));
            } else {
                cb.onError("Le clic n'a pas abouti — l'élément n'est peut-être pas cliquable.");
            }
        } finally {
            node.recycle();
        }
    }

    private interface RootTask {
        void run(AccessibilityNodeInfo root);
    }

    private static void withRoot(PegaseAccessibilityService svc, RootTask task, Runnable onMissing) {
        if (svc == null) {
            if (onMissing != null) onMissing.run();
            return;
        }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) {
            if (onMissing != null) onMissing.run();
            return;
        }
        try {
            task.run(root);
        } finally {
            root.recycle();
        }
    }
}
