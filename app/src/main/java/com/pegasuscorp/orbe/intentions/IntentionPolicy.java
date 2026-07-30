package com.pegasuscorp.orbe.intentions;

import android.content.Context;

import java.util.Calendar;

/**
 * Anti-spam central : quiet hours, quota, cooldown, suppress, snooze par id, notif unique.
 */
public final class IntentionPolicy {

    private IntentionPolicy() {}

    public static boolean canFire(Context ctx, IntentionCandidate candidate) {
        if (ctx == null || candidate == null || !IntentionIds.isValid(candidate.id)) {
            return false;
        }
        if (!IntentionPrefs.isEnabled(ctx)) return false;
        if (IntentionPrefs.isSuppressed(ctx, candidate.id)) return false;

        long now = System.currentTimeMillis();
        long snoozeUntil = IntentionPrefs.getSnoozedUntil(ctx, candidate.id);
        if (snoozeUntil > now) return false;

        // Live GP : plusieurs pushes rares pendant la course (cooldown dédié)
        if (IntentionIds.F1_LIVE.equals(candidate.id)) {
            return true;
        }

        if (isQuietHours(ctx, now)) return false;
        if (IntentionPrefs.firedToday(ctx, candidate.id)) return false;

        IntentionPrefs.rollDailyIfNeeded(ctx);
        if (IntentionPrefs.getDailyCount(ctx) >= IntentionPrefs.MAX_DAILY) return false;

        long lastGlobal = IntentionPrefs.getLastFiredGlobal(ctx);
        if (lastGlobal > 0L && (now - lastGlobal) < IntentionPrefs.GLOBAL_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    /** Quiet hours wrap midnight (ex. 22 → 7). */
    public static boolean isQuietHours(Context ctx, long nowMs) {
        int start = IntentionPrefs.getQuietStartHour(ctx);
        int end = IntentionPrefs.getQuietEndHour(ctx);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (start == end) return false;
        if (start < end) {
            return hour >= start && hour < end;
        }
        // wrap : 22–7 → quiet if hour >= 22 || hour < 7
        return hour >= start || hour < end;
    }
}
