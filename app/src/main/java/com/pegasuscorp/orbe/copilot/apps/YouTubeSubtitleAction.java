package com.pegasuscorp.orbe.copilot.apps;

import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Actions hardcodées YouTube — pas d'interprétation LLM cloud.
 * Premier cas d'usage copilote : activer les sous-titres à la voix.
 */
public final class YouTubeSubtitleAction {

    private static final String[] CC_HINTS = {
            "sous-titres", "sous titres", "subtitles", "subtitle",
            "closed captions", "captions", "caption", "cc"
    };

    private YouTubeSubtitleAction() {}

    /**
     * Cherche le bouton sous-titres / CC et clique.
     * @return true si un clic a été tenté avec succès
     */
    public static boolean toggleSubtitles(AccessibilityNodeInfo root) {
        if (root == null) return false;
        AccessibilityNodeInfo target = findCcButton(root);
        if (target == null) return false;
        boolean clicked = target.isClickable()
                ? target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                : clickParent(target);
        target.recycle();
        return clicked;
    }

    private static boolean clickParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo p = node.getParent();
        int depth = 0;
        while (p != null && depth < 4) {
            if (p.isClickable()) {
                boolean ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                p.recycle();
                return ok;
            }
            AccessibilityNodeInfo next = p.getParent();
            p.recycle();
            p = next;
            depth++;
        }
        if (p != null) p.recycle();
        return false;
    }

    private static AccessibilityNodeInfo findCcButton(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (matchesCc(node)) {
                if (node.isClickable()) {
                    recycleQueue(queue);
                    if (best != null) best.recycle();
                    return AccessibilityNodeInfo.obtain(node);
                }
                if (best == null) {
                    best = AccessibilityNodeInfo.obtain(node);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            node.recycle();
        }
        return best;
    }

    private static boolean matchesCc(AccessibilityNodeInfo node) {
        String desc = node.getContentDescription() != null
                ? node.getContentDescription().toString().toLowerCase(Locale.ROOT) : "";
        String text = node.getText() != null
                ? node.getText().toString().toLowerCase(Locale.ROOT) : "";
        String viewId = node.getViewIdResourceName() != null
                ? node.getViewIdResourceName().toLowerCase(Locale.ROOT) : "";
        String hay = desc + " " + text + " " + viewId;
        if (TextUtils.isEmpty(hay.trim())) return false;
        for (String hint : CC_HINTS) {
            if (hay.contains(hint)) return true;
        }
        return viewId.contains("caption") || viewId.contains("subtitle");
    }

    private static void recycleQueue(ArrayDeque<AccessibilityNodeInfo> queue) {
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n != null) n.recycle();
        }
    }
}
