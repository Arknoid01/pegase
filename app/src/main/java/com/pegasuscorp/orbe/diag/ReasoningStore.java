package com.pegasuscorp.orbe.diag;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cartes de raisonnement indexées par empreinte de la réponse affichée.
 * Conservées sur disque (48 dernières, {@code files/diag/reasoning_cards.json}) pour survivre
 * au redémarrage ; les cartes éphémères ne sont pas écrites.
 */
public final class ReasoningStore {

    private static final int MAX = 48;

    private static final LinkedHashMap<String, ReasoningCard> BY_KEY =
            new LinkedHashMap<String, ReasoningCard>(MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ReasoningCard> eldest) {
                    return size() > MAX;
                }
            };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static File file;

    private ReasoningStore() {}

    /** Charge les cartes sauvegardées (appel unique au démarrage de l'appli). */
    public static synchronized void init(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "diag");
        file = new File(dir, "reasoning_cards.json");
        BY_KEY.clear();
        try {
            if (!file.isFile()) return;
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                ReasoningCard card = ReasoningCard.fromStoreJson(e.optJSONObject("card"));
                String key = e.optString("key");
                if (card != null && !key.isEmpty()) BY_KEY.put(key, card);
            }
        } catch (Exception ignored) {
            // fichier illisible : on repart de zéro, il sera réécrit au prochain put
        }
    }

    /** Écrit l'état courant (instantané pris sous verrou, écriture hors thread appelant). */
    private static void save() {
        final File target = file;
        if (target == null) return;
        final JSONArray snapshot = new JSONArray();
        for (Map.Entry<String, ReasoningCard> e : BY_KEY.entrySet()) {
            if (e.getValue().ephemeral) continue;
            try {
                snapshot.put(new JSONObject().put("key", e.getKey())
                        .put("card", e.getValue().toStoreJson()));
            } catch (Exception ignored) {
            }
        }
        IO.execute(() -> {
            try {
                File dir = target.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                File tmp = new File(dir, target.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(target)) {
                    Files.move(tmp.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                Trace.error("io_write", "ReasoningStore.save : " + e.getClass().getSimpleName());
            }
        });
    }

    /** Attend la fin des écritures en cours (tests). */
    static void awaitIdle() throws Exception {
        IO.submit(() -> { }).get();
    }

    public static synchronized void put(String replyText, ReasoningCard card) {
        if (card == null) return;
        String key = fingerprint(replyText);
        if (key.isEmpty()) return;
        BY_KEY.put(key, card);
        save();
    }

    public static synchronized ReasoningCard findForReply(String replyText) {
        String key = fingerprint(replyText);
        if (key.isEmpty()) return null;
        ReasoningCard exact = BY_KEY.get(key);
        if (exact != null) return exact;
        // Fallback : préfixe (texte nettoyé légèrement différent)
        for (Map.Entry<String, ReasoningCard> e : BY_KEY.entrySet()) {
            if (key.startsWith(e.getKey()) || e.getKey().startsWith(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static synchronized void clear() {
        BY_KEY.clear();
        save();
    }

    /** Empreinte stable pour lier message UI ↔ carte. */
    public static String fingerprint(String replyText) {
        if (replyText == null) return "";
        String t = replyText.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        if (t.length() > 160) t = t.substring(0, 160);
        return t;
    }
}
