package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.bureau.BureauCalcHelper;

import org.json.JSONObject;

/**
 * Calcul déterministe — délègue à {@link BureauCalcHelper}.
 * Le LLM ne doit jamais calculer de tête : il appelle cet outil puis reformule.
 */
public final class CalculatorTool implements Tool {

    @Override
    public String id() {
        return "calculator";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.CALCULATOR;
    }

    @Override
    public String description() {
        return "calculator(expression:str, question?:str) — Calcul déterministe (arithmétique, "
                + "marge, CA, pourcentage). Passe l'expression ou la question brute. "
                + "INTERDICTION de calculer de tête : appelle cet outil, puis reformule le résultat.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String expression = params.optString("expression", "").trim();
        String question = params.optString("question", "").trim();

        String input;
        if (!expression.isEmpty()
                && !question.isEmpty()
                && sameMathIntent(expression, question)) {
            input = expression;
        } else if (!expression.isEmpty() && !question.isEmpty()) {
            input = question + " " + expression;
        } else if (!expression.isEmpty()) {
            input = expression;
        } else {
            input = question;
        }
        if (input.isEmpty() || isBareQuestionMark(input) || !hasDigit(input)) {
            cb.onError("Précise une expression avec des chiffres (ex. 12×4, 50+36%, 100÷4).");
            return;
        }

        // forceRecalc : chaque demande utilisateur est un nouveau calcul.
        String forHelper = looksLikeBareExpression(input) ? "calcule " + input : input;
        BureauCalcHelper.Result result = BureauCalcHelper.trySolve(forHelper, null, true);
        if (result == null && !expression.isEmpty()) {
            result = BureauCalcHelper.trySolve("calcule " + expression, null, true);
        }
        if (result == null) {
            Double value = BureauCalcHelper.evaluateExpression(
                    expression.isEmpty() ? input : expression);
            if (value != null && !Double.isNaN(value) && !Double.isInfinite(value)) {
                String formatted = forTts(format(value));
                cb.onSuccess(ToolResult.text(
                        "Résultat : " + formatted,
                        "[Calcul] expression=" + input + " → " + formatted));
                return;
            }
            cb.onError("Je n'ai pas pu calculer « " + input + " ». "
                    + "Reformule (ex. 12×4, 50+36% de marge, 100÷4).");
            return;
        }

        String speak = result.speak != null ? forTts(result.speak.trim()) : "";
        if (speak.isEmpty()) {
            cb.onError("Calcul sans résultat.");
            return;
        }
        StringBuilder context = new StringBuilder("[Calcul déterministe]\n");
        context.append("Entrée : ").append(input).append('\n');
        context.append("Résultat oral : ").append(speak);
        for (String line : result.allDisplayLines()) {
            if (line != null && !line.isEmpty()) {
                context.append('\n').append(forTts(line));
            }
        }
        cb.onSuccess(ToolResult.text(speak, context.toString()));
    }

    private static boolean isBareQuestionMark(String input) {
        String t = input.trim();
        return t.equals("?") || t.matches("\\?+");
    }

    private static boolean hasDigit(String input) {
        if (input == null) return false;
        for (int i = 0; i < input.length(); i++) {
            if (Character.isDigit(input.charAt(i))) return true;
        }
        return false;
    }

    private static boolean looksLikeBareExpression(String input) {
        String t = input.trim();
        return t.matches("^[\\d\\s.,+\\-*/×÷()%xX=]+$")
                || t.matches("(?i)^\\d+[\\.,]?\\d*\\s*(fois|plus|moins|divis).*");
    }

    private static boolean sameMathIntent(String expression, String question) {
        String a = normalizeMathText(expression);
        String b = normalizeMathText(question);
        return !a.isEmpty() && a.equals(b);
    }

    private static String normalizeMathText(String input) {
        if (input == null) return "";
        return input.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "")
                .replace("×", "*")
                .replace("÷", "/")
                .replace(',', '.');
    }

    /** Point décimal → virgule pour TTS (« 6,545 » → « six virgule… »). */
    static String forTts(String result) {
        if (result == null) return "";
        return result.replace('.', ',');
    }

    private static String format(double v) {
        java.text.DecimalFormatSymbols sym =
                new java.text.DecimalFormatSymbols(java.util.Locale.FRANCE);
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.###", sym);
        return df.format(v);
    }
}
