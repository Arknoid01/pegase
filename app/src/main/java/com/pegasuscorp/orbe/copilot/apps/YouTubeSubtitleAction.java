package com.pegasuscorp.orbe.copilot.apps;

import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.copilot.A11yUiMatcher;

/**
 * Actions hardcodées YouTube — délègue au matcher générique v4.
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
        for (String hint : CC_HINTS) {
            A11yUiMatcher.Criteria c = A11yUiMatcher.Criteria.fromText(hint);
            AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, c);
            if (node == null) continue;
            try {
                if (A11yUiMatcher.performClick(node)) return true;
            } finally {
                node.recycle();
            }
        }
        A11yUiMatcher.Criteria byId = A11yUiMatcher.Criteria.fromViewId("caption");
        AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, byId);
        if (node == null) {
            byId = A11yUiMatcher.Criteria.fromViewId("subtitle");
            node = A11yUiMatcher.findNode(root, byId);
        }
        if (node == null) return false;
        try {
            return A11yUiMatcher.performClick(node);
        } finally {
            node.recycle();
        }
    }
}
