package com.pegasuscorp.orbe.tools.device;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recherche un numéro dans le répertoire local. */
public final class ContactLookup {

    public static final class Match {
        public final String displayName;
        public final String phone;
        public final int score;

        Match(String displayName, String phone, int score) {
            this.displayName = displayName;
            this.phone = phone;
            this.score = score;
        }
    }

    private ContactLookup() {}

    public static boolean hasPermission(Context ctx) {
        return ctx != null
                && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static String findPhone(Context ctx, String name) {
        Match best = findBest(ctx, name);
        return best != null ? best.phone : null;
    }

    public static Match findBest(Context ctx, String name) {
        List<Match> all = search(ctx, name, 1);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Meilleurs contacts pour {@code query}, dédupliqués par numéro. */
    public static List<Match> search(Context ctx, String name, int limit) {
        List<Match> out = new ArrayList<>();
        if (ctx == null || name == null || name.trim().isEmpty() || limit <= 0) return out;
        if (!hasPermission(ctx)) return out;

        String needle = name.trim().toLowerCase(Locale.ROOT);
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        Map<String, Match> byPhone = new LinkedHashMap<>();
        try (Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null) return out;
            while (cursor.moveToNext()) {
                String display = cursor.getString(0);
                String number = cursor.getString(1);
                if (display == null || number == null) continue;
                String phone = number.replaceAll("\\s+", "");
                if (phone.isEmpty()) continue;
                int score = matchScore(display.toLowerCase(Locale.ROOT), needle);
                if (score < 1) continue;
                Match prev = byPhone.get(phone);
                if (prev == null || score > prev.score) {
                    byPhone.put(phone, new Match(display.trim(), phone, score));
                }
            }
        } catch (Exception ignored) {}

        List<Match> ranked = new ArrayList<>(byPhone.values());
        ranked.sort((a, b) -> Integer.compare(b.score, a.score));
        for (Match m : ranked) {
            out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static int matchScore(String display, String needle) {
        if (display.equals(needle)) return 100;
        if (display.startsWith(needle + " ")) return 80;
        if (display.contains(" " + needle + " ")) return 70;
        if (display.endsWith(" " + needle)) return 65;
        if (display.contains(needle)) return 50;
        String[] parts = needle.split("\\s+");
        if (parts.length > 1) {
            String last = parts[parts.length - 1];
            if (display.contains(last)) return 40;
        }
        return -1;
    }
}
