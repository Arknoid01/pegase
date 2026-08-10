package com.pegasuscorp.orbe.pcshell;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rendu terminal statique — grille + positionnement curseur (fastfetch, neofetch…).
 */
public final class AnsiTerminalRenderer {

    private static final int ESC = 0x1B;

    private static final Pattern CURSOR_CSI = Pattern.compile(
            "\u001B\\[[0-9;?]*[HfGABCDsuJK]");

    private AnsiTerminalRenderer() {}

    public static boolean needsGridRender(String text) {
        if (text == null || text.isEmpty()) return false;
        String n = AnsiText.normalizeEscapes(text);
        if (n.indexOf(ESC) < 0) return false;
        return CURSOR_CSI.matcher(n).find();
    }

    public static SpannableStringBuilder render(String text, int cols, int defaultFg) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        appendTo(out, text, cols, defaultFg);
        return out;
    }

    public static void appendTo(SpannableStringBuilder target, String text, int cols, int defaultFg) {
        if (text == null || text.isEmpty()) return;
        Grid grid = new Grid(Math.max(40, cols), defaultFg);
        grid.feed(AnsiText.normalizeEscapes(text));
        grid.appendTo(target);
    }

    private static final class Grid {
        private final int cols;
        private final int defaultFg;
        private final List<StringBuilder> chars = new ArrayList<>();
        private final List<int[]> fg = new ArrayList<>();
        private final List<boolean[]> bold = new ArrayList<>();
        private int row;
        private int col;
        private int savedRow;
        private int savedCol;
        private Style style;

        Grid(int cols, int defaultFg) {
            this.cols = cols;
            this.defaultFg = defaultFg;
            this.style = new Style(defaultFg);
            ensureRow(0);
        }

        void feed(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ESC) {
                    int next = parseEscape(text, i);
                    if (next < 0) {
                        putChar((char) ESC);
                        i++;
                    } else {
                        i = next - 1;
                    }
                    continue;
                }
                if (c == '\n') {
                    row++;
                    col = 0;
                    ensureRow(row);
                    continue;
                }
                if (c == '\r') {
                    col = 0;
                    continue;
                }
                if (c == '\t') {
                    int tabStop = ((col / 8) + 1) * 8;
                    while (col < tabStop) {
                        putChar(' ');
                    }
                    continue;
                }
                if (c < 0x20 && c != 0x09) {
                    continue;
                }
                putChar(c);
            }
        }

        void appendTo(SpannableStringBuilder target) {
            trimTrailingBlankRows();
            for (int r = 0; r < chars.size(); r++) {
                if (r > 0) {
                    target.append('\n');
                }
                appendLine(target, chars.get(r), fg.get(r), bold.get(r));
            }
        }

        private void appendLine(SpannableStringBuilder target, StringBuilder line,
                int[] colors, boolean[] bolds) {
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            if (end == 0) return;

            int runStart = 0;
            while (runStart < end) {
                int runEnd = runStart + 1;
                int fgColor = colors[runStart];
                boolean isBold = bolds[runStart];
                while (runEnd < end
                        && colors[runEnd] == fgColor
                        && bolds[runEnd] == isBold) {
                    runEnd++;
                }
                int start = target.length();
                target.append(line, runStart, runEnd);
                target.setSpan(new ForegroundColorSpan(fgColor), start, target.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (isBold) {
                    target.setSpan(new StyleSpan(Typeface.BOLD), start, target.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                runStart = runEnd;
            }
        }

        private void trimTrailingBlankRows() {
            while (!chars.isEmpty()) {
                StringBuilder last = chars.get(chars.size() - 1);
                boolean blank = true;
                for (int i = 0; i < last.length(); i++) {
                    if (last.charAt(i) != ' ') {
                        blank = false;
                        break;
                    }
                }
                if (!blank) break;
                chars.remove(chars.size() - 1);
                fg.remove(fg.size() - 1);
                bold.remove(bold.size() - 1);
            }
        }

        private void putChar(char c) {
            if (col >= cols) {
                row++;
                col = 0;
                ensureRow(row);
            }
            ensureRow(row);
            StringBuilder line = chars.get(row);
            int needed = col + 1;
            if (line.length() < needed) {
                int oldLen = line.length();
                while (line.length() < needed) {
                    line.append(' ');
                }
                growLineArrays(row, line.length());
                int[] colors = fg.get(row);
                boolean[] bolds = bold.get(row);
                for (int i = oldLen; i < col; i++) {
                    colors[i] = style.fg;
                    bolds[i] = style.bold;
                }
            } else {
                growLineArrays(row, line.length());
            }
            line.setCharAt(col, c);
            fg.get(row)[col] = style.fg;
            bold.get(row)[col] = style.bold;
            col++;
        }

        private void growLineArrays(int rowIndex, int minLen) {
            int[] colors = fg.get(rowIndex);
            boolean[] bolds = bold.get(rowIndex);
            if (colors.length >= minLen) return;
            int[] newFg = new int[minLen];
            boolean[] newBold = new boolean[minLen];
            System.arraycopy(colors, 0, newFg, 0, colors.length);
            System.arraycopy(bolds, 0, newBold, 0, bolds.length);
            for (int i = colors.length; i < minLen; i++) {
                newFg[i] = defaultFg;
            }
            fg.set(rowIndex, newFg);
            bold.set(rowIndex, newBold);
        }

        private void ensureRow(int r) {
            while (chars.size() <= r) {
                chars.add(new StringBuilder());
                fg.add(new int[0]);
                bold.add(new boolean[0]);
            }
        }

        private int parseEscape(String text, int escIndex) {
            if (escIndex + 1 >= text.length()) return -1;
            char lead = text.charAt(escIndex + 1);
            if (lead == '[') {
                return parseCsi(text, escIndex);
            }
            if (lead == ']') {
                return skipOsc(text, escIndex + 2);
            }
            return -1;
        }

        private int parseCsi(String text, int escIndex) {
            int len = text.length();
            int i = escIndex + 2;
            while (i < len) {
                char c = text.charAt(i);
                if (c >= 0x40 && c <= 0x7E) {
                    String params = text.substring(escIndex + 2, i);
                    applyCsi(c, params);
                    return i + 1;
                }
                i++;
            }
            return len;
        }

        private void applyCsi(char cmd, String params) {
            switch (cmd) {
                case 'm':
                    applySgr(params);
                    break;
                case 'H':
                case 'f': {
                    int[] rc = parseRowCol(params);
                    row = Math.max(0, rc[0]);
                    col = Math.max(0, rc[1]);
                    ensureRow(row);
                    break;
                }
                case 'G': {
                    int column = parseSingleParam(params, 1) - 1;
                    col = Math.max(0, column);
                    break;
                }
                case 'A':
                    row = Math.max(0, row - parseSingleParam(params, 1));
                    ensureRow(row);
                    break;
                case 'B':
                    row = row + parseSingleParam(params, 1);
                    ensureRow(row);
                    break;
                case 'C':
                    col = col + parseSingleParam(params, 1);
                    break;
                case 'D':
                    col = Math.max(0, col - parseSingleParam(params, 1));
                    break;
                case 's':
                    savedRow = row;
                    savedCol = col;
                    break;
                case 'u':
                    row = savedRow;
                    col = savedCol;
                    ensureRow(row);
                    break;
                case 'J':
                    if (params.isEmpty() || "0".equals(params) || "2".equals(params)) {
                        clearBelow();
                    }
                    break;
                case 'K':
                    clearLineFromCursor();
                    break;
                default:
                    break;
            }
        }

        private void clearBelow() {
            for (int r = row; r < chars.size(); r++) {
                if (r == row) {
                    clearLineFromCursor();
                } else {
                    chars.set(r, new StringBuilder());
                    fg.set(r, new int[0]);
                    bold.set(r, new boolean[0]);
                }
            }
        }

        private void clearLineFromCursor() {
            ensureRow(row);
            StringBuilder line = chars.get(row);
            if (col < line.length()) {
                line.setLength(col);
                fg.set(row, trimArray(fg.get(row), col));
                bold.set(row, trimBoolArray(bold.get(row), col));
            }
        }

        private static int[] trimArray(int[] arr, int len) {
            if (arr.length <= len) return arr;
            int[] out = new int[len];
            System.arraycopy(arr, 0, out, 0, len);
            return out;
        }

        private static boolean[] trimBoolArray(boolean[] arr, int len) {
            if (arr.length <= len) return arr;
            boolean[] out = new boolean[len];
            System.arraycopy(arr, 0, out, 0, len);
            return out;
        }

        private static int parseSingleParam(String params, int defaultValue) {
            if (params == null || params.isEmpty()) return defaultValue;
            try {
                return Integer.parseInt(params.split(";", -1)[0]);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private static int[] parseRowCol(String params) {
            int row1 = 1;
            int col1 = 1;
            if (params != null && !params.isEmpty()) {
                String[] parts = params.split(";", -1);
                try {
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        row1 = Integer.parseInt(parts[0]);
                    }
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        col1 = Integer.parseInt(parts[1]);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return new int[] {row1 - 1, col1 - 1};
        }

        private void applySgr(String params) {
            AnsiSgr.apply(params, style);
        }

        private int skipOsc(String text, int i) {
            int len = text.length();
            while (i < len) {
                char c = text.charAt(i);
                if (c == 0x07) return i + 1;
                if (c == ESC && i + 1 < len && text.charAt(i + 1) == '\\') return i + 2;
                i++;
            }
            return len;
        }
    }

    /** État SGR partagé avec {@link AnsiText}. */
    static final class Style {
        final int defaultFg;
        int fg;
        int bg;
        boolean bold;

        Style(int defaultFg) {
            this.defaultFg = defaultFg;
            reset(defaultFg);
        }

        void reset(int fg) {
            this.fg = fg;
            this.bg = Color.TRANSPARENT;
            this.bold = false;
        }
    }
}
