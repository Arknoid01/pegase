package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fil Pégase lié à un document Bureau ({@code files/bureau/chat-*.json}).
 * Isolé de l'historique chat voix/texte.
 */
public final class BureauChatStore {

    private static final String TAG = "BureauChatStore";

    public static final class Turn {
        public final boolean fromUser;
        public final String text;
        public final long atMs;

        public Turn(boolean fromUser, String text, long atMs) {
            this.fromUser = fromUser;
            this.text = text == null ? "" : text;
            this.atMs = atMs;
        }
    }

    private BureauChatStore() {}

    public static String docIdFor(String bureauFilename) {
        if (bureauFilename == null || bureauFilename.isEmpty()) return "session-default";
        String id = bureauFilename.replace(".md", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        return id.isEmpty() ? "session-default" : id;
    }

    public static File chatFile(Context ctx, String bureauFilename) {
        return new File(BureauSessionStore.dir(ctx), "chat-" + docIdFor(bureauFilename) + ".json");
    }

    public static List<Turn> load(Context ctx, String bureauFilename) {
        List<Turn> out = new ArrayList<>();
        File f = chatFile(ctx, bureauFilename);
        if (!f.isFile()) return out;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("turns");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                out.add(new Turn(t.optBoolean("fromUser", true),
                        t.optString("text", ""),
                        t.optLong("atMs", 0L)));
            }
        } catch (Exception e) {
            Log.w(TAG, "load chat", e);
        }
        return out;
    }

    public static void save(Context ctx, String bureauFilename, List<Turn> turns) {
        if (ctx == null) return;
        try {
            JSONArray arr = new JSONArray();
            if (turns != null) {
                for (Turn t : turns) {
                    if (t == null) continue;
                    arr.put(new JSONObject()
                            .put("fromUser", t.fromUser)
                            .put("text", t.text)
                            .put("atMs", t.atMs));
                }
            }
            JSONObject root = new JSONObject().put("turns", arr);
            File target = chatFile(ctx, bureauFilename);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "save chat", e);
        }
    }

    public static void append(Context ctx, String bureauFilename, boolean fromUser, String text) {
        List<Turn> turns = load(ctx, bureauFilename);
        turns.add(new Turn(fromUser, text, System.currentTimeMillis()));
        // Cap historique
        while (turns.size() > 80) turns.remove(0);
        save(ctx, bureauFilename, turns);
    }

    public static void clear(Context ctx, String bureauFilename) {
        save(ctx, bureauFilename, new ArrayList<>());
    }
}
