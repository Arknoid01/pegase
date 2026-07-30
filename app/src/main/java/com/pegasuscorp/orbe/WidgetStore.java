package com.pegasuscorp.orbe;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistance des widgets placés sur le tableau widgets.
 */
public final class WidgetStore {

    public static final class Entry {
        public int appWidgetId;
        public int x;
        public int y;
        public int width;
        public int height;

        public Entry(int appWidgetId, int x, int y, int width, int height) {
            this.appWidgetId = appWidgetId;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static final String PREFS = "orbe_widgets";
    private static final String KEY_ENTRIES = "entries";

    private WidgetStore() {}

    public static List<Entry> load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, "");
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("\\|")) {
            if (part.isEmpty()) continue;
            String[] f = part.split(",");
            if (f.length != 5) continue;
            try {
                out.add(new Entry(
                        Integer.parseInt(f[0]),
                        Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]),
                        Integer.parseInt(f[3]),
                        Integer.parseInt(f[4])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static void save(Context context, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) sb.append('|');
            sb.append(e.appWidgetId).append(',')
                    .append(e.x).append(',')
                    .append(e.y).append(',')
                    .append(e.width).append(',')
                    .append(e.height);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, sb.toString())
                .apply();
    }

    public static void upsert(Context context, Entry entry) {
        List<Entry> all = load(context);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).appWidgetId == entry.appWidgetId) {
                all.set(i, entry);
                found = true;
                break;
            }
        }
        if (!found) all.add(entry);
        save(context, all);
    }

    public static void remove(Context context, int appWidgetId) {
        List<Entry> all = load(context);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).appWidgetId == appWidgetId) {
                all.remove(i);
                break;
            }
        }
        save(context, all);
    }

    public static void clearAll(Context context) {
        save(context, new ArrayList<>());
    }
}
