package com.pegasuscorp.orbe.tools.device;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Déclenche le calcul déterministe quand le message mélange chiffres et signes maths.
 * Le LLM ne doit pas calculer : {@link CalculatorTool} + reformulation uniquement.
 */
public final class MathCalcTrigger {

    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    /** Signes forts : peu d'ambiguïté avec dates / heures. */
    private static final Pattern STRONG_SIGN = Pattern.compile("[+*×÷=%]|\\bx\\b");
    /** a OP b — opérateurs plus ambigus. */
    private static final Pattern ARITH_PAIR = Pattern.compile(
            "\\d+[\\.,]?\\d*\\s*([+\\-*/×÷=]|fois|plus|moins|divis[eé])\\s*\\d",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_ONLY = Pattern.compile(
            "^\\s*\\d{1,2}[/.-]\\d{1,2}([/.-]\\d{2,4})?\\s*$");
    private static final Pattern TIME_ONLY = Pattern.compile(
            "^\\s*\\d{1,2}\\s*[h:]\\s*\\d{0,2}\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONEISH = Pattern.compile(
            "^\\s*[+]?\\d[\\d\\s().-]{7,}\\s*$");

    private MathCalcTrigger() {}

    public static boolean matches(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        if (DATE_ONLY.matcher(t).matches()) return false;
        if (TIME_ONLY.matcher(t).matches()) return false;
        if (PHONEISH.matcher(t).matches()) return false;
        if (!HAS_DIGIT.matcher(t).find()) return false;

        String fold = t.toLowerCase(Locale.ROOT);
        if (fold.contains("marge") && HAS_DIGIT.matcher(t).find()) return true;
        if (fold.contains("pour cent") || fold.contains("pourcent")) {
            return HAS_DIGIT.matcher(t).find();
        }
        if (STRONG_SIGN.matcher(t).find()) return true;
        return ARITH_PAIR.matcher(t).find();
    }
}
