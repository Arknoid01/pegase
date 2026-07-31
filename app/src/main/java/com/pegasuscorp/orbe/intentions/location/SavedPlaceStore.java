package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Lieux nommés persistés (SharedPreferences) — maison, travail, restaurants.
 */
public final class SavedPlaceStore {

    private static final String PREFS = "pegase_saved_places";
    private static final String KEY_JSON = "places_json";
    private static final float DEFAULT_RADIUS_M = 120f;

    private static volatile SavedPlaceStore instance;

    private final Context app;
    private List<SavedPlace> cache = Collections.emptyList();

    private SavedPlaceStore(Context ctx) {
        this.app = ctx.getApplicationContext();
        reload();
    }

    public static SavedPlaceStore getInstance(Context ctx) {
        if (ctx == null) return null;
        if (instance == null) {
            synchronized (SavedPlaceStore.class) {
                if (instance == null) {
                    instance = new SavedPlaceStore(ctx);
                }
            }
        }
        return instance;
    }

    static void resetInstanceForTests() {
        instance = null;
    }

    public synchronized List<SavedPlace> listAll() {
        return new ArrayList<>(cache);
    }

    public synchronized SavedPlace findAt(double lat, double lon) {
        if (!isValidCoord(lat, lon)) return null;
        SavedPlace best = null;
        float bestDist = Float.MAX_VALUE;
        for (SavedPlace p : cache) {
            if (!p.contains(lat, lon)) continue;
            float d = LocationSituationReader.distanceM(p.lat, p.lon, lat, lon);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    public synchronized SavedPlace upsert(SavedPlace.Type type, String label,
            double lat, double lon, float radiusM) {
        if (type == null || !isValidCoord(lat, lon)) return null;
        float r = radiusM > 0 ? radiusM : DEFAULT_RADIUS_M;
        String id = type.name().toLowerCase(Locale.US);
        String trimmed = label != null ? label.trim() : "";
        SavedPlace place = new SavedPlace(id, trimmed, lat, lon, r, type);
        List<SavedPlace> next = new ArrayList<>();
        boolean replaced = false;
        for (SavedPlace p : cache) {
            if (type == p.type) {
                next.add(place);
                replaced = true;
            } else {
                next.add(p);
            }
        }
        if (!replaced) next.add(place);
        cache = Collections.unmodifiableList(next);
        persist();
        return place;
    }

    public synchronized SavedPlace addPlace(SavedPlace.Type type, String label,
            double lat, double lon, float radiusM) {
        if (type == SavedPlace.Type.HOME || type == SavedPlace.Type.WORK) {
            return upsert(type, label, lat, lon, radiusM);
        }
        if (!isValidCoord(lat, lon)) return null;
        float r = radiusM > 0 ? radiusM : DEFAULT_RADIUS_M;
        String id = type.name().toLowerCase(Locale.US) + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        SavedPlace place = new SavedPlace(id, label, lat, lon, r, type);
        List<SavedPlace> next = new ArrayList<>(cache);
        next.add(place);
        cache = Collections.unmodifiableList(next);
        persist();
        return place;
    }

    public synchronized SavedPlace getByType(SavedPlace.Type type) {
        if (type == null) return null;
        for (SavedPlace p : cache) {
            if (type == p.type) return p;
        }
        return null;
    }

    public synchronized void removeById(String id) {
        if (id == null || id.isEmpty()) return;
        List<SavedPlace> next = new ArrayList<>();
        for (SavedPlace p : cache) {
            if (!id.equals(p.id)) next.add(p);
        }
        if (next.size() == cache.size()) return;
        cache = Collections.unmodifiableList(next);
        persist();
    }

    public synchronized SavedPlace addOther(String label, double lat, double lon, float radiusM) {
        if (!isValidCoord(lat, lon)) return null;
        String id = "other_" + UUID.randomUUID().toString().substring(0, 8);
        SavedPlace place = new SavedPlace(id, label, lat, lon, radiusM, SavedPlace.Type.OTHER);
        List<SavedPlace> next = new ArrayList<>(cache);
        next.add(place);
        cache = Collections.unmodifiableList(next);
        persist();
        return place;
    }

    public synchronized void clearAll() {
        cache = Collections.emptyList();
        persist();
    }

    public synchronized void reload() {
        cache = Collections.unmodifiableList(readAll());
    }

    private List<SavedPlace> readAll() {
        String raw = prefs().getString(KEY_JSON, "[]");
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        List<SavedPlace> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                SavedPlace p = fromJson(arr.optJSONObject(i));
                if (p != null) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (SavedPlace p : cache) {
            try {
                arr.put(toJson(p));
            } catch (Exception ignored) {}
        }
        prefs().edit().putString(KEY_JSON, arr.toString()).apply();
    }

    private static JSONObject toJson(SavedPlace p) throws Exception {
        return new JSONObject()
                .put("id", p.id)
                .put("label", p.label)
                .put("lat", p.lat)
                .put("lon", p.lon)
                .put("radiusM", p.radiusM)
                .put("type", p.type.name());
    }

    private static SavedPlace fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            SavedPlace.Type type = SavedPlace.Type.valueOf(o.optString("type", "OTHER"));
            return new SavedPlace(
                    o.optString("id", ""),
                    o.optString("label", ""),
                    o.optDouble("lat", 0d),
                    o.optDouble("lon", 0d),
                    (float) o.optDouble("radiusM", DEFAULT_RADIUS_M),
                    type);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isValidCoord(double lat, double lon) {
        return Math.abs(lat) <= 90d && Math.abs(lon) <= 180d
                && !(lat == 0d && lon == 0d);
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
