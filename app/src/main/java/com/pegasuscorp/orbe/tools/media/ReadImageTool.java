package com.pegasuscorp.orbe.tools.media;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import com.pegasuscorp.orbe.chat.OpenRouterVisionClient;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.io.File;

/** Décrit / analyse une photo : la dernière prise, ou une image donnée (content:// ou fichier). */
public final class ReadImageTool implements Tool {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "read_image";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.IMAGE;
    }

    @Override
    public String description() {
        return "read_image(source?:str, prompt?:str) — Décrit ou analyse une image (vision). "
                + "source = \"last_photo\" (dernière photo de la galerie, défaut), un content:// "
                + "ou un chemin de fichier. prompt = ce qu'il faut regarder (ex. lire le texte, "
                + "identifier un objet).";
    }

    @Override
    public com.pegasuscorp.orbe.tools.ToolRequirement requirement(Context ctx) {
        return com.pegasuscorp.orbe.tools.ToolRequirement.photos(ctx);
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String source = params.optString("source", "last_photo").trim();
        String prompt = params.optString("prompt", "").trim();
        if (prompt.isEmpty()) {
            prompt = "Décris cette image et extrais le texte important s'il y en a.";
        }
        Uri uri;
        try {
            uri = resolve(ctx, source);
        } catch (IllegalStateException e) {
            cb.onError(e.getMessage());
            return;
        }
        OpenRouterVisionClient.analyzeImageUri(ctx, uri, prompt,
                new OpenRouterVisionClient.Callback() {
                    @Override
                    public void onSuccess(String analysis) {
                        MAIN.post(() -> cb.onSuccess(ToolResult.text(analysis)));
                    }

                    @Override
                    public void onError(String message) {
                        MAIN.post(() -> cb.onError(message));
                    }
                });
    }

    private static Uri resolve(Context ctx, String source) {
        if (source.isEmpty() || "last_photo".equalsIgnoreCase(source)) {
            return latestPhoto(ctx);
        }
        if (source.startsWith("content://") || source.startsWith("file://")) {
            return Uri.parse(source);
        }
        File f = new File(source);
        if (!f.isFile() || !f.canRead()) {
            throw new IllegalStateException("Image introuvable ou illisible : " + source);
        }
        return Uri.fromFile(f);
    }

    private static Uri latestPhoto(Context ctx) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException(
                    "Autorise l'accès aux photos dans les réglages Android pour que je regarde ta galerie.");
        }
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (android.database.Cursor c = ctx.getContentResolver().query(collection,
                new String[]{MediaStore.Images.Media._ID}, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (SecurityException e) {
            throw new IllegalStateException("Accès aux photos refusé.");
        }
        throw new IllegalStateException("Aucune photo trouvée dans la galerie.");
    }
}
