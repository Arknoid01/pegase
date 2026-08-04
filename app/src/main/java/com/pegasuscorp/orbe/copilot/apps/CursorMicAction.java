package com.pegasuscorp.orbe.copilot.apps;

import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.copilot.A11yUiMatcher;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Micro Cursor web (Chrome) — le bouton a11y s'appelle
 * « Démarrer la saisie vocale » / « Start voice input », pas « micro ».
 */
public final class CursorMicAction {

    /** Libellés a11y réels du bouton micro (FR / EN). */
    static final String[] MIC_LABELS = {
            "Démarrer la saisie vocale",
            "Start voice input",
            "Start dictation",
            "Start voice dictation",
            "Voice input",
            "Dictate",
            "Dictation",
            "saisie vocale"
    };

    private CursorMicAction() {}

    /** True si la cible utilisateur/LLM vise le micro (pas le libellé Cursor). */
    public static boolean looksLikeMicRequest(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String f = fold(raw);
        if (f.isEmpty()) return false;
        if (f.equals("mic") || f.equals("micro") || f.equals("microphone")
                || f.equals("micro phone") || f.equals("dictée") || f.equals("dictee")
                || f.equals("dictation") || f.equals("voice") || f.equals("voix")
                || f.equals("de micro") || f.equals("du micro") || f.equals("le micro")
                || f.equals("la micro")) {
            return true;
        }
        // « de micro », « icone de micro », suffixe micro(phone)
        if (f.endsWith(" micro") || f.endsWith(" microphone") || f.endsWith(" mic")
                || f.contains(" de micro") || f.contains(" du micro")) {
            return true;
        }
        return f.contains("saisie vocale")
                || f.contains("voice input")
                || f.contains("start voice")
                || f.contains("start dictation")
                || f.contains("micro cursor")
                || f.contains("microphone cursor")
                || f.contains("bouton micro")
                || f.contains("icone micro")
                || f.contains("icone microphone")
                || f.contains("icon mic")
                || (f.contains("micro") && (f.contains("clique") || f.contains("click")
                || f.contains("active") || f.contains("lance") || f.contains("appuie")));
    }

    /** Page Cursor ouverte dans un navigateur (barre d'URL ou titre). */
    public static boolean isCursorPage(AccessibilityNodeInfo root) {
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        while (!queue.isEmpty() && visited < 400) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            try {
                if (nodeMentionsCursor(node)) {
                    recycleQueue(queue);
                    return true;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            } finally {
                node.recycle();
            }
        }
        recycleQueue(queue);
        return false;
    }

    /**
     * Premier libellé micro présent dans l'arbre, ou null.
     * Préfère une page Cursor ; accepte aussi le libellé exact seul.
     */
    public static String resolveMicLabel(AccessibilityNodeInfo root) {
        if (root == null) return null;
        boolean cursor = isCursorPage(root);
        for (String label : MIC_LABELS) {
            A11yUiMatcher.Criteria c = A11yUiMatcher.Criteria.fromText(label);
            AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, c);
            if (node == null) continue;
            node.recycle();
            if (cursor || label.equalsIgnoreCase("Démarrer la saisie vocale")
                    || label.equalsIgnoreCase("Start voice input")) {
                return label;
            }
        }
        return null;
    }

    /** Cherche et clique le micro Cursor. */
    public static boolean clickMic(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String label = resolveMicLabel(root);
        if (label == null) return false;
        A11yUiMatcher.Criteria c = A11yUiMatcher.Criteria.fromText(label);
        AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, c);
        if (node == null) return false;
        try {
            return A11yUiMatcher.performClick(node);
        } finally {
            node.recycle();
        }
    }

    public static A11yUiMatcher.Target findMic(AccessibilityNodeInfo root) {
        String label = resolveMicLabel(root);
        if (label == null) return null;
        return A11yUiMatcher.find(root, A11yUiMatcher.Criteria.fromText(label));
    }

    private static boolean nodeMentionsCursor(AccessibilityNodeInfo node) {
        String viewId = node.getViewIdResourceName();
        String hay = "";
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null) hay += text;
        if (desc != null) hay += " " + desc;
        if (viewId != null && (viewId.endsWith("/url_bar") || viewId.contains("url_bar"))) {
            // Barre d'adresse Chrome / Brave.
        } else if (!TextUtils.isEmpty(hay) && !hay.toLowerCase(Locale.ROOT).contains("cursor")) {
            return false;
        }
        String f = fold(hay);
        return f.contains("cursor.com")
                || f.contains("cursor.com/agents")
                || f.contains("cursor.com/dashboard")
                || (f.contains("cursor") && (f.contains("agent") || f.contains("agents")));
    }

    static String fold(String text) {
        return SpeechInputNormalizer.fold(text != null ? text : "")
                .replace('\'', ' ')
                .replace('_', ' ').replace('-', ' ').replace(':', ' ')
                .replace('[', ' ').replace(']', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void recycleQueue(ArrayDeque<AccessibilityNodeInfo> queue) {
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n != null) n.recycle();
        }
    }
}
