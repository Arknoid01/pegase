package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Persistance des raccourcis autour de l'orbe (9 emplacements).
 * Types : app installée ({@code package}) ou lien web ({@code label} + {@code url}).
 */
public final class ShortcutStore {

    public static final int SLOT_COUNT = 9;
    private static final String PREFS = "orbe_shortcuts";
    private static final String KEY_SLOT = "slot_";

    public enum Type { APP, WEB }

    /** Un emplacement orbe — app ou lien web. */
    public static final class Slot {
        public final Type type;
        public final String packageName;
        public final String label;
        public final String url;

        private Slot(Type type, String packageName, String label, String url) {
            this.type = type;
            this.packageName = packageName;
            this.label = label;
            this.url = url;
        }

        public static Slot empty() {
            return new Slot(Type.APP, null, null, null);
        }

        public static Slot app(String packageName) {
            if (packageName == null || packageName.trim().isEmpty()) return empty();
            return new Slot(Type.APP, packageName.trim(), null, null);
        }

        public static Slot web(String label, String url) {
            String l = label != null ? label.trim() : "";
            String u = normalizeUrl(url);
            if (l.isEmpty() || u.isEmpty()) return empty();
            return new Slot(Type.WEB, null, l, u);
        }

        public boolean isEmpty() {
            if (type == Type.WEB) {
                return label == null || label.isEmpty() || url == null || url.isEmpty();
            }
            return packageName == null || packageName.isEmpty();
        }

        public boolean isWeb() {
            return type == Type.WEB && !isEmpty();
        }

        public boolean isApp() {
            return type == Type.APP && !isEmpty();
        }
    }

    private ShortcutStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Slot getSlot(Context context, int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return Slot.empty();
        String raw = prefs(context).getString(KEY_SLOT + slot, null);
        return parse(raw);
    }

    /** Compat Spotify / code existant — package app uniquement. */
    public static String getPackage(Context context, int slot) {
        Slot s = getSlot(context, slot);
        return s.isApp() ? s.packageName : null;
    }

    public static void setSlot(Context context, int slot, Slot value) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        SharedPreferences.Editor ed = prefs(context).edit();
        if (value == null || value.isEmpty()) {
            ed.remove(KEY_SLOT + slot);
        } else {
            ed.putString(KEY_SLOT + slot, serialize(value));
        }
        ed.apply();
    }

    public static void setPackage(Context context, int slot, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            clearSlot(context, slot);
            return;
        }
        setSlot(context, slot, Slot.app(packageName));
    }

    public static void setWeb(Context context, int slot, String label, String url) {
        setSlot(context, slot, Slot.web(label, url));
    }

    public static void clearSlot(Context context, int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        prefs(context).edit().remove(KEY_SLOT + slot).apply();
    }

    /**
     * Meilleur raccourci web dont le libellé matche {@code name}
     * (égalité puis containment). Null si aucun.
     */
    public static Slot findWebByLabel(Context context, String name) {
        if (context == null || name == null || name.trim().isEmpty()) return null;
        String needle = fold(name);
        // STT fréquent : « curseur » pour Cursor
        if ("curseur".equals(needle)) needle = "cursor";
        Slot bestContains = null;
        for (int i = 0; i < SLOT_COUNT; i++) {
            Slot s = getSlot(context, i);
            if (!s.isWeb()) continue;
            String lab = fold(s.label);
            if (lab.equals(needle)) return s;
            if (lab.contains(needle) || needle.contains(lab)) {
                if (bestContains == null
                        || s.label.length() < bestContains.label.length()) {
                    bestContains = s;
                }
            }
        }
        return bestContains;
    }

    public static String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return u;
        if (lower.startsWith("www.")) return "https://" + u;
        // Domaine simple sans schéma
        if (u.contains(".") && !u.contains(" ")) return "https://" + u;
        return u;
    }

    public static boolean isValidHttpUrl(String url) {
        String u = normalizeUrl(url);
        if (u.isEmpty()) return false;
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        // Host non vide après le schéma (sans dépendre d'android.net.Uri en tests JVM).
        int schemeEnd = lower.startsWith("https://") ? 8 : 7;
        if (schemeEnd >= u.length()) return false;
        String rest = u.substring(schemeEnd);
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        return !host.isEmpty() && host.contains(".");
    }

    static Slot parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Slot.empty();
        String s = raw.trim();
        if (s.startsWith("{")) {
            try {
                return parseJson(s);
            } catch (Exception e) {
                return Slot.empty();
            }
        }
        // Legacy : package name brut
        return Slot.app(s);
    }

    /** Parse manuel — évite org.json stubé dans les tests JVM. */
    private static Slot parseJson(String s) {
        String type = jsonString(s, "type");
        if ("web".equalsIgnoreCase(type)) {
            return Slot.web(jsonString(s, "label"), jsonString(s, "url"));
        }
        String pkg = jsonString(s, "package");
        if (pkg.isEmpty()) pkg = jsonString(s, "pkg");
        return Slot.app(pkg);
    }

    static String serialize(Slot slot) {
        if (slot == null || slot.isEmpty()) return "";
        if (slot.isWeb()) {
            return "{\"type\":\"web\",\"label\":" + jsonQuote(slot.label)
                    + ",\"url\":" + jsonQuote(slot.url) + "}";
        }
        return "{\"type\":\"app\",\"package\":" + jsonQuote(slot.packageName) + "}";
    }

    private static String jsonQuote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    /** Extraction naive "key":"value" (suffisant pour nos slots). */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return "";
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String fold(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private static final String KEY_DEFAULTS_SEEDED = "defaults_seeded";
    private static final String KEY_SPOTIFY_ORB_MIGRATION = "spotify_orb_v1";

    public static boolean isSpotifyPinned(Context context) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if ("com.spotify.music".equals(getPackage(context, i))) return true;
        }
        return false;
    }

    /** Place Spotify sur l'orbe si l'app est installée. */
    public static boolean pinSpotify(Context context) {
        if (!isSpotifyInstalled(context)) return false;
        if (isSpotifyPinned(context)) return true;
        if (getPackage(context, 0) == null) {
            setPackage(context, 0, "com.spotify.music");
            return true;
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (getPackage(context, i) == null && getSlot(context, i).isEmpty()) {
                setPackage(context, i, "com.spotify.music");
                return true;
            }
        }
        // Slot libre (y compris web) → premier vide absolu
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (getSlot(context, i).isEmpty()) {
                setPackage(context, i, "com.spotify.music");
                return true;
            }
        }
        setPackage(context, 0, "com.spotify.music");
        return true;
    }

    public static boolean isSpotifyInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.spotify.music", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Première utilisation : épingler Spotify si aucun raccourci n'est configuré. */
    public static void seedDefaultsIfNeeded(Context context) {
        if (prefs(context).getBoolean(KEY_DEFAULTS_SEEDED, false)) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!getSlot(context, i).isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) {
            pinSpotify(context);
        }
        prefs(context).edit().putBoolean(KEY_DEFAULTS_SEEDED, true).apply();
    }

    /** Ajoute Spotify au menu de l'orbe une fois pour les installations existantes. */
    public static void migrateSpotifyOrbIfNeeded(Context context) {
        if (prefs(context).getBoolean(KEY_SPOTIFY_ORB_MIGRATION, false)) {
            return;
        }
        pinSpotify(context);
        prefs(context).edit().putBoolean(KEY_SPOTIFY_ORB_MIGRATION, true).apply();
    }
}
