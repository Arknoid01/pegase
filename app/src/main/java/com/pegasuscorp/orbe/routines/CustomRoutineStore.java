package com.pegasuscorp.orbe.routines;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routines custom du brief du matin — CRUD sur {@code files/routines/routines.json}.
 * TTL expiré → suppression auto silencieuse au chargement / démarrage.
 */
public final class CustomRoutineStore {

    public enum Type {
        WEB_PAGE,
        WEB_SEARCH,
        LOAD_CONTEXT,
        REMINDER
    }

    public static final class CustomRoutine {
        public final String id;
        public final Type type;
        public final String query;
        public final String label;
        /** null = pas d'expiration. */
        public final Integer ttlDays;
        public final boolean active;
        public final int order;
        public final long createdAtMs;

        public CustomRoutine(String id, Type type, String query, String label,
                Integer ttlDays, boolean active, int order, long createdAtMs) {
            this.id = id;
            this.type = type;
            this.query = query != null ? query : "";
            this.label = label != null ? label : "";
            this.ttlDays = ttlDays;
            this.active = active;
            this.order = order;
            this.createdAtMs = createdAtMs;
        }

        public CustomRoutine withActive(boolean on) {
            return new CustomRoutine(id, type, query, label, ttlDays, on, order, createdAtMs);
        }

        public boolean isExpired(long nowMs) {
            if (ttlDays == null || ttlDays <= 0) return false;
            long limit = createdAtMs + TimeUnit.DAYS.toMillis(ttlDays);
            return nowMs >= limit;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("type", type.name());
            o.put("query", query);
            o.put("label", label);
            if (ttlDays != null) o.put("ttlDays", ttlDays.intValue());
            else o.put("ttlDays", JSONObject.NULL);
            o.put("active", active);
            o.put("order", order);
            o.put("createdAtMs", createdAtMs);
            return o;
        }

        static CustomRoutine fromJson(JSONObject o) {
            if (o == null) return null;
            String id = o.optString("id", "").trim();
            if (id.isEmpty()) return null;
            Type type = parseType(o.optString("type", "REMINDER"));
            Integer ttl = null;
            if (o.has("ttlDays") && !o.isNull("ttlDays")) {
                int d = o.optInt("ttlDays", -1);
                if (d > 0) ttl = d;
            }
            return new CustomRoutine(
                    id,
                    type,
                    o.optString("query", ""),
                    o.optString("label", ""),
                    ttl,
                    o.optBoolean("active", true),
                    o.optInt("order", 0),
                    o.optLong("createdAtMs", System.currentTimeMillis()));
        }
    }

    private static final Pattern ADD_PREFIX = Pattern.compile(
            "(?i)^(?:ajoute|ajout)\\s+(?:a|à)?\\s*(?:ma\\s+)?routine"
                    + "(?:\\s+du\\s+matin)?\\s*[:\\-–]?\\s*(.+)$");

    private static CustomRoutineStore instance;

    private final Context appContext;
    private final File file;
    private final List<CustomRoutine> routines = new ArrayList<>();

    private CustomRoutineStore(Context context) {
        appContext = context.getApplicationContext();
        file = PegaseFileSystem.get(appContext).routinesJson();
        loadFromDisk();
        purgeExpiredSilent();
    }

