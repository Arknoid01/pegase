package com.pegasuscorp.orbe.mail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Dernière liste relevée par compte (files/mail/&lt;adresse&gt;.json) : affichage immédiat
 * à l'ouverture, avant que l'actualisation IMAP réponde.
 */
public final class MailCache {

    private MailCache() {}

    public static void save(Context ctx, String account, List<MailMessage> mails, long nowMs) {
        try {
            JSONArray arr = new JSONArray();
            for (MailMessage m : mails) {
                arr.put(new JSONObject().put("uid", m.uid).put("name", m.fromName)
                        .put("address", m.fromAddress).put("subject", m.subject)
                        .put("date", m.dateMs).put("seen", m.seen).put("snippet", m.snippet));
            }
            File f = file(ctx, account);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("taken_at", nowMs).put("mails", arr).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {}
    }

    public static List<MailMessage> load(Context ctx, String account) {
        List<MailMessage> out = new ArrayList<>();
        try {
            File f = file(ctx, account);
            if (!f.exists()) return out;
            JSONObject root = new JSONObject(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("mails");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new MailMessage(account, o.optLong("uid"), o.optString("name"),
                        o.optString("address"), o.optString("subject"), o.optLong("date"),
                        o.optBoolean("seen"), o.optString("snippet")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static long takenAt(Context ctx, String account) {
        try {
            File f = file(ctx, account);
            if (!f.exists()) return 0L;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8)).optLong("taken_at");
        } catch (Exception e) {
            return 0L;
        }
    }

    static void clear(Context ctx, String account) {
        file(ctx, account).delete();
    }

    private static File file(Context ctx, String account) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "mail");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, account.replaceAll("[^A-Za-z0-9@._-]", "_") + ".json");
    }
}
