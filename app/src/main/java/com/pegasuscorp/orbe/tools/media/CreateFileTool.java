package com.pegasuscorp.orbe.tools.media;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Crée un fichier texte dans le cache et propose de le partager.
 */
public final class CreateFileTool implements Tool {

    @Override public String id() { return "create_file"; }

    @Override public ToolTag tag() { return ToolTag.CREATE_FILE; }

    @Override
    public String description() {
        return "create_file(filename:str, content:str) — Génère un fichier texte/CSV/MD "
                + "et propose de le partager";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String filename = params.optString("filename", "document.txt");
        String content  = params.optString("content", "");

        if (content.isEmpty()) {
            cb.onError("Le contenu du fichier est vide.");
            return;
        }

        // Sécurise le nom de fichier
        filename = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (!filename.contains(".")) filename += ".txt";

        try {
            File dir = new File(ctx.getCacheDir(), "generated");
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, filename);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }

            Uri uri = FileProvider.getUriForFile(
                    ctx,
                    ctx.getPackageName() + ".fileprovider",
                    outFile);

            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType(mimeFor(filename))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, filename)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);

            ctx.startActivity(Intent.createChooser(share, "Partager " + filename)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

            cb.onSuccess(ToolResult.text("Voilà, " + filename + " est prêt. Tu peux le télécharger ou le partager."));
        } catch (Exception e) {
            cb.onError("Impossible de créer le fichier : " + e.getMessage());
        }
    }

    private static String mimeFor(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        return "text/plain";
    }
}
