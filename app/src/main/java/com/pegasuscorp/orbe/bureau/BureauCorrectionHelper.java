package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import com.pegasuscorp.orbe.voice.VoiceCorrectionStore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte et interprète les corrections au bureau (« non c'était 50 », « recalcule », etc.).
 */
public final class BureauCorrectionHelper {

    public enum Type {
        NONE,
        RECALC,
        REMOVE_ANSWER,
        CORRECTED_EXPRESSION
    }

    public static final class Intent {
        public final Type type;
        /** Expression à recalculer (voix ou encre corrigée). */
        public final String expression;
        /** Mot-clé pour retirer un ajout Pégase précis. */
        public final String removeTarget;

        Intent(Type type, String expression, String removeTarget) {
            this.type = type;
            this.expression = expression == null ? "" : expression.trim();
            this.removeTarget = removeTarget;
        }

        static Intent none() {
            return new Intent(Type.NONE, "", null);
        }
    }

    private static final Pattern MULT_EXPR = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s*(?:fois|x|×|\\*)\\s*(\\d+[\\.,]?\\d*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_BUT = Pattern.compile(
            "(?i)(?:pas|non,?\\s+pas)\\s+[^,;]+?\\s+(?:mais|plutot|plutôt)\\s+(.+)");
    private static final Pattern ITS = Pattern.compile(
            "(?i)(?:c'?est|cest|en fait,?\\s+c'?est)\\s+(.+)");
    private static final Pattern WAS_NOT_NUM = Pattern.compile(
            "(?i)(?:c'?était|cetait|en fait,?\\s+c'?était)\\s+(\\d+[\\.,]?\\d*)\\s+pas\\s+(\\d+[\\.,]?\\d*)");
    private static final Pattern NUM_NOT_NUM = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s+pas\\s+(\\d+[\\.,]?\\d*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_THEN_ITS = Pattern.compile(
            "(?i)(?:ce n'?est pas|c'?est pas|pas)\\s+[^,;]*?\\s+(?:c'?est|mais)\\s+(.+)");

    private BureauCorrectionHelper() {}

    public static Intent parse(Context context, String raw) {
        if (raw == null || raw.trim().isEmpty()) return Intent.none();
        String fold = raw.toLowerCase(Locale.ROOT).trim();

        if (isRemoveAnswer(fold)) {
            return new Intent(Type.REMOVE_ANSWER, "", pickRemoveKeyword(fold));
        }

        if (isRecalc(fold)) {
            String expr = extractExpression(context, raw);
            if (expr.isEmpty()) expr = extractNumberSwap(raw);
            return new Intent(Type.RECALC, expr, null);
        }

        String corrected = VoiceCorrectionStore.extractCorrectionPhrase(raw);
        if (corrected != null && !corrected.isEmpty()) {
            String expr = extractExpressionFromPhrase(corrected);
            if (expr.isEmpty()) expr = extractNumberSwap(corrected);
            if (!expr.isEmpty()) {
                return new Intent(Type.CORRECTED_EXPRESSION, expr, null);
            }
            return new Intent(Type.RECALC, corrected, null);
        }

        Matcher wasNot = WAS_NOT_NUM.matcher(raw);
        if (wasNot.find()) {
            String expr = buildSwapExpr(wasNot.group(1), raw);
            if (!expr.isEmpty()) return new Intent(Type.CORRECTED_EXPRESSION, expr, null);
        }

        Matcher notThenIts = NOT_THEN_ITS.matcher(raw);
        if (notThenIts.find() && looksMathy(notThenIts.group(1))) {
            String expr = extractExpressionFromPhrase(notThenIts.group(1));
            if (!expr.isEmpty()) return new Intent(Type.CORRECTED_EXPRESSION, expr, null);
        }

        Matcher notBut = NOT_BUT.matcher(raw);
        if (notBut.find()) {
            String expr = extractExpressionFromPhrase(notBut.group(1));
            if (!expr.isEmpty()) {
                return new Intent(Type.CORRECTED_EXPRESSION, expr, null);
            }
        }

        Matcher its = ITS.matcher(raw);
        if (its.find() && looksMathy(its.group(1))) {
            String expr = extractExpressionFromPhrase(its.group(1));
            if (!expr.isEmpty()) {
                return new Intent(Type.CORRECTED_EXPRESSION, expr, null);
            }
        }

        if (fold.startsWith("non ") || fold.equals("non")) {
            String expr = extractExpression(context, raw);
            if (expr.isEmpty()) expr = extractNumberSwap(raw);
            return new Intent(Type.RECALC, expr, null);
        }

        if (fold.contains(" pas ") && NUM_NOT_NUM.matcher(raw).find()) {
            String expr = extractNumberSwap(raw);
            if (!expr.isEmpty()) return new Intent(Type.CORRECTED_EXPRESSION, expr, null);
        }

        return Intent.none();
    }

    /** « c'était 50 pas 5 » → réinjecte le bon chiffre dans l'expression manuscrite si possible. */
    private static String extractNumberSwap(String raw) {
        if (raw == null) return "";
        Matcher wasNot = WAS_NOT_NUM.matcher(raw);
        if (wasNot.find()) return buildSwapExpr(wasNot.group(1), raw);
        Matcher swap = NUM_NOT_NUM.matcher(raw);
        if (swap.find()) return buildSwapExpr(swap.group(1), raw);
        return "";
    }

    private static String buildSwapExpr(String correctNum, String raw) {
        if (correctNum == null || correctNum.isEmpty()) return "";
        String expr = extractExpressionFromPhrase(raw);
        if (!expr.isEmpty()) {
            Matcher mult = MULT_EXPR.matcher(expr);
            if (mult.find()) {
                return mult.group(1) + " fois " + mult.group(2);
            }
            return expr;
        }
        Matcher mult = MULT_EXPR.matcher(raw);
        String last = "";
        while (mult.find()) last = mult.group();
        if (!last.isEmpty()) {
            return last.replaceFirst("\\d+[\\.,]?\\d*", correctNum.replace(',', '.'));
        }
        if (looksMathy(raw)) return correctNum;
        return "";
    }

    private static boolean isRecalc(String fold) {
        return fold.contains("recalcule") || fold.contains("recalcul")
                || fold.contains("refais") || fold.contains("recommence")
                || fold.contains("corrige") || fold.contains("correction")
                || fold.contains("tu t'es tromp") || fold.contains("tu tes tromp")
                || fold.contains("c'est pas ça") || fold.contains("c'est pas ca")
                || fold.contains("ce n'est pas") || fold.contains("ce nest pas");
    }

    private static boolean isRemoveAnswer(String fold) {
        return fold.contains("enlève le résultat") || fold.contains("enleve le resultat")
                || fold.contains("enlève la réponse") || fold.contains("enleve la reponse")
                || fold.contains("enlève le calcul") || fold.contains("enleve le calcul")
                || fold.contains("efface le résultat") || fold.contains("efface la réponse")
                || fold.contains("efface le calcul")
                || (fold.contains("enlève") && (fold.contains("résultat")
                || fold.contains("resultat") || fold.contains("réponse") || fold.contains("reponse")))
                || (fold.contains("efface") && fold.contains("résultat"));
    }

    private static String pickRemoveKeyword(String fold) {
        if (fold.contains("résultat") || fold.contains("resultat")) return "résultat";
        if (fold.contains("réponse") || fold.contains("reponse")) return "réponse";
        if (fold.contains("calcul")) return "calcul";
        return "=";
    }

    private static String extractExpression(Context context, String raw) {
        String fromPhrase = extractExpressionFromPhrase(raw);
        if (!fromPhrase.isEmpty()) return fromPhrase;
        String corrected = VoiceCorrectionStore.extractCorrectionPhrase(raw);
        if (corrected != null) {
            fromPhrase = extractExpressionFromPhrase(corrected);
            if (!fromPhrase.isEmpty()) return fromPhrase;
        }
        if (context != null) {
            String applied = VoiceCorrectionStore.getInstance(context).apply(raw);
            fromPhrase = extractExpressionFromPhrase(applied);
            if (!fromPhrase.isEmpty()) return fromPhrase;
        }
        return "";
    }

    static String extractExpressionFromPhrase(String phrase) {
        if (phrase == null) return "";
        String t = phrase.trim();
        if (t.isEmpty()) return "";

        Matcher m = MULT_EXPR.matcher(t);
        String last = "";
        while (m.find()) last = m.group();
        if (!last.isEmpty()) return last;

        if (looksMathy(t)) return t;
        return "";
    }

    private static boolean looksMathy(String s) {
        if (s == null) return false;
        return Pattern.compile("\\d").matcher(s).find()
                && (s.contains("fois") || s.contains("x") || s.contains("×")
                || s.contains("*") || s.contains("marge") || s.contains("€"));
    }
}
