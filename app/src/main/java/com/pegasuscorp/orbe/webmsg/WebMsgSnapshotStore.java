package com.pegasuscorp.orbe.webmsg;

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
 * Dernier relevé de la liste des discussions de chaque messagerie web (WhatsApp, Messenger).
 *
 * Écrit par {@link WebMessagingActivity} pendant qu'elle est ouverte, lu par l'outil
 * {@code messageries}. Reste sur le téléphone (files/webmsg/&lt;service&gt;.json).
 */
public final class WebMsgSnapshotStore {

    public static final String WHATSAPP = "whatsapp";
    public static final String MESSENGER = "messenger";

    /** Une discussion telle qu'affichée dans la liste du site. */
    public static final class Chat {
        public final String name;
        public final String time;
        public final String last;
        public final int unread;
        /** Discussion de groupe : {@link #last} commence par « Expéditeur : ». */
        public final boolean group;

        public Chat(String name, String time, String last, int unread, boolean group) {
            this.name = name != null ? name : "";
            this.time = time != null ? time : "";
            this.last = last != null ? last : "";
            this.unread = Math.max(0, unread);
            this.group = group;
        }
    }

    public static final class Snapshot {
        public final String service;
        /** Heure du relevé (ms epoch). */
        public final long takenAtMs;
        public final List<Chat> chats;

        public Snapshot(String service, long takenAtMs, List<Chat> chats) {
            this.service = service;
            this.takenAtMs = takenAtMs;
            this.chats = chats != null ? chats : new ArrayList<>();
        }

        public int unreadChats() {
            int n = 0;
            for (Chat c : chats) if (c.unread > 0) n++;
            return n;
        }
    }

    private WebMsgSnapshotStore() {}

    /**
     * Parse la sortie du script de relevé : {@code [{"name","time","last","unread"}…]}.
     * Retourne {@code null} si ce n'est pas une liste (page de connexion, chargement…).
     */
    public static List<Chat> parseChats(String json) {
        if (json == null) return null;
        String t = json.trim();
        if (!t.startsWith("[")) return null;
        try {
            JSONArray arr = new JSONArray(t);
            List<Chat> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                if (name.isEmpty()) continue;
                out.add(new Chat(name, o.optString("time", "").trim(),
                        o.optString("last", "").trim(), o.optInt("unread", 0),
                        o.optBoolean("group", false)));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context ctx, String service, List<Chat> chats, long nowMs) {
        try {
            JSONArray arr = new JSONArray();
            for (Chat c : chats) {
                arr.put(new JSONObject().put("name", c.name).put("time", c.time)
                        .put("last", c.last).put("unread", c.unread).put("group", c.group));
            }
            JSONObject root = new JSONObject().put("taken_at", nowMs).put("chats", arr);
            File f = file(ctx, service);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {}
    }

    /** Dernier relevé, ou {@code null} si jamais relevé. */
    public static Snapshot load(Context ctx, String service) {
        try {
            File f = file(ctx, service);
            if (!f.exists()) return null;
            JSONObject root = new JSONObject(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            List<Chat> chats = parseChats(root.optJSONArray("chats") != null
                    ? root.getJSONArray("chats").toString() : "[]");
            return new Snapshot(service, root.optLong("taken_at", 0L), chats);
        } catch (Exception e) {
            return null;
        }
    }

    private static File file(Context ctx, String service) {
        File dir = new File(ctx.getFilesDir(), "webmsg");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, service + ".json");
    }
}
