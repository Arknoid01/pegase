package com.pegasuscorp.orbe.termux;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistance SharedPreferences des raccourcis Termux (label + commande). */
public final class TermuxShortcutStore {

    private static final String PREFS = "termux_shortcuts";
    private static final String KEY_LIST = "shortcuts";

    private TermuxShortcutStore() {}

    public static List<TermuxShortcut> list(Context ctx) {
        List<TermuxShortcut> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new TermuxShortcut(o.optString("label", ""), o.optString("command", "")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(Context ctx, TermuxShortcut shortcut) {
        List<TermuxShortcut> current = list(ctx);
        current.add(shortcut);
        save(ctx, current);
    }

    public static void remove(Context ctx, int index) {
        List<TermuxShortcut> current = list(ctx);
        if (index < 0 || index >= current.size()) return;
        current.remove(index);
        save(ctx, current);
    }

    private static void save(Context ctx, List<TermuxShortcut> shortcuts) {
        JSONArray arr = new JSONArray();
        for (TermuxShortcut s : shortcuts) {
            JSONObject o = new JSONObject();
            try {
                o.put("label", s.label);
                o.put("command", s.command);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
