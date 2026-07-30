package com.pegasuscorp.orbe.bureau;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calculs déterministes + rendu style crayon. */
public final class BureauCalcHelper {

    private static final Pattern MULT = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s*(?:fois|x|×|\\*)\\s*(\\d+[\\.,]?\\d*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s*(?:\\+|plus)\\s*(\\d+[\\.,]?\\d*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUB = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s*(?:-|moins)\\s*(\\d+[\\.,]?\\d*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIV = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s*(?:/|÷|divis[eé](?:\\s*par)?)\\s*(\\d+[\\.,]?\\d*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_PRICE = Pattern.compile(
            "(\\d+[\\.,]?\\d*)\\s*(?:pi[eè]ces?|unit[eé]s?)?\\s*(?:à|a)\\s*(\\d+[\\.,]?\\d*)\\s*(?:€|euros?)?",
            Pattern.CASE_INSENSITIVE);

    public static final class Result {
        public final String speak;
        public final String exprKey;
        public final List<String> sketchLines;
        public final List<String> detailLines;

        public Result(String speak, String exprKey, List<String> sketchLines, List<String> detailLines) {
            this.speak = speak;
            this.exprKey = exprKey;
            this.sketchLines = sketchLines == null ? List.of() : sketchLines;
            this.detailLines = detailLines == null ? List.of() : detailLines;
        }

        public List<String> allDisplayLines() {
            List<String> all = new ArrayList<>(sketchLines);
            all.addAll(detailLines);
            return all;
        }
    }

    private BureauCalcHelper() {}

    public static Result trySolve(String spoken) {
        return trySolve(spoken, null);
    }

    public static Result trySolve(String spoken, Set<String> alreadyProcessed) {
        return trySolve(spoken, alreadyProcessed, false);
    }

    public static Result trySolve(String spoken, Set<String> alreadyProcessed, boolean forceRecalc) {
        if (spoken == null || spoken.isEmpty()) return null;
        String t = normalizeSpoken(spoken);

        // Expressions arithmétiques pures d'abord (multi-opérateurs, %, virgules FR)
        Result expr = tryPureExpression(t, alreadyProcessed, forceRecalc);
        if (expr != null) return expr;

        Result margin = tryMargin(t, alreadyProcessed, forceRecalc);
        if (margin != null) return margin;

        Result revenue = tryRevenue(t, alreadyProcessed, forceRecalc);
        if (revenue != null) return revenue;

        Result mult = tryMultiply(t, alreadyProcessed, forceRecalc);
        if (mult != null) return mult;

        Result add = tryBinary(t, ADD, "+", "plus", alreadyProcessed, forceRecalc);
        if (add != null) return add;

        Result sub = tryBinary(t, SUB, "−", "moins", alreadyProcessed, forceRecalc);
        if (sub != null) return sub;

        Result div = tryDivide(t, alreadyProcessed, forceRecalc);
        if (div != null) return div;

        return null;
    }

    /**
     * Normalisation complète avant évaluation arithmétique
     * (÷×, virgule décimale, %, =).
     */
    public static String normalizeExpression(String expr) {
        if (expr == null) return "";
        return expr
                .replace('÷', '/')
                .replace('×', '*')
                .replace('\u00d7', '*')
                .replace('\u00f7', '/')
                .replaceAll(",(?=\\d)", ".")
                .replaceAll("(\\d+\\.?\\d*)%", "($1/100)")
                .replace('=', ' ')
                .trim();
    }

    private static String normalizeSpoken(String spoken) {
        return spoken.toLowerCase(Locale.ROOT)
                .replace("€", "")
                .replace("euro", "")
                .replace("euros", "")
                .replace("pièce", "piece")
                .replace("pièces", "pieces")
                .trim();
    }

    public static String exprKey(double a, double b) {
        return formatNum(a) + "*" + formatNum(b);
    }

    public static boolean isProcessed(Set<String> processed, String key) {
        return key != null && processed != null && processed.contains(key);
    }

    private static Result tryMultiply(String t, Set<String> processed, boolean forceRecalc) {
        if (!t.contains("combien") && !t.contains("calcule") && !t.contains("calcul")
                && !MULT.matcher(t).find()) {
            return null;
        }
        Matcher m = MULT.matcher(t);
        if (!m.find()) return null;
        double a = parseNum(m.group(1));
        double b = parseNum(m.group(2));
        if (a < 0 || b < 0) return null;
        String key = exprKey(a, b);
        if (!forceRecalc && isProcessed(processed, key)) return null;
        double product = a * b;
        List<String> sketch = List.of(
                formatNum(a) + " × " + formatNum(b),
                "↓",
                "= " + formatNum(product));
        String speak = formatNum(a) + " fois " + formatNum(b) + " égale " + formatNum(product);
        return new Result(speak, key, sketch, List.of());
    }

    private static Result tryBinary(String t, Pattern pattern, String sketchOp, String speakOp,
            Set<String> processed, boolean forceRecalc) {
        if (!t.contains("combien") && !t.contains("calcule") && !t.contains("calcul")
                && !pattern.matcher(t).find()) {
            return null;
        }
        Matcher m = pattern.matcher(t);
        if (!m.find()) return null;
        double a = parseNum(m.group(1));
        double b = parseNum(m.group(2));
        if (Double.isNaN(a) || Double.isNaN(b)) return null;
        double value;
        String keyOp;
        if ("+".equals(sketchOp)) {
            value = a + b;
            keyOp = "+";
        } else if ("−".equals(sketchOp)) {
            value = a - b;
            keyOp = "-";
        } else {
            return null;
        }
        String key = formatNum(a) + keyOp + formatNum(b);
        if (!forceRecalc && isProcessed(processed, key)) return null;
        List<String> sketch = List.of(
                formatNum(a) + " " + sketchOp + " " + formatNum(b),
                "↓",
                "= " + formatNum(value));
        String speak = formatNum(a) + " " + speakOp + " " + formatNum(b)
                + " égale " + formatNum(value);
        return new Result(speak, key, sketch, List.of());
    }

    private static Result tryDivide(String t, Set<String> processed, boolean forceRecalc) {
        if (!t.contains("combien") && !t.contains("calcule") && !t.contains("calcul")
                && !DIV.matcher(t).find()) {
            return null;
        }
        Matcher m = DIV.matcher(t);
        if (!m.find()) return null;
        double a = parseNum(m.group(1));
        double b = parseNum(m.group(2));
        if (b == 0 || Double.isNaN(a) || Double.isNaN(b)) return null;
        String key = formatNum(a) + "/" + formatNum(b);
        if (!forceRecalc && isProcessed(processed, key)) return null;
        double value = a / b;
        List<String> sketch = List.of(
                formatNum(a) + " ÷ " + formatNum(b),
                "↓",
                "= " + formatNum(value));
        String speak = formatNum(a) + " divisé par " + formatNum(b)
                + " égale " + formatNum(value);
        return new Result(speak, key, sketch, List.of());
    }

    private static Result tryPureExpression(String t, Set<String> processed, boolean forceRecalc) {
        String stripped = t.replaceAll(
                "(?i)\\b(calcule|calcul|combien\\s+font|combien\\s+fait)\\b", " ").trim();
        String expr = normalizeExpression(stripped);
        if (expr.isEmpty()) return null;
        // Après normalisation : chiffres, opérateurs ASCII, parenthèses, espaces
        if (!expr.matches("^[\\d\\s.+\\-*/()]+$")) return null;
        if (!expr.matches(".*[+\\-*/()].*")) return null;
        Double value = evaluateNormalized(expr);
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) return null;
        String key = "expr:" + expr.replace(" ", "");
        if (!forceRecalc && isProcessed(processed, key)) return null;
        String formatted = formatNum(value);
        List<String> sketch = List.of(stripped.trim().isEmpty() ? expr : stripped.trim(),
                "↓", "= " + formatted);
        return new Result("Ça fait " + formatted, key, sketch, List.of());
    }

    /**
     * Évalue une expression arithmétique simple (+ − * / et parenthèses).
     * @return null si l'expression est invalide
     */
    public static Double evaluateExpression(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String normalized = normalizeExpression(raw);
        if (normalized.isEmpty() || "?".equals(normalized.trim())) return null;
        if (!normalized.matches(".*\\d.*")) return null;
        try {
            return evaluateNormalized(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double evaluateNormalized(String normalized) {
        try {
            String s = normalized.replaceAll("\\s+", "");
            if (s.isEmpty()) return null;
            int[] i = {0};
            double v = parseExpr(s, i);
            if (i[0] != s.length()) return null; // reste non consommé → invalide
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseExpr(String s, int[] i) {
        double v = parseTerm(s, i);
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]);
            if (c == '+') {
                i[0]++;
                v += parseTerm(s, i);
            } else if (c == '-') {
                i[0]++;
                v -= parseTerm(s, i);
            } else {
                break;
            }
        }
        return v;
    }

    private static double parseTerm(String s, int[] i) {
        double v = parseFactor(s, i);
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]);
            if (c == '*') {
                i[0]++;
                v *= parseFactor(s, i);
            } else if (c == '/') {
                i[0]++;
                double d = parseFactor(s, i);
                if (d == 0) throw new ArithmeticException("div0");
                v /= d;
            } else {
                break;
            }
        }
        return v;
    }

    private static double parseFactor(String s, int[] i) {
        if (i[0] >= s.length()) throw new IllegalArgumentException("eof");
        char c = s.charAt(i[0]);
        if (c == '+') {
            i[0]++;
            return parseFactor(s, i);
        }
        if (c == '-') {
            i[0]++;
            return -parseFactor(s, i);
        }
        if (c == '(') {
            i[0]++;
            double v = parseExpr(s, i);
            if (i[0] >= s.length() || s.charAt(i[0]) != ')') {
                throw new IllegalArgumentException("paren");
            }
            i[0]++;
            return v;
        }
        int start = i[0];
        while (i[0] < s.length()
                && (Character.isDigit(s.charAt(i[0])) || s.charAt(i[0]) == '.')) {
            i[0]++;
        }
        if (start == i[0]) throw new IllegalArgumentException("num");
        return Double.parseDouble(s.substring(start, i[0]));
    }

    private static Result tryRevenue(String t, Set<String> processed, boolean forceRecalc) {
        boolean wantsRevenue = t.contains("vend") || t.contains("chiffre")
                || t.contains("ca ") || t.contains("combien") || t.contains("rapporte");
        Matcher m = QTY_PRICE.matcher(t);
        if (!m.find()) {
            m = MULT.matcher(t);
            if (!wantsRevenue || !m.find()) return null;
        }
        double qty = parseNum(m.group(1));
        double price = parseNum(m.group(2));
        if (qty <= 0 || price <= 0) return null;
        String key = "ca:" + exprKey(qty, price);
        if (!forceRecalc && isProcessed(processed, key)) return null;
        double total = qty * price;
        List<String> sketch = List.of(
                formatNum(qty) + " × " + formatMoney(price),
                "↓",
                "= " + formatMoney(total));
        List<String> detail = List.of("CA " + formatMoney(total));
        return new Result("Ça fait " + formatMoney(total), key, sketch, detail);
    }

    private static Result tryMargin(String t, Set<String> processed, boolean forceRecalc) {
        if (!t.contains("marge") && !t.contains("coût") && !t.contains("cout")
                && !t.contains("coûte") && !t.contains("coute")) {
            return null;
        }
        List<Double> nums = extractNumbers(t);
        if (nums.size() < 2) return null;

        Double marginPct = findMarginPercent(t);
        Double cost = findLabeledNumber(t, "coût", "cout", "coûte", "coute");
        Double price = findLabeledNumber(t, "prix", "vend", "vente");
        Double qty = findLabeledNumber(t, "piece", "pieces", "unité", "unite");

        if (cost == null && nums.size() >= 2) {
            if (marginPct != null) cost = nums.get(0);
            else {
                cost = nums.get(0);
                price = nums.size() > 1 ? nums.get(1) : null;
            }
        }
        if (price == null && nums.size() >= 2 && marginPct == null) {
            price = nums.get(nums.size() - 1);
            if (cost == null) cost = nums.get(0);
        }

        if (marginPct != null && cost != null && cost > 0 && marginPct > 0 && marginPct < 100) {
            String key = "marge:" + formatNum(cost) + "@" + formatNum(marginPct);
            if (!forceRecalc && isProcessed(processed, key)) return null;
            double targetPrice = cost / (1.0 - marginPct / 100.0);
            double profitUnit = targetPrice - cost;
            List<String> sketch = List.of(
                    "marge " + formatNum(marginPct) + " %",
                    "↓",
                    "≈ " + formatMoney(targetPrice));
            List<String> detail = new ArrayList<>();
            detail.add("coût " + formatMoney(cost));
            detail.add("gain / pièce ≈ " + formatMoney(profitUnit));
            if (qty != null && qty > 0) {
                detail.add(qtyLabel(qty) + " → " + formatMoney(profitUnit * qty));
            }
            return new Result("Vends à au moins " + formatMoney(targetPrice), key, sketch, detail);
        }

        if (cost != null && price != null && cost > 0 && price > 0) {
            String key = "marge:" + formatNum(cost) + "/" + formatNum(price);
            if (!forceRecalc && isProcessed(processed, key)) return null;
            double margin = (price - cost) / price * 100.0;
            double profitUnit = price - cost;
            List<String> sketch = List.of(
                    formatMoney(cost) + " → " + formatMoney(price),
                    "↓",
                    "= " + formatNum(margin) + " %");
            List<String> detail = new ArrayList<>();
            detail.add("gain / pièce " + formatMoney(profitUnit));
            if (qty != null && qty > 0) {
                detail.add(qtyLabel(qty) + " · CA " + formatMoney(qty * price));
            }
            return new Result("Marge de " + formatNum(margin) + " pour cent.", key, sketch, detail);
        }

        return null;
    }

    private static Double findMarginPercent(String t) {
        Matcher m = Pattern.compile("(\\d+[\\.,]?\\d*)\\s*(?:%|pour\\s*cents?)").matcher(t);
        if (m.find()) return parseNum(m.group(1));
        if (t.contains("marge")) {
            for (double n : extractNumbers(t)) {
                if (n > 0 && n < 100) return n;
            }
        }
        return null;
    }

    private static Double findLabeledNumber(String t, String... labels) {
        for (String label : labels) {
            Matcher m = Pattern.compile(label + "\\s*(?:de|à|a|=)?\\s*(\\d+[\\.,]?\\d*)",
                    Pattern.CASE_INSENSITIVE).matcher(t);
            if (m.find()) return parseNum(m.group(1));
        }
        return null;
    }

    private static List<Double> extractNumbers(String t) {
        List<Double> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+[\\.,]?\\d*").matcher(t);
        while (m.find()) {
            try {
                out.add(parseNum(m.group()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static String qtyLabel(double qty) {
        return formatNum(qty) + " pièces";
    }

    private static double parseNum(String raw) {
        if (raw == null) return -1;
        return Double.parseDouble(raw.replace(',', '.').replace(" ", "").trim());
    }

    private static String formatNum(double v) {
        java.text.DecimalFormatSymbols sym = new java.text.DecimalFormatSymbols(Locale.FRANCE);
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.###", sym);
        return df.format(v);
    }

    private static String formatMoney(double v) {
        return formatNum(v) + " €";
    }
}
