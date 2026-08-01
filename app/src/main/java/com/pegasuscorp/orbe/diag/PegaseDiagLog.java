package com.pegasuscorp.orbe.diag;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logs locaux KWS + crashes (JSONL). Complète Logcat pour debug wake / Bluetooth.
 */
public final class PegaseDiagLog {

    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final String KWS_FILE = "kws_lifecycle.jsonl";
    private static final String CRASH_FILE = "crashes.jsonl";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile File kwsFile;
    private static volatile File crashFile;
    private static volatile Context appContext;

    private PegaseDiagLog() {}

    public static void install(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        initFiles(app);
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logCrash(app, thread, throwable);
            if (prev != null) {
                prev.uncaughtException(thread, throwable);
            }
        });
        kws(app, "diag_installed", new JSONObject());
    }

    public static File kwsLogFile(Context ctx) {
        initFiles(ctx.getApplicationContext());
        return kwsFile;
    }

    public static File crashLogFile(Context ctx) {
        initFiles(ctx.getApplicationContext());
        return crashFile;
    }

    /** Événement cycle de vie KWS / wake. */
    public static void kws(Context ctx, String event, JSONObject fields) {
        if (ctx == null || event == null || event.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        IO.execute(() -> appendKws(app, event, fields));
    }

    /** Depuis classes statiques (KwsDiagnostics) après {@link #install}. */
    public static void kwsFromStatic(String event, JSONObject fields) {
        Context app = appContext;
        if (app == null || event == null || event.isEmpty()) return;
        IO.execute(() -> appendKws(app, event, fields));
    }

    private static void logCrash(Context app, Thread thread, Throwable throwable) {
        try {
            initFiles(app);
            JSONObject o = base("crash");
            o.put("thread", thread != null ? thread.getName() : "?");
            o.put("throwable", throwable != null ? throwable.getClass().getName() : "null");
            o.put("message", throwable != null && throwable.getMessage() != null
                    ? throwable.getMessage() : "");
            o.put("stack", stackTrace(throwable));
            appendLine(crashFile, o);
        } catch (Exception ignored) {}
    }

    public static void shareLogs(Context ctx) {
        Context app = ctx.getApplicationContext();
        initFiles(app);
        ArrayList<Uri> uris = new ArrayList<>();
        String authority = app.getPackageName() + ".fileprovider";
        try {
            if (kwsFile != null && kwsFile.isFile() && kwsFile.length() > 0) {
                uris.add(FileProvider.getUriForFile(app, authority, kwsFile));
            }
            if (crashFile != null && crashFile.isFile() && crashFile.length() > 0) {
                uris.add(FileProvider.getUriForFile(app, authority, crashFile));
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(app,
                    "Impossible de préparer les logs : " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (uris.isEmpty()) {
            android.widget.Toast.makeText(app,
                    "Aucun log KWS/crash enregistré pour l'instant.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Intent share = uris.size() == 1
                ? new Intent(Intent.ACTION_SEND)
                .setType("application/x-ndjson")
                .putExtra(Intent.EXTRA_STREAM, uris.get(0))
                : new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("application/x-ndjson")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        app.startActivity(Intent.createChooser(share, "Exporter logs KWS / crash")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void initFiles(Context app) {
        if (kwsFile != null && crashFile != null) return;
        File dir = new File(app.getFilesDir(), "diag");
        if (!dir.exists()) dir.mkdirs();
        kwsFile = new File(dir, KWS_FILE);
        crashFile = new File(dir, CRASH_FILE);
    }

    private static void appendKws(Context app, String event, JSONObject fields) {
        try {
            initFiles(app);
            JSONObject o = base(event);
            if (fields != null) {
                java.util.Iterator<String> keys = fields.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    o.put(k, fields.opt(k));
                }
            }
            appendLine(kwsFile, o);
        } catch (Exception ignored) {}
    }

    private static JSONObject base(String event) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ts", isoNow());
        o.put("event", event);
        o.put("pid", Process.myPid());
        o.put("process", currentProcessName());
        return o;
    }

    private static void appendLine(File file, JSONObject line) throws Exception {
        if (file == null) return;
        rotateIfNeeded(file);
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write((line.toString() + '\n').getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void rotateIfNeeded(File file) {
        if (file.length() <= MAX_BYTES) return;
        File old = new File(file.getParentFile(), file.getName() + ".old");
        if (old.exists()) old.delete();
        file.renameTo(old);
    }

    private static String isoNow() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String n = Application.getProcessName();
            if (n != null && !n.isEmpty()) return n;
        }
        return "pid:" + Process.myPid();
    }

    private static String stackTrace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
