package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Journal d'entraînement : une ligne JSON par tour (phrase exacte de l'utilisateur,
 * outils appelés avec paramètres complets, qui les a choisis, succès, réponse finale,
 * modèle utilisé). Sert à distiller les choix du gros modèle cloud vers un petit modèle local.
 *
 * <p>Reste sur le téléphone ({@code files/training/}), jamais purgé automatiquement
 * (contrairement à {@link Trace}, 7 jours). Rotation par fichier de {@link #MAX_FILE_BYTES}.
 * Désactivable, exportable et effaçable depuis « Apprends à me connaître ».
 */
public final class TrainingJournal {

    private static final String PREFS = "training_journal";
    private static final String KEY_ENABLED = "enabled";
    /** En mode local seul : une 2ᵉ ligne JSONL avec la réponse cloud (référence d'entraînement). */
    private static final String KEY_CLOUD_REFERENCE = "cloud_reference";
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_RESULT_CHARS = 600;
    private static final int MAX_REPLY_CHARS = 2000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();

    private static Context appContext;
    private static File dir;

    /** Tour en cours (null = aucun). Accès sous LOCK. */
    private static JSONObject turn;
    private static JSONArray turnTools;
    private static JSONObject pendingTool;
    private static Boolean nextToolLocalRouter;

    private TrainingJournal() {}

    public static void init(Context ctx) {
        if (ctx == null) return;
        synchronized (LOCK) {
            appContext = ctx.getApplicationContext();
            dir = new File(appContext.getFilesDir(), "training");
        }
    }

    // ---- Réglage ----

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            synchronized (LOCK) { resetTurn(); }
        }
    }

    public static boolean isCloudReferenceEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CLOUD_REFERENCE, false);
    }

    public static void setCloudReferenceEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_CLOUD_REFERENCE, enabled).apply();
    }

    /**
     * Réponse cloud de référence (hors UI), pour comparer au petit modèle local.
     * Écrit une ligne distincte avec {@code kind=cloud_reference}.
     */
    public static void recordCloudReference(String userText, String intent, String reply,
            String backend) {
        if (appContext == null || !isEnabled(appContext)) return;
        String user = userText != null ? userText.trim() : "";
        if (user.isEmpty() || reply == null || reply.trim().isEmpty()) return;
        JSONObject o = new JSONObject();
        put(o, "kind", "cloud_reference");
        put(o, "t", System.currentTimeMillis());
        put(o, "user", clip(user, MAX_RESULT_CHARS * 2));
        put(o, "intent", intent != null ? intent : "");
        put(o, "backend", backend != null ? backend : "cloud");
        put(o, "reply", clip(reply, MAX_REPLY_CHARS));
        final File d = dir;
        if (d == null) return;
        IO.execute(() -> append(d, o.toString()));
    }

    // ---- Capture d'un tour ----

    /** Début d'un tour utilisateur. Ignoré si le texte est vide. */
    public static void begin(Context ctx, String userText, String intent) {
        if (ctx != null && appContext == null) init(ctx);
        if (appContext == null || !isEnabled(appContext)) return;
        String text = userText != null ? userText.trim() : "";
        if (text.isEmpty()) return;
        synchronized (LOCK) {
            resetTurn();
            turn = new JSONObject();
            turnTools = new JSONArray();
            put(turn, "t", System.currentTimeMillis());
            put(turn, "user", text);
            put(turn, "intent", intent != null ? intent : "");
        }
    }

    /** Le tour a été décidé par un autre modèle que le backend de conversation (ex. local). */
    public static void noteChooser(String label) {
        synchronized (LOCK) {
            if (turn != null && label != null) put(turn, "chooser", label);
        }
    }

    /** Précise si le prochain outil vient du routeur local (true) ou du LLM (false). */
    public static void noteRouting(boolean localRouter) {
        synchronized (LOCK) { nextToolLocalRouter = localRouter; }
    }

    public static void noteToolStart(String toolId, JSONObject params) {
        synchronized (LOCK) {
            if (turn == null) return;
            pendingTool = new JSONObject();
            put(pendingTool, "tool", toolId != null ? toolId.trim() : "");
            put(pendingTool, "params", params != null ? copy(params) : new JSONObject());
            if (nextToolLocalRouter != null) {
                put(pendingTool, "local_router", nextToolLocalRouter.booleanValue());
            }
            nextToolLocalRouter = null;
        }
    }

    public static void noteToolEnd(String toolId, boolean ok, String resultOrError) {
        synchronized (LOCK) {
            if (turn == null) return;
            JSONObject step = pendingTool;
            if (step == null || (toolId != null && !toolId.trim().equals(step.optString("tool")))) {
                step = new JSONObject();
                put(step, "tool", toolId != null ? toolId.trim() : "");
            }
            put(step, "ok", ok);
            put(step, "result", clip(resultOrError, MAX_RESULT_CHARS));
            turnTools.put(step);
            pendingTool = null;
        }
    }

    /** Fin du tour : écrit la ligne. backend = « Groq/openai/gpt-oss-120b », « local »… */
    public static void end(String reply, String backend) {
        final String line;
        synchronized (LOCK) {
            if (turn == null) return;
            if (pendingTool != null) {
                put(pendingTool, "ok", false);
                put(pendingTool, "result", "(pas de fin d'outil)");
                turnTools.put(pendingTool);
            }
            put(turn, "tools", turnTools);
            String chooser = turn.optString("chooser", "");
            put(turn, "backend", !chooser.isEmpty() ? chooser : (backend != null ? backend : ""));
            put(turn, "reply", clip(reply, MAX_REPLY_CHARS));
            line = turn.toString();
            resetTurn();
        }
        final File d = dir;
        if (d == null) return;
        IO.execute(() -> append(d, line));
    }

    // ---- Export / effacement ----

    /** Nombre de tours enregistrés (tous fichiers). Lecture disque : hors thread UI de préférence. */
    public static int count() {
        int n = 0;
        for (File f : files()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    /** Concatène tous les fichiers dans {@code out} (JSONL). */
    public static void exportTo(File out) throws Exception {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            for (File f : files()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    int k;
                    while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                }
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) { resetTurn(); }
        IO.execute(() -> {
            for (File f : files()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        });
    }

    // ---- Interne ----

    /** Fichiers du plus ancien au plus récent ; journal.jsonl (courant) en dernier. */
    private static List<File> files() {
        List<File> out = new ArrayList<>();
        File d = dir;
        if (d == null || !d.isDirectory()) return out;
        File[] arr = d.listFiles((x, name) -> name.startsWith("journal") && name.endsWith(".jsonl"));
        if (arr == null) return out;
        Arrays.sort(arr, (a, b) -> {
            boolean ac = a.getName().equals("journal.jsonl");
            boolean bc = b.getName().equals("journal.jsonl");
            if (ac != bc) return ac ? 1 : -1;
            return a.getName().compareTo(b.getName());
        });
        out.addAll(Arrays.asList(arr));
        return out;
    }

    private static void append(File d, String line) {
        try {
            if (!d.isDirectory() && !d.mkdirs()) return;
            File cur = new File(d, "journal.jsonl");
            if (cur.isFile() && cur.length() > MAX_FILE_BYTES) {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                //noinspection ResultOfMethodCallIgnored
                cur.renameTo(new File(d, "journal-" + stamp + ".jsonl"));
                cur = new File(d, "journal.jsonl");
            }
            try (FileOutputStream fos = new FileOutputStream(cur, true)) {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static void resetTurn() {
        turn = null;
        turnTools = null;
        pendingTool = null;
        nextToolLocalRouter = null;
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
