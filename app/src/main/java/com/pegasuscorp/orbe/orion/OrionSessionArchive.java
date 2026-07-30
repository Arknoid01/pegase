package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Archive conversation Orion sur disque ({@code files/orion-archives}),
 * un fichier markdown par session, purge après N jours.
 * Toutes les écritures passent par un executor dédié — jamais sur le thread UI.
 */
public final class OrionSessionArchive {

    private static final String TAG = "OrionSessionArchive";
    private static final String DIR_NAME = "orion-archives";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orion-session-archive");
        t.setDaemon(true);
        return t;
    });

    /** Fichier de la session ouverte — touché uniquement depuis {@link #IO}. */
    private static File currentFile;

    private OrionSessionArchive() {}

    /** Crée le fichier session (en-tête). No-op si une session est déjà ouverte. */
    public static void openSession(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> openSessionSync(app));
    }

    /**
     * Ajoute un tour en fin de fichier. Ouvre une session si besoin.
     * Ignore les textes vides. N'échoue jamais vers l'appelant.
     */
    public static void appendTurn(Context ctx, boolean fromUser, String text) {
        if (ctx == null) return;
        if (text == null || text.trim().isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        final boolean user = fromUser;
        final String body = text.trim();
        IO.execute(() -> {
            try {
                if (currentFile == null) openSessionSync(app);
                if (currentFile == null) return;
                String block = "## " + (user ? "User" : "Orion") + "\n\n"
                        + body + "\n\n";
                appendUtf8(currentFile, block);
            } catch (Exception e) {
                Log.w(TAG, "appendTurn", e);
            }
        });
    }

    /** Ligne de fin + ferme la session courante. */
    public static void closeSession(Context ctx) {
        IO.execute(() -> {
            try {
                if (currentFile != null && currentFile.isFile()) {
                    String end = "---\nFin : "
                            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date())
                            + "\n";
                    appendUtf8(currentFile, end);
                }
            } catch (Exception e) {
                Log.w(TAG, "closeSession", e);
            } finally {
                currentFile = null;
            }
        });
    }

    /**
     * Supprime les archives plus vieilles que {@code days} jours
     * (basé sur {@link File#lastModified()}).
     * À appeler hors thread principal.
     * @return nombre de fichiers supprimés
     */
    public static int purgeOlderThan(Context ctx, int days) {
        if (ctx == null || days < 0) return 0;
        try {
            File dir = archiveDir(ctx.getApplicationContext());
            if (!dir.isDirectory()) return 0;
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return 0;
            long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
            int removed = 0;
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                if (f.lastModified() < cutoff) {
                    if (f.delete()) removed++;
                }
            }
            return removed;
        } catch (Exception e) {
            Log.w(TAG, "purgeOlderThan", e);
            return 0;
        }
    }

    /** Purge async (démarrage app) — n'utilise pas le thread UI. */
    public static void purgeOlderThanAsync(Context ctx, int days) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            int n = purgeOlderThan(app, days);
            if (n > 0) Log.i(TAG, "purge: " + n + " archive(s) > " + days + "j");
        });
    }

    static void resetForTests() {
        IO.execute(() -> currentFile = null);
    }

    private static void openSessionSync(Context app) {
        try {
            if (currentFile != null) return;
            File dir = archiveDir(app);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "mkdir failed: " + dir.getAbsolutePath());
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            File f = new File(dir, "orion_" + stamp + ".md");
            String started = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            String header = "# Session Orion\n\n"
                    + "Début : " + started + "\n\n";
            writeUtf8(f, header, false);
            currentFile = f;
        } catch (Exception e) {
            Log.w(TAG, "openSession", e);
            currentFile = null;
        }
    }

    private static File archiveDir(Context app) {
        return new File(app.getFilesDir(), DIR_NAME);
    }

    private static void writeUtf8(File file, String text, boolean append) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            fos.write((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void appendUtf8(File file, String text) throws Exception {
        writeUtf8(file, text, true);
    }
}
