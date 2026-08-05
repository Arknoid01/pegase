package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import com.pegasuscorp.orbe.copilot.apps.CursorMicAction;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.copilot.CopilotUiSupport;
import com.pegasuscorp.orbe.tools.device.OpenAppTool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exécute les actions UI copilote v4 sur un scan a11y frais.
 */
public final class A11yUiExecutor {

    /** Plafond séquence ui_action.steps. */
    public static final int MAX_SEQUENCE_STEPS = 6;
    private static final long SETTLE_IDLE_MS = 450L;
    private static final long SETTLE_TIMEOUT_MS = 2_500L;
    private static final long FOREGROUND_TIMEOUT_MS = 3_500L;
    private static final long CONFIRM_TIMEOUT_MS = 120_000L;

    private static final ExecutorService SEQ_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ui-action-seq");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private A11yUiExecutor() {}

    public static A11yUiMatcher.Criteria parseCriteria(JSONObject params) {
        A11yUiMatcher.Criteria c = new A11yUiMatcher.Criteria();
        if (params == null) return c;
        c.text = stripLeadingArticles(unwrapIconTarget(
                params.optString("target", params.optString("text", "")).trim()));
        // Compat : si un vieux prompt LLM envoie encore view_id, le traiter comme libellé.
        if (c.text.isEmpty()) {
            String rawId = params.optString("view_id", params.optString("viewId", "")).trim();
            if (!rawId.isEmpty()) {
                String human = UiExplainHelper.humanizeViewId(rawId);
                c.text = stripLeadingArticles(
                        !TextUtils.isEmpty(human) ? human : rawId);
            }
        }
        // Jamais de critère viewId côté LLM — matching texte seul (scanne aussi les ids nœuds).
        c.viewId = "";
        return c;
    }

