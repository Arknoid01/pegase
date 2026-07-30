package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;

import com.pegasuscorp.orbe.tools.life.SituationRoutineDefaults;

import java.util.Calendar;

/**
 * Routines situationnelles : matin, soir, etc.
 * Déclenchées par « comme d'habitude » selon l'heure ou un créneau explicite.
 */
public final class SituationRoutineStore {

    public enum Slot {
        MORNING("morning"),
        AFTERNOON("afternoon"),
        EVENING("evening"),
        NIGHT("night"),
        DEFAULT("default");

        final String key;

        Slot(String key) {
            this.key = key;
        }
    }

    private static final String PREFS = "situation_routines";

    private SituationRoutineStore() {}

    public static Slot currentSlot() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) return Slot.MORNING;
        if (hour >= 12 && hour < 18) return Slot.AFTERNOON;
        if (hour >= 18 && hour < 23) return Slot.EVENING;
        return Slot.NIGHT;
    }

    public static Slot parseExplicitSlot(String fold) {
        if (fold == null || fold.isEmpty()) return null;
        if (fold.contains("matin") || fold.contains("leve") || fold.contains("lever")) {
            return Slot.MORNING;
        }
        if (fold.contains("apres midi") || fold.contains("après midi") || fold.contains("midi")) {
            return Slot.AFTERNOON;
        }
        if (fold.contains("soir") || fold.contains("ce soir")) {
            return Slot.EVENING;
        }
        if (fold.contains("nuit") || fold.contains("coucher")) {
            return Slot.NIGHT;
        }
        return null;
    }

    public static String resolveRoutine(Context context, Slot slot) {
        if (context == null) {
            return SituationRoutineDefaults.builtin(slot);
        }
        String custom = prefs(context).getString(slot.key, "").trim();
        if (!custom.isEmpty()) return custom;
        String fallback = prefs(context).getString(Slot.DEFAULT.key, "").trim();
        if (!fallback.isEmpty() && slot != Slot.DEFAULT) return fallback;
        return SituationRoutineDefaults.builtin(slot);
    }

    public static void assign(Context context, Slot slot, String toolJson) {
        if (context == null || slot == null) return;
        prefs(context).edit()
                .putString(slot.key, toolJson == null ? "" : toolJson.trim())
                .apply();
    }

    public static String labelForSlot(Slot slot) {
        switch (slot) {
            case MORNING: return "routine du matin";
            case AFTERNOON: return "routine de l'après-midi";
            case EVENING: return "routine du soir";
            case NIGHT: return "routine de nuit";
            default: return "routine habituelle";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
