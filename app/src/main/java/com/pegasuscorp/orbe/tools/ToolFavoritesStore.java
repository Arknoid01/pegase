package com.pegasuscorp.orbe.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Favoris épinglés dans l'onglet Outils. */
public final class ToolFavoritesStore {

    private static final String PREFS = "tool_favorites";
    private static final String KEY_PINS = "pins";
    private static final int MAX_PINS = 8;

    private ToolFavoritesStore() {}

    public static boolean isPinned(Context context, String favoriteKey) {
        return loadPins(context).contains(favoriteKey);
    }

    public static void toggle(Context context, String favoriteKey) {
        List<String> pins = loadPins(context);
        if (pins.contains(favoriteKey)) {
            pins.remove(favoriteKey);
        } else {
            pins.remove(favoriteKey);
            pins.add(0, favoriteKey);
            while (pins.size() > MAX_PINS) {
                pins.remove(pins.size() - 1);
            }
        }
        savePins(context, pins);
    }

    public static List<String> getPinnedKeys(Context context) {
        return loadPins(context);
    }

    private static List<String> loadPins(Context context) {
        List<String> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY_PINS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String key = arr.optString(i, "").trim();
                if (!key.isEmpty() && !out.contains(key)) out.add(key);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void savePins(Context context, List<String> pins) {
        JSONArray arr = new JSONArray();
        for (String pin : pins) arr.put(pin);
        prefs(context).edit().putString(KEY_PINS, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
