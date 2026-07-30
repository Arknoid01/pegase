package com.pegasuscorp.orbe.voice;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Importe le modèle vocal Piper (ZIP ou dossier SAF) vers le stockage interne de l'app.
 */
public final class PiperModelImporter {

    public static final String HF_MODEL_HINT =
            "Hugging Face : csukuangfj/vits-piper-fr_FR-siwis-medium";

    public static class Result {
        public final boolean success;
        public final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private PiperModelImporter() {}

    public static Result importZip(Context context, Uri uri) {
        Context app = context.getApplicationContext();
        File destDir = modelDestDir(app);
        File tempZip = new File(app.getCacheDir(), "piper-import-" + System.currentTimeMillis() + ".zip");
        try {
            prepareDest(destDir);
            copyUriToFile(app.getContentResolver(), uri, tempZip);
            String stripPrefix = detectZipPrefix(collectZipEntryNames(tempZip));

            try (ZipInputStream extract = new ZipInputStream(
                    new BufferedInputStream(new java.io.FileInputStream(tempZip)))) {
                ZipEntry entry;
                while ((entry = extract.getNextEntry()) != null) {
                    if (shouldSkipZipEntry(entry.getName())) continue;
                    String relative = relativizeZipPath(entry.getName(), stripPrefix);
                    if (relative.isEmpty()) continue;
                    File out = new File(destDir, relative);
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        writeStream(extract, out);
                    }
                }
            }
            return finalizeImport(app, destDir);
        } catch (Exception e) {
            return Result.fail("Import ZIP échoué : " + e.getMessage());
        } finally {
            tempZip.delete();
        }
    }

    public static Result importFolder(Context context, Uri treeUri) {
        Context app = context.getApplicationContext();
        File destDir = modelDestDir(app);
        try {
            prepareDest(destDir);
            DocumentFile root = DocumentFile.fromTreeUri(app, treeUri);
            if (root == null || !root.isDirectory()) {
                return Result.fail("Dossier invalide");
            }
            DocumentFile source = findModelSource(root);
            if (source == null) {
                return Result.fail("Dossier Piper introuvable (.onnx + tokens.txt + espeak-ng-data)");
            }
            copyDocumentFolder(app, source, destDir);
            return finalizeImport(app, destDir);
        } catch (Exception e) {
            return Result.fail("Import dossier échoué : " + e.getMessage());
        }
    }

    private static File modelDestDir(Context app) {
        return PiperModelStore.defaultModelDir(app);
    }

    static void prepareDest(File destDir) {
        deleteRecursive(destDir);
        destDir.mkdirs();
    }

    static Result finalizeInstall(Context app, File destDir) {
        PiperModelStore.setModelDirPath(app, destDir.getAbsolutePath());
        PiperModelStore.setUsePiper(app, true);
        if (PiperModelStore.isModelReady(app)) {
            return Result.ok("Piper installé — voix prête");
        }
        return Result.fail("Fichiers copiés mais modèle incomplet "
                + "(il faut .onnx, tokens.txt et espeak-ng-data/)");
    }

    private static Result finalizeImport(Context app, File destDir) {
        return finalizeInstall(app, destDir);
    }

    private static DocumentFile findModelSource(DocumentFile root) {
        if (hasModelFiles(root)) return root;
        for (DocumentFile child : root.listFiles()) {
            if (child.isDirectory()) {
                if (PiperModelStore.DIR_NAME.equals(child.getName()) && hasModelFiles(child)) {
                    return child;
                }
                if (hasModelFiles(child)) return child;
            }
        }
        return null;
    }

    private static boolean hasModelFiles(DocumentFile dir) {
        if (dir == null || !dir.isDirectory()) return false;
        boolean hasOnnx = false;
        boolean hasTokens = false;
        boolean hasEspeak = false;
        for (DocumentFile f : dir.listFiles()) {
            String name = f.getName();
            if (name == null) continue;
            if (name.endsWith(".onnx")) hasOnnx = true;
            if ("tokens.txt".equals(name)) hasTokens = true;
            if ("espeak-ng-data".equals(name) && f.isDirectory()) hasEspeak = true;
        }
        return hasOnnx && hasTokens && hasEspeak;
    }

    private static void copyDocumentFolder(Context context, DocumentFile src, File destDir)
            throws Exception {
        for (DocumentFile child : src.listFiles()) {
            String name = child.getName();
            if (name == null) continue;
            File out = new File(destDir, name);
            if (child.isDirectory()) {
                out.mkdirs();
                copyDocumentFolder(context, child, out);
            } else {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = context.getContentResolver().openInputStream(child.getUri())) {
                    if (in == null) throw new IllegalStateException("Lecture impossible : " + name);
                    writeStream(in, out);
                }
            }
        }
    }

    private static Set<String> collectZipEntryNames(File zipFile) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream scan = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = scan.getNextEntry()) != null) {
                if (!entry.isDirectory()) names.add(entry.getName());
            }
        }
        return names;
    }

    private static void copyUriToFile(ContentResolver resolver, Uri uri, File dest) throws Exception {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Fichier illisible");
            writeStream(in, dest);
        }
    }

    private static String detectZipPrefix(Set<String> paths) {
        for (String path : paths) {
            if (path.startsWith(PiperModelStore.DIR_NAME + "/")) {
                return PiperModelStore.DIR_NAME + "/";
            }
        }
        String common = null;
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash <= 0) return "";
            String head = path.substring(0, slash + 1);
            if (common == null) common = head;
            else if (!common.equals(head)) return "";
        }
        return common != null ? common : "";
    }

    private static String relativizeZipPath(String path, String stripPrefix) {
        String p = path;
        if (stripPrefix != null && !stripPrefix.isEmpty() && p.startsWith(stripPrefix)) {
            p = p.substring(stripPrefix.length());
        }
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static boolean shouldSkipZipEntry(String name) {
        return name == null
                || name.startsWith("__MACOSX")
                || name.contains("/__MACOSX")
                || name.endsWith(".DS_Store");
    }

    private static void writeStream(InputStream in, File out) throws Exception {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                os.write(buf, 0, read);
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
