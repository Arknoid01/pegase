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
        if (params == null) return c;
        c.text = params.optString("target", params.optString("text", "")).trim();
        // Compat : si un vieux prompt LLM envoie encore view_id, le traiter comme libellé.
        if (c.text.isEmpty()) {
            String rawId = params.optString("view_id", params.optString("viewId", "")).trim();
            if (!rawId.isEmpty()) {
                String human = UiExplainHelper.humanizeViewId(rawId);
                c.text = !TextUtils.isEmpty(human) ? human : rawId;
            }
        }
        // Jamais de critère viewId côté LLM — matching texte seul (scanne aussi les ids nœuds).
        c.viewId = "";
        return c;
    }

    public static boolean isForegroundAllowed(Context ctx, AccessibilityNodeInfo root) {
        if (ctx == null || root == null) return false;
        CharSequence pkg = root.getPackageName();
        String packageName = pkg != null ? pkg.toString() : "";
        return CopilotPrefs.isPackageAllowed(ctx, packageName);
    }

    public static void highlightTarget(Context ctx, A11yUiMatcher.Target target) {
        if (ctx == null || target == null) return;
        int left = target.left;
        int top = target.top;
        int right = target.right;
        int bottom = target.bottom;
        // Sections Wiki hauteur 0 : surligne une bande cliquable juste au-dessus.
        if (bottom <= top && right > left) {
            bottom = top + 48;
            top = Math.max(0, top - 48);
        }
        if (right <= left || bottom <= top) return;
        String label = !TextUtils.isEmpty(target.text) ? target.text
                : UiExplainHelper.humanizeViewId(target.viewId);
        ElementHighlightService.showActionTarget(ctx, left, top, right, bottom, label);
    }

    public static void executeClick(Context ctx, PegaseAccessibilityService svc,
            JSONObject params, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        A11yUiMatcher.Criteria criteria = parseCriteria(params);
        if (criteria.isEmpty()) {
            cb.onError("Indique la cible à cliquer (texte visible à l'écran).");
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
                performClick(ctx, root, criteria, target, cb);
                return;
            }
            String question = A11yClickPolicy.buildConfirmQuestion(target, level);
            cb.onConfirmNeeded(question,
                    () -> {
                        CopilotUiSupport.notifyActionInProgress(ctx, cb);
                        withRoot(svc, r -> performClick(ctx, r, criteria, target, cb),
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

    private static void performClick(Context ctx, AccessibilityNodeInfo root,
            A11yUiMatcher.Criteria criteria, A11yUiMatcher.Target preview, ToolCallback cb) {
        AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, criteria);
        if (node == null) {
            ElementHighlightService.hide(ctx);
            cb.onError("Je ne trouve plus l'élément à cliquer.");
            return;
        }
        try {
            android.graphics.Rect live = new android.graphics.Rect();
            node.getBoundsInScreen(live);
            // Sections Wiki (hauteur 0) / nœuds non cliquables : ACTION_CLICK sur le
            // WebView parent renvoie souvent true sans effet → succès fantôme.
            boolean preferGesture = live.height() <= 0 || !node.isClickable();
            boolean ok = false;
            String via = "";
            if (!preferGesture) {
                ok = A11yUiMatcher.performClick(node);
                if (ok) via = "a11y";
            }
            if (!ok) {
                // Bounds fraîches du nœud (pas le preview snapshot).
                ok = tapBounds(headerBand(live));
                if (ok) via = "gesture";
            }
            if (!ok && preview != null) {
                ok = tapTarget(preview);
                if (ok) via = "gesture-preview";
            }
            ElementHighlightService.hide(ctx);
            if (ok) {
                String label = preview != null && !TextUtils.isEmpty(preview.text)
                        ? preview.text
                        : (preview != null ? UiExplainHelper.humanizeViewId(preview.viewId) : "l'élément");
                if (TextUtils.isEmpty(label)) label = "l'élément";
                android.util.Log.i("A11yUi", "click ok via=" + via
                        + " live=" + live.toShortString()
                        + " label=" + label);
                cb.onSuccess(ToolResult.text("Clic envoyé sur « " + label + " »."));
            } else {
                cb.onError("Le clic n'a pas abouti — l'élément n'est peut-être pas cliquable.");
            }
        } finally {
            node.recycle();
        }
    }

    /** Bande d'en-tête cliquable au-dessus d'un nœud content hauteur 0. */
    private static android.graphics.Rect headerBand(android.graphics.Rect b) {
        if (b == null) return new android.graphics.Rect();
        int left = b.left;
        int top = b.top;
        int right = b.right;
        int bottom = b.bottom;
        if (bottom <= top && right > left) {
            bottom = top;
            top = Math.max(0, top - 56);
        }
        return new android.graphics.Rect(left, top, right, bottom);
    }

    private static boolean tapTarget(A11yUiMatcher.Target target) {
        if (target == null) return false;
        android.graphics.Rect b = headerBand(new android.graphics.Rect(
                target.left, target.top, target.right, target.bottom));
        return tapBounds(b);
    }

    private static boolean tapBounds(android.graphics.Rect b) {
        if (b == null || b.width() <= 0) return false;
        int h = b.height();
        float x = b.exactCenterX();
        float y = h > 0 ? b.exactCenterY() : Math.max(1f, b.top - 28f);
        PegaseAccessibilityService svc = PegaseAccessibilityService.getInstance();
        return svc != null && svc.tapScreen(x, y);
    }

    private interface RootTask {
        void run(AccessibilityNodeInfo root);
    }

    private static void withRoot(PegaseAccessibilityService svc, RootTask task, Runnable onMissing) {
        if (svc == null) {
            if (onMissing != null) onMissing.run();
            return;
        }
        AccessibilityNodeInfo root = A11yRootPicker.preferAppRoot(svc);
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