    /** « de micro » / « le bouton » — articles / prépositions collés par le LLM. */
    static String stripLeadingArticles(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String t = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)^(le|la|les|l['’]|un|une|du|de|des|d['’])\\s+(.+)$").matcher(t);
        if (m.find()) {
            String rest = m.group(2).trim();
            if (!rest.isEmpty()) return stripLeadingArticles(rest);
        }
        return t;
    }

    /**
     * Le snapshot montre {@code [icône: mic_button]} ; le LLM le renvoie souvent tel quel.
     * Le matching live n'a pas ce libellé synthétique — on extrait l'id court.
     */
    static String unwrapIconTarget(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String t = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)^\\[\\s*ic[oô]ne\\s*:\\s*(.+?)\\s*\\]$").matcher(t);
        if (m.find()) {
            t = m.group(1).trim();
        } else {
            // « icône mic_button » / « l'icone search »
            m = java.util.regex.Pattern.compile(
                    "(?i)^(?:l['’])?ic[oô]ne(?:s)?\\s+(.+)$").matcher(t);
            if (m.find()) t = m.group(1).trim();
        }
        // Crochets résiduels
        if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
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
        A11yUiMatcher.Criteria early = parseCriteria(params);
        // « retour » / « back » = navigation système, pas un bouton à matcher.
        if (looksLikeBackCommand(early.text)) {
            executeBack(ctx, svc, cb);
            return;
        }
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        if (early.isEmpty()) {
            cb.onError("Indique la cible à cliquer (texte visible à l'écran).");
            return;
        }
        withForegroundRoot(ctx, svc, root -> {
            String pkg = A11yRootPicker.packageOf(root);
            CopilotAppHints hints = CopilotAppHintsStore.get(ctx, pkg);
            A11yUiMatcher.Criteria criteria = parseCriteria(params);
            if (!TextUtils.isEmpty(criteria.text)) {
                criteria.text = hints.resolveAlias(criteria.text);
            }
            criteria.strictText = hints.strictTextMatch;
            String want = criteria.text != null ? criteria.text : "";
            // Cursor web : « micro » ≠ libellé a11y « Démarrer la saisie vocale ».
            A11yUiMatcher.Criteria effective = criteria;
            if (CursorMicAction.looksLikeMicRequest(want)) {
                String micLabel = CursorMicAction.resolveMicLabel(root);
                if (micLabel != null) {
                    effective = A11yUiMatcher.Criteria.fromText(micLabel)
                            .withStrictText(hints.strictTextMatch);
                    want = micLabel;
                }
            }
            A11yUiMatcher.Target resolved = A11yUiMatcher.find(root, effective);
            if (resolved == null && A11yUiMatcher.looksLikeBrowserSearchTarget(want)) {
                AccessibilityNodeInfo field = A11yUiMatcher.findBrowserSearchField(root);
                if (field != null) {
                    try {
                        resolved = A11yUiMatcher.targetFromNode(field);
                        String vid = field.getViewIdResourceName();
                        if (vid != null && !vid.isEmpty()) {
                            effective = A11yUiMatcher.Criteria.fromViewId(vid);
                        } else {
                            CharSequence t = field.getContentDescription();
                            if (t == null || t.length() == 0) t = field.getText();
                            if (t != null && t.length() > 0) {
                                effective = A11yUiMatcher.Criteria.fromText(t.toString())
                                        .withStrictText(hints.strictTextMatch);
                            } else {
                                effective = A11yUiMatcher.Criteria.fromText("url_bar");
                            }
                        }
                        want = !TextUtils.isEmpty(effective.text) ? effective.text
                                : (!TextUtils.isEmpty(effective.viewId)
                                ? effective.viewId : "barre d'adresse");
                    } finally {
                        field.recycle();
                    }
                }
            }
            if (resolved == null) {
                Trace.copilotUi("matcher_miss", "click_not_found",
                        "Cible introuvable", pkg, want);
                cb.onError("Je ne trouve pas cet élément à l'écran.");
                return;
            }
            final A11yUiMatcher.Target target = resolved;
            final A11yUiMatcher.Criteria clickCriteria = effective;
            final String labelHint = want;
            final CopilotAppHints clickHints = hints;
            highlightTarget(ctx, target);
            A11yClickPolicy.Level level = A11yClickPolicy.evaluate(target);
            if (level == A11yClickPolicy.Level.NEVER) {
                performClick(ctx, root, clickCriteria, target, clickHints, cb);
                return;
            }
            String question = A11yClickPolicy.buildConfirmQuestion(target, level);
            String label = !TextUtils.isEmpty(target.text) ? target.text : labelHint;
            Trace.copilotUi("confirm_ask", level.name().toLowerCase(java.util.Locale.ROOT),
                    question, pkg, label);
            cb.onConfirmNeeded(question,
                    () -> {
                        Trace.copilotUi("confirm_ok", "user_yes", "", pkg, label);
                        CopilotUiSupport.notifyActionInProgress(ctx, cb);
                        withForegroundRoot(ctx, svc,
                                r -> performClick(ctx, r, clickCriteria, target,
                                        clickHints, cb), cb);
                    },
                    () -> {
                        Trace.copilotUi("confirm_cancel", "user_no", "", pkg, label);
                        cb.onError("Clic annulé.");
                    });
        }, cb);
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
        withForegroundRoot(ctx, svc, root -> {
            String pkg = A11yRootPicker.packageOf(root);
            CopilotAppHints hints = CopilotAppHintsStore.get(ctx, pkg);
            String want = criteria.text != null ? criteria.text : "";
            if (!TextUtils.isEmpty(want)) {
                want = hints.resolveAlias(want);
                criteria.text = want;
            }
            criteria.strictText = hints.strictTextMatch;
            AccessibilityNodeInfo node = criteria.isEmpty()
                    ? A11yUiMatcher.findEditableRoot(root)
                    : A11yUiMatcher.findNode(root, criteria);
            if (node == null && (criteria.isEmpty()
                    || A11yUiMatcher.looksLikeBrowserSearchTarget(want))) {
                node = A11yUiMatcher.findBrowserSearchField(root);
            } else if (node == null) {
                // Cible LLM inventée sur navigateur — tente omnibox quand même
                String pkgLower = pkg != null ? pkg.toLowerCase(java.util.Locale.ROOT) : "";
                if (pkgLower.contains("chrome") || pkgLower.contains("brave")
                        || pkgLower.contains("browser") || pkgLower.contains("firefox")) {
                    node = A11yUiMatcher.findBrowserSearchField(root);
                }
            }
            if (node == null) {
                Trace.copilotUi("matcher_miss", "type_not_found",
                        "Champ introuvable", pkg, want);
                cb.onError("Je ne trouve pas le champ à remplir.");
                return;
            }
            try {
                highlightTarget(ctx, A11yUiMatcher.targetFromNode(node));
                String toType = EmojiNameMap.expand(value);
                boolean ok = A11yUiMatcher.performSetText(node, toType);
                if (!ok) {
                    ok = A11yUiMatcher.performClipboardPaste(ctx, node, toType);
                }
                if (ok) cb.onSuccess(ToolResult.text(""));
                else cb.onError("Impossible de saisir le texte sur cet élément.");
            } finally {
                node.recycle();
            }
        }, cb);
    }

    public static void executeScroll(Context ctx, PegaseAccessibilityService svc,
            JSONObject params, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        String direction = params.optString("direction", "down");
        withForegroundRoot(ctx, svc, root -> {
            boolean ok = A11yUiMatcher.performScroll(root, direction);
            if (ok) cb.onSuccess(ToolResult.text(""));
            else cb.onError("Impossible de faire défiler cette page.");
        }, cb);
    }

    public static void executeBack(Context ctx, PegaseAccessibilityService svc, ToolCallback cb) {
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        if (svc == null) {
            Trace.copilotUi("a11y_unavailable", "svc_null_back",
                    "Service null pendant retour", "", "");
            cb.onError("Service d'accessibilité pas encore prêt — réessaie.");
            return;
        }
        boolean ok = svc.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        if (ok) cb.onSuccess(ToolResult.text(""));
        else cb.onError("Impossible de revenir en arrière.");
    }

    private static void performClick(Context ctx, AccessibilityNodeInfo root,
            A11yUiMatcher.Criteria criteria, A11yUiMatcher.Target preview,
            CopilotAppHints hints, ToolCallback cb) {
        AccessibilityNodeInfo node = A11yUiMatcher.findNode(root, criteria);
        if (node == null) {
            ElementHighlightService.hide(ctx);
            String pkg = A11yRootPicker.packageOf(root);
            String want = criteria != null && criteria.text != null ? criteria.text : "";
            Trace.copilotUi("matcher_miss", "rematch_miss",
                    "Cible disparue avant clic", pkg, want);
            cb.onError("Je ne trouve plus l'élément à cliquer.");
            return;
        }
        try {
            android.graphics.Rect live = new android.graphics.Rect();
            node.getBoundsInScreen(live);
            if (preview != null && !A11yClickRematch.stillMatches(preview, node, live)) {
                ElementHighlightService.hide(ctx);
                String pkg = A11yRootPicker.packageOf(root);
                String want = criteria != null && criteria.text != null ? criteria.text : "";
                Trace.copilotUi("matcher_miss", "rematch_drift",
                        "Cible déplacée ou libellé changé avant clic", pkg, want);
                cb.onError("L'élément a changé à l'écran — réessaie.");
                return;
            }
            // Retirer le surlignage avant le geste (même NOT_TOUCHABLE, certains OEM
            // absorbent encore le dispatchGesture).
            ElementHighlightService.hide(ctx);

            CopilotAppHints h = hints != null ? hints : CopilotAppHints.empty("");
            boolean ok;
            String via;
            if (h.preferA11yFirst && !h.distrustA11yClickSuccess) {
                ok = A11yUiMatcher.performClick(node);
                via = ok ? "a11y" : "";
                if (!ok) {
                    ok = tapBounds(headerBand(live));
                    if (ok) via = "gesture";
                }
                if (!ok && preview != null) {
                    ok = tapTarget(preview);
                    if (ok) via = "gesture-preview";
                }
            } else {
                // Gesture d'abord : ACTION_CLICK renvoie souvent true sans effet
                // (Compose / Reddit / WebView / Play Store).
                ok = tapBounds(headerBand(live));
                via = ok ? "gesture" : "";
                if (!ok && !h.distrustA11yClickSuccess) {
                    ok = A11yUiMatcher.performClick(node);
                    if (ok) via = "a11y";
                }
                if (!ok && preview != null) {
                    ok = tapTarget(preview);
                    if (ok) via = "gesture-preview";
                }
            }
            if (ok) {
                String label = preview != null && !TextUtils.isEmpty(preview.text)
                        ? preview.text
                        : (preview != null ? UiExplainHelper.humanizeViewId(preview.viewId) : "");
                if (TextUtils.isEmpty(label) && node.getText() != null) {
                    label = node.getText().toString().trim();
                }
                if (TextUtils.isEmpty(label)) label = "l'élément";
                CharSequence pkg = node.getPackageName();
                android.util.Log.i("A11yUi", "click ok via=" + via
                        + " live=" + live.toShortString()
                        + " h=" + live.height()
                        + " clickable=" + node.isClickable()
                        + " web=" + looksLikeWebContent(node)
                        + " pkg=" + (pkg != null ? pkg : "")
                        + " label=" + label
                        + " hintsStrict=" + h.strictTextMatch
                        + " distrustA11y=" + h.distrustA11yClickSuccess);
                // Succès visible à l'écran — silence vocal (bulle / historique optionnel via "").
                cb.onSuccess(ToolResult.text(""));
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

    /** Chrome / WebView / Gecko : ACTION_CLICK a11y peu fiable. */
    static boolean looksLikeWebContent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence pkgCs = node.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "";
        if (pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("browser")
                || pkg.contains("webview") || pkg.equals("com.android.chrome")
                || pkg.equals("com.brave.browser") || pkg.equals("org.mozilla.firefox")
                || pkg.equals("com.microsoft.emmx") || pkg.equals("com.opera.browser")) {
            return true;
        }
        CharSequence clsCs = node.getClassName();
        String cls = clsCs != null ? clsCs.toString() : "";
        return cls.contains("WebView") || cls.contains("Chrome")
                || cls.contains("Gecko") || cls.contains("AwContents");
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

    /**
     * Enchaîne plusieurs actions UI (open / click / type / scroll / back)
     * avec attentes de stabilité — un seul appel outil agentique.
     * Tourne hors UI thread (settles + awaits confirm) ; actions a11y postées sur main.
     */
    public static void runSequence(Context ctx, PegaseAccessibilityService svc,
            JSONArray steps, ToolCallback cb) {
        if (steps == null || steps.length() == 0) {
            cb.onError("Séquence vide — indique steps=[{action,...},…].");
            return;
        }
        if (steps.length() > MAX_SEQUENCE_STEPS) {
            cb.onError("Trop d'étapes (max " + MAX_SEQUENCE_STEPS + ").");
            return;
        }
        if (svc == null) {
            cb.onError("Service d'accessibilité pas encore prêt — réessaie.");
            return;
        }
        CopilotUiSupport.notifyActionInProgress(ctx, cb);
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        SEQ_IO.execute(() -> runSequenceOnIo(appCtx, svc, steps, cb));
    }

    private static void runSequenceOnIo(Context ctx, PegaseAccessibilityService svc,
            JSONArray steps, ToolCallback cb) {
        StringBuilder replies = new StringBuilder();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) {
                deliverError(cb, "Étape " + (i + 1) + "/" + steps.length() + " : JSON invalide.");
                return;
            }
            String action = step.optString("action", "").trim().toLowerCase();
            if (action.isEmpty()) {
                deliverError(cb, "Étape " + (i + 1) + "/" + steps.length()
                        + " : action manquante.");
                return;
            }
            AtomicReference<String> spokenRef = new AtomicReference<>("");
            String err;
            String spoken;
            switch (action) {
                case "open":
                case "launch":
                case "open_app": {
                    String name = firstNonEmpty(
                            step.optString("name", ""),
                            step.optString("target", ""),
                            step.optString("package", ""),
                            step.optString("app", ""));
                    OpenAppTool.LaunchResult launched = OpenAppTool.launchApp(ctx, name);
                    if (!launched.ok) {
                        err = launched.error;
                        spoken = null;
                    } else {
                        err = null;
                        spoken = "";
                        if (launched.packageName != null && !launched.packageName.isEmpty()) {
                            err = waitForeground(svc, launched.packageName, FOREGROUND_TIMEOUT_MS);
                        }
                        if (err == null) waitTreeSettle(svc, SETTLE_TIMEOUT_MS);
                    }
                    break;
                }
                case "click":
                case "tap":
                    err = runOnMainBlocking(ctx, svc, step, spokenRef, cb, ActionKind.CLICK);
                    spoken = spokenRef.get();
                    if (err == null) waitTreeSettle(svc, SETTLE_TIMEOUT_MS);
                    break;
                case "type":
                case "set_text":
                    err = runOnMainBlocking(ctx, svc, step, spokenRef, cb, ActionKind.TYPE);
                    spoken = spokenRef.get();
                    if (err == null) waitTreeSettle(svc, SETTLE_TIMEOUT_MS);
                    break;
                case "scroll":
                    err = runOnMainBlocking(ctx, svc, step, spokenRef, cb, ActionKind.SCROLL);
                    spoken = spokenRef.get();
                    if (err == null) waitTreeSettle(svc, SETTLE_TIMEOUT_MS);
                    break;
                case "back":
                case "retour":
                case "go_back":
                    err = runOnMainBlocking(ctx, svc, step, spokenRef, cb, ActionKind.BACK);
                    spoken = spokenRef.get();
                    if (err == null) waitTreeSettle(svc, SETTLE_TIMEOUT_MS);
                    break;
                default:
                    err = "action inconnue « " + action + " »";
                    spoken = null;
                    break;
            }
            if (err != null) {
                deliverError(cb, "Étape " + (i + 1) + "/" + steps.length()
                        + " (" + action + ") : " + err);
                return;
            }
            if (spoken != null && !spoken.isEmpty()) {
                if (replies.length() > 0) replies.append(' ');
                replies.append(spoken.trim());
            }
        }
        String out = replies.length() == 0
                ? ""
                : replies.toString().trim();
        deliverSuccess(cb, ToolResult.text(out));
    }

    private enum ActionKind { CLICK, TYPE, SCROLL, BACK }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /** Commandes vocales / cibles LLM pour un retour navigateur / système. */
    static boolean looksLikeBackCommand(String text) {
        if (text == null) return false;
        String f = A11yUiMatcher.fold(text);
        if (f.isEmpty()) return false;
        return f.equals("retour")
                || f.equals("retour arriere")
                || f.equals("revenir")
                || f.equals("page precedente")
                || f.equals("precedent")
                || f.equals("back")
                || f.equals("go back")
                || f.equals("navigate back");
    }

    /** Attend que {@code packageName} soit au premier plan (ou timeout). null = ok. */
    static String waitForeground(PegaseAccessibilityService svc, String packageName,
            long timeoutMs) {
        if (svc == null || packageName == null || packageName.isEmpty()) return null;
        long deadline = System.currentTimeMillis() + Math.max(200L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = A11yRootPicker.preferForegroundRoot(svc);
            if (root != null) {
                try {
                    if (packageName.equals(A11yRootPicker.packageOf(root))) return null;
                } finally {
                    root.recycle();
                }
            }
            sleepQuiet(80L);
        }
        return "l'app n'est pas passée au premier plan à temps (" + packageName + ")";
    }

    /** Poll jusqu'à arbre a11y stable {@link #SETTLE_IDLE_MS}, ou timeout. */
    static void waitTreeSettle(PegaseAccessibilityService svc, long timeoutMs) {
        if (svc == null) return;
        long deadline = System.currentTimeMillis() + Math.max(SETTLE_IDLE_MS, timeoutMs);
        int lastSig = Integer.MIN_VALUE;
        long stableSince = -1L;
        while (System.currentTimeMillis() < deadline) {
            int sig = treeSignature(svc);
            long now = System.currentTimeMillis();
            if (sig == lastSig && sig != Integer.MIN_VALUE) {
                if (stableSince < 0L) stableSince = now;
                if (now - stableSince >= SETTLE_IDLE_MS) return;
            } else {
                lastSig = sig;
                stableSince = -1L;
            }
            sleepQuiet(90L);
        }
    }

    private static int treeSignature(PegaseAccessibilityService svc) {
        AccessibilityNodeInfo root = A11yRootPicker.preferForegroundRoot(svc);
        if (root == null) return -1;
        try {
            String pkg = A11yRootPicker.packageOf(root);
            int nodes = countNodes(root, 0, 400);
            CharSequence title = root.getContentDescription();
            int titleHash = title != null ? title.toString().hashCode() : 0;
            return ((pkg != null ? pkg : "").hashCode() * 31 + nodes) * 31 + titleHash;
        } finally {
            root.recycle();
        }
    }

    private static int countNodes(AccessibilityNodeInfo node, int acc, int max) {
        if (node == null || acc >= max) return acc;
        int n = acc + 1;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && n < max; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                n = countNodes(child, n, max);
            } finally {
                child.recycle();
            }
        }
        return n;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Exécute une action a11y sur le main thread et attend le callback (confirm inclus).
     * @return message d'erreur, ou null si succès
     */
    private static String runOnMainBlocking(Context ctx, PegaseAccessibilityService svc,
            JSONObject params, AtomicReference<String> spokenOut, ToolCallback parentCb,
            ActionKind kind) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicReference<String> spoken = new AtomicReference<>("");
        ToolCallback bridge = bridgingCallback(parentCb, spoken, error, done);
        MAIN.post(() -> {
            switch (kind) {
                case CLICK:
                    executeClick(ctx, svc, params, bridge);
                    break;
                case TYPE:
                    executeType(ctx, svc, params, bridge);
                    break;
                case SCROLL:
                    executeScroll(ctx, svc, params, bridge);
                    break;
                case BACK:
                    executeBack(ctx, svc, bridge);
                    break;
                default:
                    error.set("action interne inconnue");
                    done.countDown();
                    break;
            }
        });
        try {
            if (!done.await(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "délai dépassé (confirmation ou action)";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrompu";
        }
        if (spokenOut != null) spokenOut.set(spoken.get());
        return error.get();
    }

    private static ToolCallback bridgingCallback(ToolCallback parentConfirm,
            AtomicReference<String> spoken, AtomicReference<String> error,
            CountDownLatch done) {
        return new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                if (result != null && result.text != null) spoken.set(result.text);
                done.countDown();
            }

            @Override public void onSuccessAndExit(ToolResult result) {
                onSuccess(result);
            }

            @Override public void onError(String err) {
                error.set(err != null ? err : "erreur");
                done.countDown();
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                if (parentConfirm == null) {
                    error.set("Confirmation requise — impossible sans UI.");
                    done.countDown();
                    return;
                }
                parentConfirm.onConfirmNeeded(question, onConfirm, onCancel);
            }

            @Override
            public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                if (parentConfirm != null) {
                    parentConfirm.onChoiceNeeded(title, labels, onChosen, onCancel);
                } else {
                    error.set("Choix non supporté dans une séquence UI.");
                    done.countDown();
                }
            }

            @Override
            public void onProgress(String message) {
                if (parentConfirm != null) parentConfirm.onProgress(message);
            }
        };
    }

    private static void deliverSuccess(ToolCallback cb, ToolResult result) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onSuccess(result);
        } else {
            MAIN.post(() -> cb.onSuccess(result));
        }
    }

    private static void deliverError(ToolCallback cb, String err) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onError(err);
        } else {
            MAIN.post(() -> cb.onError(err));
        }
    }

    private interface RootTask {
        void run(AccessibilityNodeInfo root);
    }

    /**
     * Racine premier plan (hors overlay) puis garde-fou whitelist.
     * Ne confond plus « app non autorisée » / « écran illisible » avec
     * « service pas prêt » (getInstance null).
     */
    private static void withForegroundRoot(Context ctx, PegaseAccessibilityService svc,
            RootTask task, ToolCallback cb) {
        if (svc == null) {
            Trace.copilotUi("a11y_unavailable", "svc_null_root",
                    "Service null avant lecture écran", "", "");
            cb.onError("Service d'accessibilité pas encore prêt — réessaie.");
            return;
        }
        AccessibilityNodeInfo root = A11yRootPicker.preferForegroundRoot(svc);
        if (root == null) {
            cb.onError("Impossible de lire l'écran de l'app au premier plan "
                    + "(fenêtre masquée par l'overlay ou arbre a11y vide).");
            return;
        }
        try {
            if (!isForegroundAllowed(ctx, root)) {
                String pkg = A11yRootPicker.packageOf(root);
                Trace.copilotUi("whitelist_block", "package_not_allowed",
                        "App hors whitelist copilote", pkg, "");
                cb.onError("Cette app n'est pas autorisée pour le copilote"
                        + (pkg.isEmpty() ? "." : " (" + pkg + ").")
                        + " Ajoute-la dans Copilote → apps.");
                return;
            }
            task.run(root);
        } finally {
            root.recycle();
        }
    }
}
