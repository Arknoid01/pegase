package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * Réponse locale ou overlay pour {@code ui_explain} (v4).
 */
public final class UiExplainHelper {

    private UiExplainHelper() {}

    public static A11yUiMatcher.Target resolveTarget(Context ctx, JSONObject params) {
        A11yUiMatcher.Target fromBounds = targetFromBounds(params);
        if (fromBounds != null) return fromBounds;

        A11yUiMatcher.Criteria criteria = A11yUiExecutor.parseCriteria(params);
        if (criteria.isEmpty()) return null;

        PegaseAccessibilityService svc = PegaseAccessibilityService.getInstance();
        if (svc != null) {
            android.view.accessibility.AccessibilityNodeInfo root =
                    A11yRootPicker.preferAppRoot(svc);
            if (root != null) {
                try {
                    if (A11yUiExecutor.isForegroundAllowed(ctx, root)) {
                        A11yUiMatcher.Target live = A11yUiMatcher.find(root, criteria);
                        if (live != null) return live;
                    }
                } finally {
                    root.recycle();
                }
            }
        }
        return findInSnapshot(ctx, criteria);
    }

    public static String localAnswer(A11yUiMatcher.Target target, String question) {
        if (target == null) return "";
        if (!TextUtils.isEmpty(target.text)) return target.text;
        // viewId technique (ex. Astronomie_et_espace-collapsible) → libellé lisible
        if (!TextUtils.isEmpty(target.viewId)) {
            return humanizeViewId(target.viewId);
        }
        return "";
    }

    static String humanizeViewId(String viewId) {
        if (viewId == null || viewId.isEmpty()) return "";
        String s = viewId;
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) s = s.substring(slash + 1);
        s = s.replace("-collapsible-content", "")
                .replace("-collapsible-heading", "")
                .replace("-collapsible-toggle", "")
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    public static void showOverlay(Context ctx, A11yUiMatcher.Target target, String answer) {
        if (ctx == null || target == null || !target.hasBounds()) return;
        String text = !TextUtils.isEmpty(answer) ? answer : target.text;
        if (TextUtils.isEmpty(text)) return;
        List<TranslationOverlayService.TranslatedBlock> blocks = Collections.singletonList(
                new TranslationOverlayService.TranslatedBlock(
                        target.text, text,
                        target.left, target.top, target.right, target.bottom));
        TranslationOverlayService.showExplain(ctx, blocks);
    }

    private static A11yUiMatcher.Target targetFromBounds(JSONObject params) {
        if (params == null || !params.has("left") || !params.has("top")
                || !params.has("right") || !params.has("bottom")) {
            return null;
        }
        int left = params.optInt("left");
        int top = params.optInt("top");
        int right = params.optInt("right");
        int bottom = params.optInt("bottom");
        if (right <= left || bottom <= top) return null;
        String label = params.optString("target", params.optString("label", "")).trim();
        return new A11yUiMatcher.Target(label, "", "", false, left, top, right, bottom);
    }

    private static A11yUiMatcher.Target findInSnapshot(Context ctx, A11yUiMatcher.Criteria criteria) {
        if (ctx == null || criteria == null || criteria.isEmpty()) return null;
        for (A11ySnapshot.Node node : A11ySnapshot.loadNodes(ctx)) {
            if (A11yUiMatcher.matchesFields(node.text, node.viewId, node.className, criteria)) {
                return new A11yUiMatcher.Target(
                        node.text, node.viewId, node.className, node.clickable,
                        node.left, node.top, node.right, node.bottom);
            }
        }
        return null;
    }
}
