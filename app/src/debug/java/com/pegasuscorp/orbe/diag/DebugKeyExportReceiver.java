package com.pegasuscorp.orbe.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DEBUG UNIQUEMENT (src/debug), protégé par DUMP (seul adb peut le déclencher) :
 * copie la clé Tavily dans un fichier PRIVÉ de l'appli, pour que Yannick la récupère sur
 * son PC (demande du 2026-11-23, banc de comparaison). Extra « clear » : efface ce fichier.
 *
 * <pre>
 * adb shell am broadcast -n com.pegasuscorp.orbe/.diag.DebugKeyExportReceiver
 * adb exec-out run-as com.pegasuscorp.orbe cat files/debug_export/tavily.key
 * adb shell am broadcast -n com.pegasuscorp.orbe/.diag.DebugKeyExportReceiver --ez clear true
 *
 * Import de la clé Brave (recherche privée) : le fichier temporaire est EFFACÉ après lecture.
 * adb push brave.key /data/local/tmp/brave.key
 * adb shell am broadcast -n com.pegasuscorp.orbe/.diag.DebugKeyExportReceiver --ez import_brave true
 * </pre>
 */
public class DebugKeyExportReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        File dir = new File(context.getFilesDir(), "debug_export");
        File f = new File(dir, "tavily.key");
        if (intent.getBooleanExtra("import_brave", false)) {
            File src = new File("/data/local/tmp/brave.key");
            try {
                String k = new String(java.nio.file.Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
                ApiKeyStore.setBraveKey(context, k);
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            return;
        }
        if (intent.getBooleanExtra("clear", false)) {
            f.delete();
            dir.delete();
            return;
        }
        try {
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(ApiKeyStore.getTavilyKey(context).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
