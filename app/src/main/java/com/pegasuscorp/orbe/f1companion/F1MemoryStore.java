package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Persistance mémoire fan + miroir contexte nommé {@code f1_fan} + souvenirs {@code f1}.
 */
public final class F1MemoryStore {

    private static final String TAG = "F1MemoryStore";
    public static final String CONTEXT_KEYWORD = "f1_fan";
    private static final String MEMORY_CATEGORY = "f1";

    private F1MemoryStore() {}

    public static File memoryFile(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "f1");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "fan_memory.json");
    }

    public static F1FanMemory load(Context ctx) {
        File f = memoryFile(ctx);
        if (!f.isFile()) return new F1FanMemory();
        try {
            byte[] raw = readAll(f);
            if (raw.length == 0) return new F1FanMemory();
            return F1FanMemory.fromJson(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w(TAG, "load", e);
            return new F1FanMemory();
        }
    }

    public static void save(Context ctx, F1FanMemory mem) {
        if (ctx == null || mem == null) return;
        try {
            writeAll(memoryFile(ctx), mem.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            mirrorContext(ctx, mem);
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    public static void mirrorContext(Context ctx) {
        mirrorContext(ctx, load(ctx));
    }

    public static void mirrorContext(Context ctx, F1FanMemory mem) {
        if (ctx == null || mem == null) return;
        try {
            ContextualFileStore store = ContextualFileStore.getInstance(ctx);
            if (mem.isEmpty() && FavoriteTeamsStore.selectedTeams(ctx).isEmpty()) {
                return;
            }
            String md = mem.toMarkdown(FavoriteTeamsStore.selectedTeams(ctx));
            store.save(CONTEXT_KEYWORD, md);
            // Pas de load() ici — évite de coller f1_fan sur toutes les discussions.
        } catch (Exception e) {
            Log.w(TAG, "mirrorContext", e);
        }
    }

    /** Attache le contexte fan pour le tour en cours (outil F1 explicite). */
    public static void loadIntoPrompt(Context ctx) {
        try {
            ContextualFileStore.getInstance(ctx).load(CONTEXT_KEYWORD);
        } catch (Exception ignored) {
            mirrorContext(ctx);
            try {
                ContextualFileStore.getInstance(ctx).load(CONTEXT_KEYWORD);
            } catch (Exception ignored2) {}
        }
    }

    public static F1FanMemory.Take addTake(Context ctx, String text, WeekendSnapshot snap) {
        if (text == null || text.trim().isEmpty()) return null;
        F1FanMemory mem = load(ctx);
        F1FanMemory.Take t = new F1FanMemory.Take();
        t.text = text.trim();
        t.atMs = System.currentTimeMillis();
        if (snap != null) {
            t.gp = snap.event != null ? snap.event : "";
            t.sessionKey = snap.sessionKey;
        }
        mem.takes.add(t);
        trimTakes(mem);
        save(ctx, mem);
        syncPermanent(ctx, "Avis F1 : " + t.text
                + (t.gp.isEmpty() ? "" : " (" + t.gp + ")"));
        return t;
    }

    public static F1FanMemory.Prediction addPrediction(Context ctx, String text,
            WeekendSnapshot snap) {
        if (text == null || text.trim().isEmpty()) return null;
        F1FanMemory mem = load(ctx);
        F1FanMemory.Prediction p = new F1FanMemory.Prediction();
        p.text = text.trim();
        p.atMs = System.currentTimeMillis();
        if (snap != null) {
            p.gp = snap.event != null ? snap.event : "";
            p.sessionKey = snap.sessionKey;
        }
        mem.predictions.add(p);
        trimPredictions(mem);
        save(ctx, mem);
        syncPermanent(ctx, "Pronostic F1 : " + p.text
                + (p.gp.isEmpty() ? "" : " (" + p.gp + ")"));
        return p;
    }

    public static void addNote(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return;
        F1FanMemory mem = load(ctx);
        String note = text.trim();
        mem.notes.remove(note);
        mem.notes.add(note);
        while (mem.notes.size() > F1FanMemory.MAX_NOTES) mem.notes.remove(0);
        save(ctx, mem);
        syncPermanent(ctx, "Préférence F1 : " + note);
    }

    /**
     * Résout les pronostics ouverts contre le podium / vainqueur de la fiche.
     * @return nombre de pronostics nouvellement résolus
     */
    public static int resolveAgainstRace(Context ctx, WeekendSnapshot snap) {
        if (ctx == null || snap == null || !snap.hasRaceResults()) return 0;
        F1FanMemory mem = load(ctx);
        String winner = "";
        String winnerTeam = "";
        WeekendSnapshot.ResultRow w = snap.winner();
        if (w != null) {
            winner = w.driver != null ? w.driver : "";
            winnerTeam = w.team != null ? w.team : "";
        }
        String podium = snap.podiumLine() != null ? snap.podiumLine() : "";
        String outcome = "Vainqueur : " + (winner.isEmpty() ? "?" : winner)
                + (podium.isEmpty() ? "" : " · Podium : " + podium);
        int resolved = 0;
        for (F1FanMemory.Prediction p : mem.predictions) {
            if (p.resolved) continue;
            // Lier au GP courant si session connue, sinon tout ouvert récent
            if (p.sessionKey > 0 && snap.sessionKey > 0 && p.sessionKey != snap.sessionKey) {
                continue;
            }
            p.resolved = true;
            p.outcome = outcome;
            p.correct = scorePrediction(p.text, winner, winnerTeam, podium);
            resolved++;
            String mark = Boolean.TRUE.equals(p.correct) ? "bon" : "raté";
            syncPermanent(ctx, "Pronostic F1 " + mark + " : " + p.text + " → " + outcome);
        }
        if (resolved > 0) save(ctx, mem);
        return resolved;
    }

    /**
     * Heuristique simple : le texte cite-t-il le vainqueur / son équipe / un du podium ?
     */
    static Boolean scorePrediction(String text, String winner, String winnerTeam, String podium) {
        if (text == null || text.isEmpty()) return null;
        String hay = text.toLowerCase(Locale.ROOT);
        boolean hit = false;
        if (winner != null && !winner.isEmpty()) {
            String[] parts = winner.toLowerCase(Locale.ROOT).split("\\s+");
            for (String part : parts) {
                if (part.length() >= 4 && hay.contains(part)) {
                    hit = true;
                    break;
                }
            }
        }
        if (!hit && winnerTeam != null && !winnerTeam.isEmpty()) {
            String team = winnerTeam.toLowerCase(Locale.ROOT);
            for (String token : team.split("\\s+")) {
                if (token.length() >= 4 && hay.contains(token)) {
                    hit = true;
                    break;
                }
            }
        }
        // "podium" / "top 3" sans nom → indéterminé
        if (!hit && (hay.contains("podium") || hay.contains("top 3"))) {
            return null;
        }
        if (!hit) return false;
        // Si le user dit "pas X" / "jamais X" et X = vainqueur → faux
        if (winner != null) {
            for (String part : winner.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (part.length() < 4) continue;
                if (Pattern.compile("\\b(pas|jamais|sans)\\s+" + Pattern.quote(part))
                        .matcher(hay).find()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void clearAll(Context ctx) {
        save(ctx, new F1FanMemory());
    }

    private static void syncPermanent(Context ctx, String content) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            MemoryRepository.getInstance(ctx).addPermanentMemory(
                    new MemoryEntry(MEMORY_CATEGORY, content, 0.8, today));
        } catch (Exception e) {
            Log.w(TAG, "syncPermanent", e);
        }
    }

    private static void trimTakes(F1FanMemory mem) {
        while (mem.takes.size() > F1FanMemory.MAX_TAKES) mem.takes.remove(0);
    }

    private static void trimPredictions(F1FanMemory mem) {
        while (mem.predictions.size() > F1FanMemory.MAX_PREDICTIONS) mem.predictions.remove(0);
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1_000_000)];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
