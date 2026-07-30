package com.pegasuscorp.orbe.intentions.rules;

import android.text.TextUtils;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;
import com.pegasuscorp.orbe.intentions.IntentionIds;

/**
 * Entrée Wi‑Fi travail : ancien SSID ≠ work, nouveau == work.
 */
public final class WorkWifiRule implements IntentionRule {

    @Override
    public IntentionCandidate evaluate(ContextSnapshot ctx) {
        if (ctx == null) return null;
        String work = normalize(ctx.workWifiSsid);
        if (work.isEmpty()) return null;
        String now = normalize(ctx.ssid);
        if (now.isEmpty() || isUnknown(now)) return null;
        String prev = normalize(ctx.lastSeenSsid);
        if (work.equalsIgnoreCase(now) && !work.equalsIgnoreCase(prev)) {
            return new IntentionCandidate(
                    IntentionIds.WORK_WIFI,
                    "Pégase",
                    "Tu es arrivé au travail. Passer en mode concentré ?",
                    "work");
        }
        return null;
    }

    public static String normalize(String ssid) {
        if (ssid == null) return "";
        String s = ssid.trim().replace("\"", "");
        if (isUnknown(s)) return "";
        return s;
    }

    public static boolean isUnknown(String s) {
        if (TextUtils.isEmpty(s)) return true;
        String lower = s.toLowerCase();
        return "<unknown ssid>".equals(lower)
                || "unknown_ssid".equals(lower)
                || "0x".equals(lower);
    }
}
