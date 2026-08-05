package com.pegasuscorp.orbe.copilot;

import java.util.regex.Pattern;

/**
 * Masque PII dans le texte d'écran envoyé au LLM — pas dans l'arbre a11y live.
 */
public final class ScreenPiiRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    /** Carte avant OTP/tel pour ne pas fragmenter les groupes. */
    private static final Pattern CARD = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]*?){13,19}(?!\\d)");
    private static final Pattern OTP = Pattern.compile(
            "(?<![\\dA-Za-z])(\\d{6})(?![\\dA-Za-z])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[\\s.-]?)?(?:0\\d(?:[\\s.-]?\\d{2}){4})(?!\\d)");

    private ScreenPiiRedactor() {}

    public static String redact(String raw) {
        if (raw == null || raw.isEmpty()) return raw != null ? raw : "";
        String out = EMAIL.matcher(raw).replaceAll("[email]");
        out = CARD.matcher(out).replaceAll("[carte]");
        out = OTP.matcher(out).replaceAll("[otp]");
        out = PHONE.matcher(out).replaceAll("[tel]");
        return out;
    }
}
