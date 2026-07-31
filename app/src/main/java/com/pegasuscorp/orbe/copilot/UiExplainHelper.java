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
        A11yUiMatcher.Criteria criteria = A11yUiExecutor.parseCriteria(params);
        if (criteria.isEmpty()) return null;
        PegaseAccessibilityService svc = PegaseAccessibilityService.getInstance();
        if (svc != null) {
            android.view.accessibility.AccessibilityNodeInfo root = svc.getRootInActiveWindow();
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
        if (target == null || TextUtils.isEmpty(target.text)) return "";
        return target.text;
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
