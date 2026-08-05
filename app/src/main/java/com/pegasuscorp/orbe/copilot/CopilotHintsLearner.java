package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.tools.ToolCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Apprentissage hints a11y post-run (Sanna-lite) — opt-in via confirmation.
 * N'écrit jamais en mémoire durable / RAG ; uniquement {@link CopilotAppHintsStore}.
 */
public final class CopilotHintsLearner {

    private static final String TAG = "HintsLearner";
    private static final int MIN_TRACE_CHARS = 24;
    private static final int MAX_PROPOSALS = 3;

    public static final class Proposal {
        public final List<String> notes;
        public final List<String[]> aliases; // [from, to]

        public Proposal(List<String> notes, List<String[]> aliases) {
            this.notes = notes != null ? notes : new ArrayList<>();
            this.aliases = aliases != null ? aliases : new ArrayList<>();
        }

        public boolean isEmpty() {
            return notes.isEmpty() && aliases.isEmpty();
        }

        public String confirmQuestion(String packageName) {
            StringBuilder sb = new StringBuilder();
            sb.append("Retenir pour cette app");
            if (!TextUtils.isEmpty(packageName)) {
                sb.append(" (").append(shortPkg(packageName)).append(')');
            }
            sb.append(" ?\n");
            for (String n : notes) {
                sb.append("• ").append(n.trim()).append('\n');
            }
            for (String[] a : aliases) {
                if (a != null && a.length >= 2) {
                    sb.append("• «").append(a[0]).append("» → «")
                            .append(a[1]).append("»\n");
                }
            }
            return sb.toString().trim();
        }
    }

    private CopilotHintsLearner() {}

    /**
     * Propose des hints puis exécute {@code then} (succès/erreur outil).
     * Appeler depuis le thread IO de la boucle UI.
     */
    public static void maybeLearnThen(Context ctx, String packageName, String goal,
            String trace, String outcome, ToolCallback cb, Runnable then) {
        Runnable cont = then != null ? then : () -> {};
        if (ctx == null || cb == null || TextUtils.isEmpty(packageName)
                || TextUtils.isEmpty(trace) || trace.trim().length() < MIN_TRACE_CHARS) {
            cont.run();
            return;
        }
        Proposal proposal;
        try {
            proposal = propose(ctx, packageName, goal, trace, outcome);
        } catch (Exception e) {
            Log.w(TAG, "propose failed", e);
            cont.run();
            return;
        }
        if (proposal == null || proposal.isEmpty()) {
            cont.run();
            return;
        }
        String question = proposal.confirmQuestion(packageName);
        CountDownLatchWait.waitConfirm(cb, question,
                () -> {
                    apply(ctx, packageName, proposal);
                    cont.run();
                },
                cont);
    }

    /** Parse JSON de proposition (tests / LLM). */
    public static Proposal parseProposalJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new Proposal(null, null);
        }
        String json = extractJsonObject(raw.trim());
        if (json == null) return new Proposal(null, null);
        try {
            JSONObject o = new JSONObject(json);
            List<String> notes = new ArrayList<>();
            JSONArray na = o.optJSONArray("notes");
            if (na != null) {
                for (int i = 0; i < na.length() && notes.size() < MAX_PROPOSALS; i++) {
                    String n = na.optString(i, "").trim();
                    if (n.length() >= 8 && !looksLikeViewId(n)) notes.add(n);
                }
            }
            List<String[]> aliases = new ArrayList<>();
            JSONArray aa = o.optJSONArray("aliases");
            if (aa != null) {
                for (int i = 0; i < aa.length() && aliases.size() < MAX_PROPOSALS; i++) {
                    JSONObject a = aa.optJSONObject(i);
                    if (a == null) continue;
                    String from = a.optString("from", a.optString("said", "")).trim();
                    String to = a.optString("to", a.optString("label", "")).trim();
                    if (from.length() >= 2 && to.length() >= 2
                            && !looksLikeViewId(from) && !looksLikeViewId(to)) {
                        aliases.add(new String[]{from, to});
                    }
                }
            }
            // Cap total 3 items
            while (notes.size() + aliases.size() > MAX_PROPOSALS && !notes.isEmpty()) {
                notes.remove(notes.size() - 1);
            }
            return new Proposal(notes, aliases);
        } catch (Exception e) {
            return new Proposal(null, null);
        }
    }

    static String buildLearnPrompt(String packageName, String goal,
            String trace, String outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu analyses une session copilote a11y pour en tirer des hints réutilisables.\n");
        sb.append("App : ").append(packageName).append('\n');
        sb.append("Objectif : ").append(goal != null ? goal : "").append('\n');
        sb.append("Résultat : ").append(outcome != null ? outcome : "").append('\n');
        sb.append("Trace :\n").append(trace != null ? trace : "").append("\n\n");
        sb.append("Réponds UNIQUEMENT un JSON (sans markdown) :\n");
        sb.append("{\"notes\":[\"…\"],\"aliases\":[{\"from\":\"…\",\"to\":\"…\"}]}\n");
        sb.append("Règles : 0 à 3 items au total ; notes courtes en français ; ");
        sb.append("alias = ce que l'utilisateur dit → libellé visible à l'écran ; ");
        sb.append("JAMAIS de viewId / resource-id ; si rien d'utile → {\"notes\":[],\"aliases\":[]}.\n");
        return sb.toString();
    }

    private static Proposal propose(Context ctx, String packageName, String goal,
            String trace, String outcome) throws Exception {
        String prompt = buildLearnPrompt(packageName, goal, trace, outcome);
        String raw = PegaseSession.get(ctx).completeCopilotReflectionSync(prompt);
        return parseProposalJson(raw);
    }

    private static void apply(Context ctx, String packageName, Proposal p) {
        if (ctx == null || p == null || TextUtils.isEmpty(packageName)) return;
        for (String n : p.notes) {
            CopilotAppHintsStore.addNote(ctx, packageName, n);
        }
        for (String[] a : p.aliases) {
            if (a != null && a.length >= 2) {
                CopilotAppHintsStore.setAlias(ctx, packageName, a[0], a[1]);
            }
        }
    }

    private static boolean looksLikeViewId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.contains(":id/")) return true;
        return t.matches("(?i)^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+:id/.+");
    }

    private static String shortPkg(String pkg) {
        if (pkg == null) return "";
        int i = pkg.lastIndexOf('.');
        return i >= 0 && i < pkg.length() - 1 ? pkg.substring(i + 1) : pkg;
    }

    static String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /** Latch confirm sur main — évite de bloquer si callback déjà sur main. */
    private static final class CountDownLatchWait {
        static void waitConfirm(ToolCallback cb, String question,
                Runnable onYes, Runnable onNo) {
            java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean yes =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> cb.onConfirmNeeded(question,
                    () -> {
                        yes.set(true);
                        latch.countDown();
                    },
                    () -> {
                        yes.set(false);
                        latch.countDown();
                    }));
            try {
                if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    onNo.run();
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onNo.run();
                return;
            }
            if (yes.get()) onYes.run();
            else onNo.run();
        }
    }
}
