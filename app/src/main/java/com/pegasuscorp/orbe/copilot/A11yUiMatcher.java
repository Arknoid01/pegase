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
        /** Matching texte plus strict (hints par app). */
        public boolean strictText = false;

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

        public Criteria withStrictText(boolean strict) {
            this.strictText = strict;
            return this;
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

        AccessibilityNodeInfo fromApi = null;
        if (!TextUtils.isEmpty(criteria.text)) {
            java.util.List<AccessibilityNodeInfo> byText =
                    root.findAccessibilityNodeInfosByText(criteria.text.trim());
            if (byText != null) {
                AccessibilityNodeInfo pick = pickBest(byText, criteria);
                for (AccessibilityNodeInfo n : byText) {
                    if (n != null) n.recycle();
                }
                // Ne pas court-circuiter le BFS sur un faux positif findByText (gros WebView, etc.).
                if (pick != null && isStrongMatch(pick, criteria)) {
                    fromApi = pick;
                } else if (pick != null) {
                    pick.recycle();
                }
            }
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best = fromApi;
        int bestScore = best != null ? scoreCandidate(best, criteria) + 40 : Integer.MIN_VALUE;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            try {
                if (nodeMatches(node, criteria)) {
                    int score = scoreCandidate(node, criteria);
                    if (score > bestScore) {
                        if (best != null) best.recycle();
                        best = AccessibilityNodeInfo.obtain(node);
                        bestScore = score;
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            } finally {
                node.recycle();
            }
        }
        return best;
    }

    private static boolean isStrongMatch(AccessibilityNodeInfo node, Criteria criteria) {
        if (node == null) return false;
        if (nodeMatches(node, criteria)) return true;
        if (criteria != null && criteria.strictText) return false;
        // findByText sans match fuzzy strict : seulement si le libellé contient vraiment la cible.
        String label = normalizeForMatch(combinedLabel(node));
        String needle = normalizeForMatch(criteria.text);
        return !needle.isEmpty() && label.contains(needle) && label.length() < needle.length() + 48;
    }

    private static AccessibilityNodeInfo pickBest(
            java.util.List<AccessibilityNodeInfo> nodes, Criteria criteria) {
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            int score = scoreCandidate(node, criteria);
            if (nodeMatches(node, criteria)) score += 40;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best != null ? AccessibilityNodeInfo.obtain(best) : null;
    }

    private static int scoreCandidate(AccessibilityNodeInfo node, Criteria criteria) {
        int score = 0;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int h = Math.max(0, bounds.height());
        int w = Math.max(0, bounds.width());
        long area = (long) h * (long) w;

        String label = combinedLabel(node);
        String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
        String hay = normalizeForMatch(label + " " + viewId);
        String exact = !TextUtils.isEmpty(criteria.text)
                ? normalizeForMatch(criteria.text) : "";

        // Libellé exact / très proche > gros conteneur cliquable ambigu.
        String lbl = normalizeForMatch(label);
        if (!exact.isEmpty() && lbl.equals(exact)) score += 120;
        else if (!exact.isEmpty() && lbl.startsWith(exact)) score += 70;
        else if (!exact.isEmpty() && lbl.contains(exact)
                && lbl.length() <= exact.length() + 32) {
            // Mot dans un libellé court (lien, bouton) > paragraphe entier qui le contient.
            score += 45;
        }

        java.util.List<String> toks = significantTokens(exact);
        int tokHits = 0;
        for (String tok : toks) {
            if (hay.contains(tok)) {
                score += 12;
                tokHits++;
            }
        }
        if (!toks.isEmpty() && tokHits == toks.size()) score += 20;

        // Sections MediaWiki : viewId type Astronomie_et_espace-collapsible-*
        String fView = normalizeForMatch(viewId);
        if (fView.contains("collapsible") && tokHits == toks.size() && !toks.isEmpty()) {
            score += 80;
        }

        if (node.isClickable()) score += 25;
        if (node.isEnabled()) score += 5;

        if (h >= 20 && w >= 40) score += 30;
        else if (h > 0 && w > 0) score += 10;
        else score -= 15; // hauteur 0 : OK pour viewId section, mais moins prioritaire qu'un vrai libellé

        // Pénalise les énormes WebView / racines.
        if (area > 800_000L) score -= 100;
        else if (area > 400_000L) score -= 40;

        if (CopilotLocaleFilter.isBrowserChromeLabel(label)) score -= 200;

        return score;
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
        String hay = normalizeForMatch(label + " " + viewId + " " + className);
        if (hay.isEmpty()) return false;

        boolean textOnly = !TextUtils.isEmpty(criteria.text) && TextUtils.isEmpty(criteria.viewId);
        boolean viewOnly = TextUtils.isEmpty(criteria.text) && !TextUtils.isEmpty(criteria.viewId);

        if (viewOnly) {
            return hayContainsNeedle(hay, criteria.viewId);
        }
        if (textOnly) {
            if (criteria.strictText) {
                return hayStrictText(hay, criteria.text);
            }
            return hayContainsNeedle(hay, criteria.text);
        }
        if (criteria.strictText) {
            return hayStrictText(hay, criteria.text)
                    && hayContainsNeedle(hay, criteria.viewId);
        }
        return hayContainsNeedle(hay, criteria.text) && hayContainsNeedle(hay, criteria.viewId);
    }

    /** Exact / préfixe sur le libellé normalisé — évite les gros conteneurs WhatsApp. */
    static boolean hayStrictText(String hayNormalized, String needleRaw) {
        if (TextUtils.isEmpty(needleRaw)) return false;
        String needle = normalizeForMatch(needleRaw);
        if (needle.isEmpty() || hayNormalized == null || hayNormalized.isEmpty()) return false;
        if (hayNormalized.equals(needle)) return true;
        if (hayNormalized.startsWith(needle + " ")) return true;
        // Premier token du libellé = cible (ex. « Marie hier 20:00 »).
        int sp = hayNormalized.indexOf(' ');
        String first = sp < 0 ? hayNormalized : hayNormalized.substring(0, sp);
        return first.equals(needle);
    }

    /**
     * Contenu texte / viewId : accepte espaces vs {@code _}{@code -}, ignore les mots de commande
     * (« clique sur… ») pour matcher p.ex. {@code Astronomie_et_espace-collapsible-content}.
     */
    static boolean hayContainsNeedle(String hayNormalized, String needleRaw) {
        if (TextUtils.isEmpty(needleRaw)) return false;
        String needle = normalizeForMatch(needleRaw);
        if (needle.isEmpty()) return false;
        if (hayNormalized.contains(needle)) return true;

        java.util.List<String> tokens = significantTokens(needle);
        if (tokens.isEmpty()) return false;
        for (String tok : tokens) {
            if (!hayNormalized.contains(tok)) return false;
        }
        return true;
    }

    static String normalizeForMatch(String text) {
        String f = fold(text);
        if (f.isEmpty()) return "";
        return f.replace('_', ' ').replace('-', ' ').replace(':', ' ')
                .replace('[', ' ').replace(']', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    static java.util.List<String> significantTokens(String normalizedNeedle) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (normalizedNeedle == null || normalizedNeedle.isEmpty()) return out;
        for (String raw : normalizedNeedle.split(" ")) {
            if (raw.length() < 2) continue;
            if (STOPWORDS.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    private static final java.util.Set<String> STOPWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "clique", "cliquer", "click", "tap", "tape", "appuie", "appui",
            "sur", "le", "la", "les", "un", "une", "des", "du", "de", "d",
            "au", "aux", "et", "ou", "bouton", "lien", "element", "elements",
            "ecran", "app", "applique", "ouvre", "ouvrir", "active", "activer",
            "section", "titre", "menu", "onglet", "page",
            // Libellés synthétiques snapshot `[icône: …]` renvoyés par le LLM
            "icone", "icones", "icon", "icons", "image", "images"
    ));


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
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        if (clickClickableParent(node)) return true;
        // En-têtes Wikipedia/Chrome : souvent un nœud viewId sans surface cliquable.
        return clickNearbyClickable(node);
    }

    /** Remonte plus profondément, puis cherche un frère / voisin cliquable. */
    private static boolean clickNearbyClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo p = node.getParent();
        int depth = 0;
        while (p != null && depth < 8) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                p.recycle();
                return true;
            }
            // Frères cliquables (bouton d'accordéon au-dessus du content).
            for (int i = 0; i < p.getChildCount(); i++) {
                AccessibilityNodeInfo sibling = p.getChild(i);
                if (sibling == null) continue;
                try {
                    if (sibling.isClickable()
                            && sibling.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        p.recycle();
                        return true;
                    }
                } finally {
                    sibling.recycle();
                }
            }
            AccessibilityNodeInfo next = p.getParent();
            p.recycle();
            p = next;
            depth++;
        }
        if (p != null) p.recycle();
        return false;
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

    /**
     * Barre d'adresse / omnibox navigateur (Chrome, Brave…) via viewId,
     * sinon premier champ éditable.
     * Caller doit {@link AccessibilityNodeInfo#recycle()}.
     */
    public static AccessibilityNodeInfo findBrowserSearchField(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo byId = findByViewIdHint(root,
                "url_bar", "omnibox", "search_box", "url_bar_title", "search_box_text");
        if (byId != null) return byId;
        return findEditable(root);
    }

    private static AccessibilityNodeInfo findByViewIdHint(AccessibilityNodeInfo root,
            String... hints) {
        if (root == null || hints == null || hints.length == 0) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            try {
                String viewId = node.getViewIdResourceName();
                if (viewId != null) {
                    String lower = viewId.toLowerCase(Locale.ROOT);
                    for (String hint : hints) {
                        if (hint != null && !hint.isEmpty()
                                && (lower.endsWith("/" + hint) || lower.contains(hint))) {
                            recycleQueue(queue);
                            return AccessibilityNodeInfo.obtain(node);
                        }
                    }
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

    /** Cibles LLM fréquentes pour l'omnibox / recherche navigateur (pas du contenu page). */
    public static boolean looksLikeBrowserSearchTarget(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String f = SpeechInputNormalizer.fold(raw)
                .replace('\'', ' ')
                .replace('’', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (f.isEmpty()) return false;
        if (f.contains("barre") && (f.contains("adresse") || f.contains("url"))) return true;
        if (f.contains("omnibox") || f.contains("url bar") || f.contains("search or type")) {
            return true;
        }
        if (f.contains("champ") && f.contains("recherche")) return true;
        if (f.contains("demande a google") || f.contains("ask google")
                || f.contains("search google")) {
            return true;
        }
        return f.equals("rechercher") || f.equals("recherche")
                || f.equals("search") || f.equals("search box");
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

    /**
     * Fallback messageries : presse-papiers + ACTION_PASTE (SET_TEXT souvent ignoré).
     * Remplace le contenu du presse-papiers pendant l'action.
     */
    public static boolean performClipboardPaste(android.content.Context ctx,
            AccessibilityNodeInfo node, String text) {
        if (ctx == null || node == null || text == null) return false;
        AccessibilityNodeInfo target = node.isEditable() ? AccessibilityNodeInfo.obtain(node)
                : findEditable(node);
        if (target == null) return false;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            android.content.ClipData prev = null;
            try {
                prev = cm.getPrimaryClip();
            } catch (Exception ignored) {}
            cm.setPrimaryClip(android.content.ClipData.newPlainText("pegase", text));
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            // Restaure l'ancien clip si possible (évite collage croisé plus tard)
            try {
                if (prev != null) cm.setPrimaryClip(prev);
                else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
            } catch (Exception ignored) {}
            return ok;
        } finally {
            target.recycle();
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
        // Aligné sur le snapshot `[icône: shortId]` : le short id entre dans le haystack
        // même si le LLM cherche « mic button » sans le package.
        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) {
            String shortId = A11yTreeExtractor.shortResourceName(viewId);
            if (!shortId.isEmpty()) return shortId;
        }
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
