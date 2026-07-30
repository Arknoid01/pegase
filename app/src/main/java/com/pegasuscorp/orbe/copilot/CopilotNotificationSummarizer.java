package com.pegasuscorp.orbe.copilot;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reformule une notification en phrase Pégase (« Marine t'a écrit : … »).
 */
public final class CopilotNotificationSummarizer {

    private static final Set<String> MESSAGING_PACKAGES = new HashSet<>(Arrays.asList(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.Slack",
            "com.facebook.orca",
            "org.thoughtcrime.securesms",
            "com.discord",
            "com.instagram.android"
    ));

    private static final Pattern GROUP_SUFFIX = Pattern.compile(
            "\\s*\\(\\d+\\s*(messages?|nouveaux?)?\\)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

    private CopilotNotificationSummarizer() {}

    public static String summarize(String packageName, String appLabel,
            String title, String text) {
        String body = firstNonEmpty(text, title);
        if (body.isEmpty()) return "";

        String sender = extractSender(packageName, appLabel, title, text);
        if (!sender.isEmpty() && usesWriterPhrase(packageName)) {
            return sender + " t'a écrit : " + truncate(body, 180);
        }
        if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(text)
                && !title.trim().equalsIgnoreCase(text.trim())) {
            String app = safeLabel(appLabel);
            if (!title.equalsIgnoreCase(app)) {
                return title.trim() + " : " + truncate(text.trim(), 180);
            }
        }
        String app = safeLabel(appLabel);
        if (!app.isEmpty()) {
            return "Sur " + app + " : " + truncate(body, 180);
        }
        return truncate(body, 200);
    }

    private static String extractSender(String packageName, String appLabel,
            String title, String text) {
        if (TextUtils.isEmpty(title)) return "";
        String t = title.trim();
        String app = safeLabel(appLabel);
        if (t.equalsIgnoreCase(app)) return "";

        if ("com.google.android.gm".equals(packageName)) {
            int colon = t.indexOf(':');
            if (colon > 0 && colon < t.length() - 1) {
                return t.substring(0, colon).trim();
            }
        }
        t = GROUP_SUFFIX.matcher(t).replaceAll("").trim();
        if (t.isEmpty() || DIGITS_ONLY.matcher(t).matches()) return "";
        if (t.length() > 40) return "";
        return t;
    }

    private static boolean usesWriterPhrase(String packageName) {
        return isMessagingApp(packageName)
                || "com.google.android.gm".equals(packageName);
    }

    private static boolean isMessagingApp(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (MESSAGING_PACKAGES.contains(packageName)) return true;
        String lower = packageName.toLowerCase(Locale.ROOT);
        return lower.contains("sms") || lower.contains("message")
                || lower.contains("chat") || lower.contains("messenger");
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v.trim();
        }
        return "";
    }

    private static String safeLabel(String appLabel) {
        return appLabel != null ? appLabel.trim() : "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1).trim() + "…";
    }
}
