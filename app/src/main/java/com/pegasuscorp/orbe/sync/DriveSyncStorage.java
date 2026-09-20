package com.pegasuscorp.orbe.sync;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lecture/écriture des ops sync via SAF (Google Drive app — sans API OAuth).
 *
 * Arborescence dans le dossier choisi ({@link DriveSyncLayout#DRIVE_FOLDER_NAME}) :
 * <pre>
 *   {deviceId}/op-000001.json
 *   {@link SyncPeers#PC}/op-000001.json
 * </pre>
 *
 * Import PC (script bash) : lit {@code android-*}/ uniquement.
 * Import téléphone : lit {@link SyncPeers#PC}/ via SAF.
 */
public final class DriveSyncStorage {

    private DriveSyncStorage() {}

    public static boolean writeOp(Context context, SyncOp op) {
        if (context == null || op == null || !DriveSyncPrefs.isConfigured(context)) return false;
        try {
            Context app = context.getApplicationContext();
            DocumentFile root = DocumentFile.fromTreeUri(app, DriveSyncPrefs.getFolderUri(app));
            if (root == null || !root.canWrite()) return false;
            String deviceId = SyncDevice.get(app).deviceId;
            DocumentFile deviceDir = ensureDirectory(root, deviceId);
            if (deviceDir == null) return false;
            String name = opFileName(op.seq);
            DocumentFile existing = findChild(deviceDir, name);
            if (existing != null) {
                //noinspection ResultOfMethodCallIgnored
                existing.delete();
            }
            DocumentFile file = deviceDir.createFile("application/json", name);
            if (file == null) return false;
            try (OutputStream out = app.getContentResolver().openOutputStream(file.getUri())) {
                if (out == null) return false;
                out.write(op.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
                return true;
            }
        } catch (Exception e) {
            com.pegasuscorp.orbe.diag.Trace.error("io_write",
                    "DriveSyncStorage.writeOp : " + e.getClass().getSimpleName());
            return false;
        }
    }

    public static List<SyncOp> listInboundPcOps(Context context) {
        if (!DriveSyncPrefs.isConfigured(context)) return Collections.emptyList();
        Context app = context.getApplicationContext();
        DocumentFile root = DocumentFile.fromTreeUri(app, DriveSyncPrefs.getFolderUri(app));
        if (root == null) return Collections.emptyList();
        DocumentFile pcDir = ensureDirectory(root, SyncPeers.PC);
        if (pcDir == null) return Collections.emptyList();
        List<SyncOp> out = new ArrayList<>();
        for (DocumentFile file : pcDir.listFiles()) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name == null || !name.startsWith("op-") || !name.endsWith(".json")) continue;
            SyncOp op = readOpFile(app, file);
            if (op != null) out.add(op);
        }
        out.sort(Comparator.comparingLong(o -> o.seq));
        return out;
    }

    private static SyncOp readOpFile(Context app, DocumentFile file) {
        try (InputStream in = app.getContentResolver().openInputStream(file.getUri())) {
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return SyncOp.fromJson(new JSONObject(sb.toString()));
        } catch (Exception e) {
            return null;
        }
    }

    private static DocumentFile ensureDirectory(DocumentFile parent, String name) {
        DocumentFile existing = findChildDir(parent, name);
        if (existing != null) return existing;
        return parent.createDirectory(name);
    }

    private static DocumentFile findChildDir(DocumentFile parent, String name) {
        DocumentFile f = findChild(parent, name);
        return f != null && f.isDirectory() ? f : null;
    }

    private static DocumentFile findChild(DocumentFile parent, String name) {
        if (parent == null || name == null) return null;
        for (DocumentFile child : parent.listFiles()) {
            if (child != null && name.equals(child.getName())) return child;
        }
        return null;
    }

    static String opFileName(long seq) {
        return String.format(Locale.US, "op-%06d.json", seq);
    }
}
