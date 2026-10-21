package com.pegasuscorp.orbe.inbox;

import android.app.PendingIntent;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversations WhatsApp / Messenger reçues par notification (files/inbox/notifications.json).
 *
 * Le texte est gardé sur le téléphone ; le lien « ouvrir cette conversation » de la
 * notification d'origine (un {@link PendingIntent}) ne peut pas être enregistré : il reste
 * en mémoire tant que Pégase tourne.
 */
public final class NotificationInboxStore {

    private static final int MAX_CONVERSATIONS = 150;
    private static final int MAX_MESSAGES = 20;

    /** Un message reçu (expéditeur vide = conversation à deux). */
    public static final class Message {
        public final String sender;
        public final String text;
        public final long timeMs;

        public Message(String sender, String text, long timeMs) {
            this.sender = sender != null ? sender : "";
            this.text = text != null ? text : "";
            this.timeMs = timeMs;
        }
    }

    public static final class Conversation {
        public final InboxItem.Source source;
        public final String name;
        public final boolean group;
        /** Messages non lus connus, du plus ancien au plus récent. */
        public final List<Message> messages;
        public final long lastTimeMs;
        /** 0 = lu (ouvert depuis Pégase ou vu lu par le relevé web). */
        public final int unread;

        Conversation(InboxItem.Source source, String name, boolean group,
                List<Message> messages, long lastTimeMs, int unread) {
            this.source = source;
            this.name = name;
            this.group = group;
            this.messages = messages;
            this.lastTimeMs = lastTimeMs;
            this.unread = unread;
        }

        public String key() {
            return keyOf(source, name);
        }

        public Message last() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<String, PendingIntent> OPEN_INTENTS = new ConcurrentHashMap<>();
    private static LinkedHashMap<String, Conversation> cache;

    private NotificationInboxStore() {}

    public static String keyOf(InboxItem.Source source, String name) {
        return source.name() + "|" + stripInvisible(name);
    }

    /**
     * WhatsApp préfixe parfois le nom d'un groupe d'une marque de sens (U+200E) :
     * sans ce nettoyage, le même groupe apparaissait deux fois.
     */
    public static String stripInvisible(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]", "").trim();
    }

    /**
     * Enregistre les messages d'une notification. Renvoie {@code true} si au moins un
     * message est nouveau (pour ne pas re-sonner sur une simple mise à jour).
     *
     * @param messages messages non lus présents dans la notification (ancien → récent)
     */
    public static boolean record(Context ctx, InboxItem.Source source, String name, boolean group,
            List<Message> messages, PendingIntent open) {
        if (name == null || name.trim().isEmpty() || messages == null || messages.isEmpty()) {
            return false;
        }
        String key = keyOf(source, name);
        if (open != null) OPEN_INTENTS.put(key, open);
        synchronized (LOCK) {
            LinkedHashMap<String, Conversation> all = load(ctx);
            Conversation old = all.get(key);
            long oldLast = old != null ? old.lastTimeMs : 0L;
            // Une notification de messagerie contient tous les non-lus de la conversation :
            // seul un message plus récent que le dernier connu est « nouveau ».
            int fresh = countNewer(messages, oldLast);
            if (old != null && fresh == 0) return false;
            List<Message> merged = new ArrayList<>(old != null ? old.messages : new ArrayList<>());
            for (Message m : messages) {
                if (!contains(merged, m)) merged.add(m);
            }
            merged.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
            while (merged.size() > MAX_MESSAGES) merged.remove(0);
            int unread = old != null && old.unread > 0 ? old.unread + fresh : fresh;
            all.remove(key);
            all.put(key, new Conversation(source, name.trim(), group, merged,
                    merged.get(merged.size() - 1).timeMs, unread));
            while (all.size() > MAX_CONVERSATIONS) {
                all.remove(all.keySet().iterator().next());
            }
            save(ctx, all);
            return true;
        }
    }

    /** Conversation ouverte depuis Pégase : plus de non-lus (le texte reste). */
    public static void markRead(Context ctx, String key) {
        synchronized (LOCK) {
            LinkedHashMap<String, Conversation> all = load(ctx);
            Conversation c = all.get(key);
            if (c == null || c.unread == 0) return;
            all.put(key, new Conversation(c.source, c.name, c.group, c.messages, c.lastTimeMs, 0));
            save(ctx, all);
        }
    }

    public static PendingIntent openIntent(String key) {
        return OPEN_INTENTS.get(key);
    }

    public static void forgetOpenIntent(String key) {
        OPEN_INTENTS.remove(key);
    }

    public static List<Conversation> all(Context ctx) {
        synchronized (LOCK) {
            return new ArrayList<>(load(ctx).values());
        }
    }

    public static Conversation get(Context ctx, String key) {
        synchronized (LOCK) {
            return load(ctx).get(key);
        }
    }

    private static boolean contains(List<Message> list, Message m) {
        for (Message x : list) {
            if (x.timeMs == m.timeMs && x.text.equals(m.text) && x.sender.equals(m.sender)) {
                return true;
            }
        }
        return false;
    }

    private static int countNewer(List<Message> messages, long after) {
        int n = 0;
        for (Message m : messages) if (m.timeMs > after) n++;
        return n;
    }

    // ── Fichier ──────────────────────────────────────────────────────────

    private static LinkedHashMap<String, Conversation> load(Context ctx) {
        if (cache != null) return cache;
        cache = new LinkedHashMap<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return cache;
            JSONArray arr = new JSONArray(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<Message> msgs = new ArrayList<>();
                JSONArray ma = o.optJSONArray("messages");
                for (int j = 0; ma != null && j < ma.length(); j++) {
                    JSONObject m = ma.getJSONObject(j);
                    msgs.add(new Message(m.optString("sender"), m.optString("text"),
                            m.optLong("time")));
                }
                Conversation c = new Conversation(
                        InboxItem.Source.valueOf(o.getString("source")), o.getString("name"),
                        o.optBoolean("group"), msgs, o.optLong("last"), o.optInt("unread"));
                cache.put(c.key(), c);
            }
        } catch (Exception ignored) {
            // Fichier abîmé : on repart de zéro plutôt que de bloquer la Messagerie.
        }
        return cache;
    }

    private static void save(Context ctx, LinkedHashMap<String, Conversation> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Conversation c : all.values()) {
                JSONArray ma = new JSONArray();
                for (Message m : c.messages) {
                    ma.put(new JSONObject().put("sender", m.sender).put("text", m.text)
                            .put("time", m.timeMs));
                }
                arr.put(new JSONObject().put("source", c.source.name()).put("name", c.name)
                        .put("group", c.group).put("messages", ma).put("last", c.lastTimeMs)
                        .put("unread", c.unread));
            }
            File f = file(ctx);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {}
    }

    private static File file(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "inbox");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "notifications.json");
    }
}
