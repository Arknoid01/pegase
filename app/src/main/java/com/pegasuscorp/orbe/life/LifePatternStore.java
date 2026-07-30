package com.pegasuscorp.orbe.life;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rythmes de vie déclarés par l'utilisateur — 100 % local ({@code files/life/patterns.json}).
 * Contexte prompt + intentions (plages horaires).
 */
public final class LifePatternStore {

    private static final String TAG = "LifePatternStore";
    private static final String FILE = "patterns.json";

    /** Pattern « de 18h30 à 19h45 » / « 18:30-19:45 ». */
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:de\\s+)?(\\d{1,2})\\s*[h:]\\s*(\\d{0,2})\\s*(?:à|a|-|–)\\s*(\\d{1,2})\\s*[h:]\\s*(\\d{0,2})",
            Pattern.CASE_INSENSITIVE);

    public static final class LifePattern {
        public final String id;
        public final String label;
        public final String note;
        public final int startHour;
        public final int startMinute;
        public final int endHour;
        public final int endMinute;
        /** 1=dim … 7=sam (Calendar), vide = tous les jours. */
        public final List<Integer> daysOfWeek;
        public final boolean active;
        public final boolean injectPrompt;
        public final boolean suggestEnabled;
        public final long createdAtMs;

        public LifePattern(String id, String label, String note,
                int startHour, int startMinute, int endHour, int endMinute,
                List<Integer> daysOfWeek, boolean active, boolean injectPrompt,
                boolean suggestEnabled, long createdAtMs) {
            this.id = id;
            this.label = label != null ? label : "";
            this.note = note != null ? note : "";
            this.startHour = clampH(startHour);
            this.startMinute = clampM(startMinute);
            this.endHour = clampH(endHour);
            this.endMinute = clampM(endMinute);
            this.daysOfWeek = daysOfWeek != null
                    ? Collections.unmodifiableList(new ArrayList<>(daysOfWeek))
                    : Collections.emptyList();
            this.active = active;
            this.injectPrompt = injectPrompt;
            this.suggestEnabled = suggestEnabled;
            this.createdAtMs = createdAtMs;
        }

        public LifePattern withActive(boolean on) {
            return new LifePattern(id, label, note, startHour, startMinute, endHour, endMinute,
                    daysOfWeek, on, injectPrompt, suggestEnabled, createdAtMs);
        }

        /** Conserve la durée de la plage en décalant le début. */
        public LifePattern withShiftedStart(int newStartHour, int newStartMinute) {
            int oldStart = startHour * 60 + startMinute;
            int oldEnd = endHour * 60 + endMinute;
            int duration = oldEnd - oldStart;
            if (duration <= 0) duration += 24 * 60;
            int newStart = clampH(newStartHour) * 60 + clampM(newStartMinute);
            int newEnd = (newStart + duration) % (24 * 60);
            return new LifePattern(id, label, note,
                    newStart / 60, newStart % 60, newEnd / 60, newEnd % 60,
                    daysOfWeek, active, injectPrompt, suggestEnabled, createdAtMs);
        }

        public String intentionId() {
            return "life:" + id;
        }

        public String timeLabel() {
            return String.format(Locale.FRANCE, "%02d:%02d–%02d:%02d",
                    startHour, startMinute, endHour, endMinute);
        }

        /** true si now est dans [start, end) — wrap minuit supporté. */
        public boolean isActiveNow(Calendar cal) {
            if (!active) return false;
            if (!daysOfWeek.isEmpty()) {
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                if (!daysOfWeek.contains(dow)) return false;
            }
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int start = startHour * 60 + startMinute;
            int end = endHour * 60 + endMinute;
            if (start == end) return true;
            if (start < end) return now >= start && now < end;
            return now >= start || now < end;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label);
            o.put("note", note);
            o.put("startHour", startHour);
            o.put("startMinute", startMinute);
            o.put("endHour", endHour);
            o.put("endMinute", endMinute);
            JSONArray days = new JSONArray();
            for (Integer d : daysOfWeek) days.put(d.intValue());
            o.put("daysOfWeek", days);
            o.put("active", active);
            o.put("injectPrompt", injectPrompt);
            o.put("suggestEnabled", suggestEnabled);
            o.put("createdAtMs", createdAtMs);
            return o;
        }

        static LifePattern fromJson(JSONObject o) {
            if (o == null) return null;
            List<Integer> days = new ArrayList<>();
            JSONArray arr = o.optJSONArray("daysOfWeek");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) days.add(arr.optInt(i));
            }
            return new LifePattern(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("label", ""),
                    o.optString("note", ""),
                    o.optInt("startHour", 0),
                    o.optInt("startMinute", 0),
                    o.optInt("endHour", 0),
                    o.optInt("endMinute", 0),
                    days,
                    o.optBoolean("active", true),
                    o.optBoolean("injectPrompt", true),
                    o.optBoolean("suggestEnabled", true),
                    o.optLong("createdAtMs", System.currentTimeMillis()));
        }

        private static int clampH(int h) {
            return Math.max(0, Math.min(23, h));
        }

        private static int clampM(int m) {
            return Math.max(0, Math.min(59, m));
        }
    }

    private static LifePatternStore instance;
    private final File file;
    private List<LifePattern> cache;

    private LifePatternStore(Context ctx) {
        File dir = PegaseFileSystem.get(ctx).dir("life");
        file = new File(dir, FILE);
        cache = load();
    }

    public static synchronized LifePatternStore getInstance(Context ctx) {
        if (instance == null) {
            instance = new LifePatternStore(ctx.getApplicationContext());
        }
        return instance;
    }

    /** Tests. */
    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    /** Tests : vide le cache et le fichier. */
    public synchronized void clearAll() {
        cache.clear();
        save();
    }

    public synchronized List<LifePattern> listAll() {
        return new ArrayList<>(cache);
    }

    public synchronized List<LifePattern> listActiveNow() {
        Calendar cal = Calendar.getInstance();
        List<LifePattern> out = new ArrayList<>();
        for (LifePattern p : cache) {
            if (p.isActiveNow(cal)) out.add(p);
        }
        return out;
    }

    /** Bloc texte pour le prompt système (local). */
    public synchronized String promptBlock() {
        if (cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Rythmes de vie (déclarés par l'utilisateur, local) :\n");
        int n = 0;
        for (LifePattern p : cache) {
            if (!p.active || !p.injectPrompt) continue;
            n++;
            sb.append("- ").append(p.label.isEmpty() ? "Rythme" : p.label)
                    .append(" (").append(p.timeLabel()).append(")");
            if (!p.note.isEmpty()) sb.append(" — ").append(p.note);
            sb.append("\n");
        }
        if (n == 0) return "";
        sb.append("Tiens-en compte discrètement ; ne récite pas cette liste.\n");
        return sb.toString();
    }

    public synchronized LifePattern add(String label, String note,
            int startH, int startM, int endH, int endM) {
        LifePattern p = new LifePattern(
                UUID.randomUUID().toString(),
                label, note, startH, startM, endH, endM,
                Collections.emptyList(), true, true, true,
                System.currentTimeMillis());
        cache.add(p);
        save();
        return p;
    }

    public synchronized void setActive(String id, boolean active) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.set(i, cache.get(i).withActive(active));
                save();
                return;
            }
        }
    }

    public synchronized void remove(String id) {
        cache.removeIf(p -> p.id.equals(id));
        save();
    }

    /** Opt-in learning : décale le début en gardant la durée. */
    public synchronized boolean shiftStartKeepingDuration(String id, int startH, int startM) {
        if (TextUtils.isEmpty(id)) return false;
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.set(i, cache.get(i).withShiftedStart(startH, startM));
                save();
                return true;
            }
        }
        return false;
    }

    /**
     * Parse grossier : « ménage de 18h30 à 19h45 » / « travail ferme à 18h30 jusqu'à 19h45 ».
     * @return pattern créé ou null
     */
    public synchronized LifePattern addFromUtterance(String utterance) {
        if (TextUtils.isEmpty(utterance)) return null;
        Matcher m = TIME_RANGE.matcher(utterance);
        if (!m.find()) return null;
        int sh = parseInt(m.group(1), 0);
        int sm = parseInt(m.group(2), 0);
        int eh = parseInt(m.group(3), 0);
        int em = parseInt(m.group(4), 0);
        String label = utterance.substring(0, m.start()).trim();
        label = label.replaceAll("(?i)^(ajoute|note|enregistre)\\s+(un\\s+)?(rythme|vie|que)\\s*", "");
        label = label.replaceAll("(?i)^(à\\s+ma\\s+vie\\s*:?\\s*)", "").trim();
        if (label.isEmpty()) label = "Rythme " + String.format(Locale.FRANCE, "%02dh%02d", sh, sm);
        String note = utterance.trim();
        return add(label, note, sh, sm, eh, em);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private List<LifePattern> load() {
        List<LifePattern> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            byte[] raw = readAll(file);
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("patterns");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                LifePattern p = LifePattern.fromJson(arr.optJSONObject(i));
                if (p != null) out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "load", e);
        }
        return out;
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (LifePattern p : cache) arr.put(p.toJson());
            JSONObject root = new JSONObject().put("patterns", arr);
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        }
    }
}
