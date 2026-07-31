package com.pegasuscorp.orbe.copilot;

import android.graphics.Rect;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Matcher générique sur l'arbre a11y live (scan frais à chaque action — v4).
 * Recherche hybride texte + {@code viewIdResourceName}.
 */
public final class A11yUiMatcher {

    public static final class Criteria {
        public String text = "";
        public String viewId = "";

        public static Criteria fromText(String text) {
            Criteria c = new Criteria();
            c.text = text != null ? text.trim() : "";
            return c;
        }

        public static Criteria fromViewId(String viewId) {
            Criteria c = new Criteria();
            c.viewId = viewId != null ? viewId.trim() : "";
            return c;
        }

        public boolean isEmpty() {
            return TextUtils.isEmpty(text) && TextUtils.isEmpty(viewId);
        }
    }

    /** Métadonnées extraites d'un nœud — pas de handle vivant. */
    public static final class Target {
        public final String text;
        public final String viewId;
        public final String className;
        public final boolean clickable;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Target(String text, String viewId, String className, boolean clickable,
                int left, int top, int right, int bottom) {
            this.text = text != null ? text : "";
            this.viewId = viewId != null ? viewId : "";
            this.className = className != null ? className : "";
            this.clickable = clickable;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean hasBounds() {
            return right > left && bottom > top;
        }
    }

    private A11yUiMatcher() {}

    /**
     * Cherche la meilleure cible dans l'arbre (BFS).
     * @return métadonnées ou null — le nœud source est recyclé avant retour
     */
    public static Target find(AccessibilityNodeInfo root, Criteria criteria) {
        if (root == null || criteria == null || criteria.isEmpty()) return null;
        AccessibilityNodeInfo node = findNode(root, criteria);
        if (node == null) return null;
        try {
            return targetFromNode(node);
        } finally {
            node.recycle();
        }
    }

    /** Trouve un nœud — l'appelant doit {@link AccessibilityNodeInfo#recycle()}. */
    public static AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, Criteria criteria) {
        if (root == null || criteria == null || criteria.isEmpty()) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            try {
                if (nodeMatches(node, criteria)) {
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
            } finally {
                if (node != best) node.recycle();
            }
        }
        return best;
    }

    public static boolean nodeMatches(AccessibilityNodeInfo node, Criteria criteria) {
        if (node == null || criteria == null || criteria.isEmpty()) return false;
        String label = combinedLabel(node);
        String viewId = node.getViewIdResourceName() != null
                ? node.getViewIdResourceName() : "";
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        return matchesFields(label, viewId, className, criteria);
    }

    static boolean matchesFields(String label, String viewId, String className, Criteria criteria) {
        if (criteria == null || criteria.isEmpty()) return false;
        String fLabel = fold(label);
        String fViewId = fold(viewId);
        String fClass = fold(className);
        String hay = (fLabel + " " + fViewId + " " + fClass).trim();
        boolean textOnly = !TextUtils.isEmpty(criteria.text) && TextUtils.isEmpty(criteria.viewId);
        boolean viewOnly = TextUtils.isEmpty(criteria.text) && !TextUtils.isEmpty(criteria.viewId);
        if (viewOnly) {
            return fViewId.contains(fold(criteria.viewId)) || hay.contains(fold(criteria.viewId));
        }
        if (textOnly) {
            return !hay.isEmpty() && hay.contains(fold(criteria.text));
        }
        boolean textOk = hay.contains(fold(criteria.text));
        boolean viewOk = fViewId.contains(fold(criteria.viewId)) || hay.contains(fold(criteria.viewId));
        return textOk && viewOk;
    }

    public static Target targetFromNode(AccessibilityNodeInfo node) {
        String label = combinedLabel(node);
        String viewId = node.getViewIdResourceName() != null
                ? node.getViewIdResourceName() : "";
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return new Target(label, viewId, className, node.isClickable(),
                bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    public static boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return clickClickableParent(node);
    }

    public static boolean clickClickableParent(AccessibilityNodeInfo node) {
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

    public static boolean performScroll(AccessibilityNodeInfo root, String direction) {
        if (root == null) return false;
        String dir = direction != null ? direction.trim().toLowerCase(Locale.ROOT) : "down";
        int action = "up".equals(dir) || "backward".equals(dir) || "back".equals(dir)
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable == null) return false;
        try {
            return scrollable.performAction(action);
        } finally {
            scrollable.recycle();
        }
    }

    /** Premier champ éditable de l'écran (saisie sans cible explicite). */
    public static AccessibilityNodeInfo findEditableRoot(AccessibilityNodeInfo root) {
        return findEditable(root);
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            try {
                if (node.isScrollable()) {
                    recycleQueue(queue);
                    return AccessibilityNodeInfo.obtain(node);
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            } finally {
                node.recycle();
            }
        }
        return null;
    }

    public static boolean performSetText(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null) return false;
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
        AccessibilityNodeInfo editable = findEditable(node);
        if (editable == null) return false;
        try {
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } finally {
            editable.recycle();
        }
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            try {
                if (node.isEditable()) {
                    recycleQueue(queue);
                    return AccessibilityNodeInfo.obtain(node);
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            } finally {
                node.recycle();
            }
        }
        return null;
    }

    static String combinedLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) return text.toString().trim();
        if (desc != null && desc.length() > 0) return desc.toString().trim();
        return "";
    }

    static String fold(String text) {
        return SpeechInputNormalizer.fold(text != null ? text : "")
                .replace('\'', ' ').trim();
    }

    private static void recycleQueue(ArrayDeque<AccessibilityNodeInfo> queue) {
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n != null) n.recycle();
        }
    }
}