    public static synchronized CustomRoutineStore getInstance(Context context) {
        if (instance == null) {
            instance = new CustomRoutineStore(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public synchronized List<CustomRoutine> listAll() {
        purgeExpiredSilent();
        return Collections.unmodifiableList(new ArrayList<>(routines));
    }

    public synchronized List<CustomRoutine> listActive() {
        purgeExpiredSilent();
        List<CustomRoutine> out = new ArrayList<>();
        for (CustomRoutine r : routines) {
            if (r.active) out.add(r);
        }
        sortByOrder(out);
        return out;
    }

    /** @return nombre de routines TTL-expirées effacées. */
    public synchronized int purgeExpired() {
        return purgeExpiredSilent();
    }

    public synchronized CustomRoutine add(Type type, String query, String label, Integer ttlDays) {
        return add(type, query, label, ttlDays, System.currentTimeMillis());
    }

    /** Visible tests — createdAt injectable pour TTL. */
    public synchronized CustomRoutine add(Type type, String query, String label,
            Integer ttlDays, long createdAtMs) {
        String q = query != null ? query.trim() : "";
        String lab = !TextUtils.isEmpty(label) ? label.trim() : defaultLabel(type, q);
        int nextOrder = 0;
        for (CustomRoutine r : routines) {
            if (r.order >= nextOrder) nextOrder = r.order + 1;
        }
        CustomRoutine created = new CustomRoutine(
                UUID.randomUUID().toString(),
                type != null ? type : Type.REMINDER,
                q,
                lab,
                ttlDays != null && ttlDays > 0 ? ttlDays : null,
                true,
                nextOrder,
                createdAtMs);
        routines.add(created);
        persist();
        return created;
    }

    public synchronized boolean setActive(String id, boolean active) {
        for (int i = 0; i < routines.size(); i++) {
            CustomRoutine r = routines.get(i);
            if (r.id.equals(id)) {
                routines.set(i, r.withActive(active));
                persist();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean remove(String id) {
        for (int i = 0; i < routines.size(); i++) {
            if (routines.get(i).id.equals(id)) {
                routines.remove(i);
                persist();
                return true;
            }
        }
        return false;
    }

    /**
     * Parse « ajoute à ma routine du matin : cherche les résultats F1 ».
     * @return routine créée, ou null si la phrase ne match pas
     */
    public synchronized CustomRoutine addFromVoice(String utterance) {
        Draft draft = parseAddVoice(utterance);
        if (draft == null) return null;
        return add(draft.type, draft.query, draft.label, draft.ttlDays);
    }

    /** Parse sans persister — utilisable par les tests. */
    public static Draft parseAddVoice(String utterance) {
        if (utterance == null || utterance.trim().isEmpty()) return null;
        String text = utterance.trim();
        Matcher m = ADD_PREFIX.matcher(text);
        if (!m.find()) {
            String fold = SpeechInputNormalizer.fold(text);
            if (!fold.contains("routine") || !fold.contains("ajoute")) return null;
            int colon = Math.max(text.indexOf(':'), text.indexOf('：'));
            if (colon < 0) return null;
            String rest = text.substring(colon + 1).trim();
            if (rest.isEmpty()) return null;
            return draftFromPayload(rest);
        }
        String rest = m.group(1).trim();
        if (rest.isEmpty()) return null;
        return draftFromPayload(rest);
    }

    public static final class Draft {
        public final Type type;
        public final String query;
        public final String label;
        public final Integer ttlDays;

        Draft(Type type, String query, String label, Integer ttlDays) {
            this.type = type;
            this.query = query;
            this.label = label;
            this.ttlDays = ttlDays;
        }
    }

    private static Draft draftFromPayload(String rest) {
        String fold = SpeechInputNormalizer.fold(rest);
        Type type;
        String query = rest.trim();

        if (fold.contains("http://") || fold.contains("https://")
                || fold.startsWith("www.") || fold.contains(" ouvre ")) {
            type = Type.WEB_PAGE;
            query = extractUrlOrRest(rest);
        } else if (fold.contains("contexte") || fold.contains("charge le contexte")
                || fold.contains("charge contexte")) {
            type = Type.LOAD_CONTEXT;
            query = stripLeading(rest, "(?i)^(charge|ouvre|charge\\s+le)?\\s*(le\\s+)?contexte\\s*");
        } else if (fold.contains("rappel") || fold.startsWith("rappelle")
                || fold.contains("souvenir de") || fold.contains("n oublie pas")) {
            type = Type.REMINDER;
            query = stripLeading(rest,
                    "(?i)^(rappelle[- ]moi|rappel|n['']oublie\\s+pas|souvenir)\\s*(de\\s+|que\\s+)?");
        } else {
            type = Type.WEB_SEARCH;
            query = stripLeading(rest,
                    "(?i)^(cherche|recherche|regarde|trouve)\\s+(les?\\s+|les\\s+)?");
        }
        query = query.trim();
        if (query.isEmpty()) query = rest.trim();
        return new Draft(type, query, defaultLabel(type, query), null);
    }

    private static String extractUrlOrRest(String rest) {
        Matcher url = Pattern.compile("(https?://\\S+|www\\.\\S+)", Pattern.CASE_INSENSITIVE)
                .matcher(rest);
        if (url.find()) return url.group(1);
        return stripLeading(rest, "(?i)^(ouvre|affiche|va\\s+sur)\\s+");
    }

    private static String stripLeading(String text, String regex) {
        return text.replaceFirst(regex, "").trim();
    }

    private static String defaultLabel(Type type, String query) {
        if (TextUtils.isEmpty(query)) return type.name().toLowerCase(Locale.ROOT);
        String q = query.length() > 48 ? query.substring(0, 45) + "…" : query;
        switch (type) {
            case WEB_SEARCH: return "Recherche : " + q;
            case WEB_PAGE: return "Page : " + q;
            case LOAD_CONTEXT: return "Contexte : " + q;
            case REMINDER:
            default: return "Rappel : " + q;
        }
    }

    private static Type parseType(String raw) {
        if (raw == null) return Type.REMINDER;
        try {
            return Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Type.REMINDER;
        }
    }

    private int purgeExpiredSilent() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (int i = routines.size() - 1; i >= 0; i--) {
            if (routines.get(i).isExpired(now)) {
                routines.remove(i);
                removed++;
            }
        }
        if (removed > 0) persist();
        return removed;
    }

    private void loadFromDisk() {
        routines.clear();
        if (!file.exists() || file.length() == 0) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = in.read(buf);
            if (n <= 0) return;
            String raw = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return;
            JSONArray arr;
            if (raw.startsWith("{")) {
                arr = new JSONObject(raw).optJSONArray("routines");
            } else {
                arr = new JSONArray(raw);
            }
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                CustomRoutine r = CustomRoutine.fromJson(arr.optJSONObject(i));
                if (r != null) routines.add(r);
            }
            sortByOrder(routines);
        } catch (Exception ignored) {
            routines.clear();
        }
    }

    private void persist() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            JSONArray arr = new JSONArray();
            for (CustomRoutine r : routines) {
                arr.put(r.toJson());
            }
            JSONObject root = new JSONObject();
            root.put("routines", arr);
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(bytes);
            }
        } catch (Exception ignored) {}
    }

    private static void sortByOrder(List<CustomRoutine> list) {
        Collections.sort(list, Comparator.comparingInt(a -> a.order));
    }
}
