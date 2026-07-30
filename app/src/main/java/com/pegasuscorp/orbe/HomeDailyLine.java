package com.pegasuscorp.orbe;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Petit message souligné sous l'orbe : pool par moment de la journée,
 * une phrase stable par jour (différente de la veille). Pas de « Salut ».
 */
public final class HomeDailyLine {

    private static final String PREFS = "home_daily_line";
    private static final String KEY_DAY = "iso_day";
    private static final String KEY_INDEX = "line_index";

    private static final int SLOT_ANY = 0;
    private static final int SLOT_MORNING = 1;
    private static final int SLOT_MIDDAY = 2;
    private static final int SLOT_AFTERNOON = 3;
    private static final int SLOT_EVENING = 4;
    private static final int SLOT_NIGHT = 5;

    private static final class Entry {
        final int slot;
        final String text;

        Entry(int slot, String text) {
            this.slot = slot;
            this.text = text;
        }
    }

    private static final Entry[] POOL = {
            // —— Toute la journée ——
            e(SLOT_ANY, "Passe une bonne journée !"),
            e(SLOT_ANY, "Prends soin de toi"),
            e(SLOT_ANY, "Doucement, un pas après l'autre"),
            e(SLOT_ANY, "Respire un peu"),
            e(SLOT_ANY, "Tu gères !"),
            e(SLOT_ANY, "On avance !"),
            e(SLOT_ANY, "Belle journée devant toi !"),
            e(SLOT_ANY, "Reste léger"),
            e(SLOT_ANY, "Tout va bien se passer"),
            e(SLOT_ANY, "Un café, et c'est parti !"),
            // —— Matin ——
            e(SLOT_MORNING, "Passe une belle matinée !"),
            e(SLOT_MORNING, "Bon réveil !"),
            e(SLOT_MORNING, "Douce matinée"),
            e(SLOT_MORNING, "Bonne énergie ce matin !"),
            e(SLOT_MORNING, "Passe une bonne journée !"),
            e(SLOT_MORNING, "Matinée claire"),
            e(SLOT_MORNING, "Bien démarrer, c'est déjà ça"),
            e(SLOT_MORNING, "Le jour se lève avec toi !"),
            // —— Midi ——
            e(SLOT_MIDDAY, "Bon appétit !"),
            e(SLOT_MIDDAY, "Passe un bon midi !"),
            e(SLOT_MIDDAY, "Bonne pause déjeuner"),
            e(SLOT_MIDDAY, "Bon appétit, et à tout à l'heure !"),
            e(SLOT_MIDDAY, "Midi — prends le temps"),
            e(SLOT_MIDDAY, "Bon repas !"),
            e(SLOT_MIDDAY, "Pause bien méritée !"),
            // —— Après-midi ——
            e(SLOT_AFTERNOON, "Passe une bonne après-midi !"),
            e(SLOT_AFTERNOON, "Bon après-midi !"),
            e(SLOT_AFTERNOON, "Courage pour la fin de journée"),
            e(SLOT_AFTERNOON, "Encore un peu, tu y es !"),
            e(SLOT_AFTERNOON, "Après-midi productive !"),
            e(SLOT_AFTERNOON, "Reste focus, ou souffle"),
            e(SLOT_AFTERNOON, "Belle après-midi !"),
            // —— Soir ——
            e(SLOT_EVENING, "Passe une bonne soirée !"),
            e(SLOT_EVENING, "Bonne soirée !"),
            e(SLOT_EVENING, "Douce soirée"),
            e(SLOT_EVENING, "Profite du soir !"),
            e(SLOT_EVENING, "Soirée tranquille"),
            e(SLOT_EVENING, "Bon dîner !"),
            e(SLOT_EVENING, "Bon appétit ce soir !"),
            e(SLOT_EVENING, "Fin de journée — souffle"),
            // —— Nuit ——
            e(SLOT_NIGHT, "Passe une bonne nuit !"),
            e(SLOT_NIGHT, "Bonne nuit !"),
            e(SLOT_NIGHT, "Dors bien"),
            e(SLOT_NIGHT, "Nette paisible"),
            e(SLOT_NIGHT, "Repose-toi !"),
            e(SLOT_NIGHT, "À demain !"),
            e(SLOT_NIGHT, "Fais de beaux rêves !"),
            e(SLOT_NIGHT, "Douce nuit"),
    };

    private HomeDailyLine() {}

    /** Message du jour pour l'accueil (stable jusqu'à minuit). */
    public static String forToday(Context context) {
        if (context == null) return fallbackNow();
        Context app = context.getApplicationContext();
        String today = LocalDate.now().toString();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String storedDay = prefs.getString(KEY_DAY, "");
        int storedIndex = prefs.getInt(KEY_INDEX, -1);

        List<Integer> candidates = candidatesForNow();
        if (candidates.isEmpty()) return fallbackNow();

        if (today.equals(storedDay) && storedIndex >= 0 && storedIndex < POOL.length
                && candidates.contains(storedIndex)) {
            return POOL[storedIndex].text;
        }

        int yesterdayIndex = today.equals(storedDay) ? -1 : storedIndex;
        long epoch = LocalDate.now().toEpochDay();
        int pick = candidates.get((int) Math.floorMod(epoch, candidates.size()));
        if (pick == yesterdayIndex && candidates.size() > 1) {
            int pos = candidates.indexOf(pick);
            pick = candidates.get((pos + 1) % candidates.size());
        }
        if (pick == yesterdayIndex && candidates.size() > 1) {
            for (int c : candidates) {
                if (c != yesterdayIndex) {
                    pick = c;
                    break;
                }
            }
        }

        prefs.edit().putString(KEY_DAY, today).putInt(KEY_INDEX, pick).apply();
        return POOL[pick].text;
    }

    public static int poolSize() {
        return POOL.length;
    }

    static List<Integer> candidatesForNow() {
        int slot = slotNow();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < POOL.length; i++) {
            Entry e = POOL[i];
            if (e.slot == SLOT_ANY || e.slot == slot) out.add(i);
        }
        return out;
    }

    static int slotNow() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 11) return SLOT_MORNING;
        if (hour >= 11 && hour < 14) return SLOT_MIDDAY;
        if (hour >= 14 && hour < 18) return SLOT_AFTERNOON;
        if (hour >= 18 && hour < 22) return SLOT_EVENING;
        return SLOT_NIGHT;
    }

    private static String fallbackNow() {
        switch (slotNow()) {
            case SLOT_MORNING: return "Passe une belle matinée !";
            case SLOT_MIDDAY: return "Bon appétit !";
            case SLOT_AFTERNOON: return "Passe une bonne après-midi !";
            case SLOT_EVENING: return "Passe une bonne soirée !";
            case SLOT_NIGHT:
            default: return "Passe une bonne nuit !";
        }
    }

    private static Entry e(int slot, String text) {
        return new Entry(slot, text);
    }
}
