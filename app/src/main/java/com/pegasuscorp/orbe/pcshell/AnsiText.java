package com.pegasuscorp.orbe.pcshell;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.regex.Pattern;

/**
 * Parse / filtre les séquences CSI SGR (couleurs ANSI) pour affichage TextView.
 */
public final class AnsiText {

    private static final int ESC = 0x1B;

    /** Séquences échappées en texte brut ({@code \\x1b}, {@code \\033}, {@code \\e[}). */
    private static final Pattern LITERAL_X1B = Pattern.compile("\\\\x1[Bb]");
    private static final Pattern LITERAL_OCT = Pattern.compile("\\\\033");
    private static final Pattern LITERAL_E = Pattern.compile("\\\\e\\[");
    /** SGR sans byte ESC (parfois après transport SSH). */
    private static final Pattern BARE_SGR = Pattern.compile("(?<!\u001B)\\[([0-9;?]+)m");

    /** 16 couleurs ANSI de base (index 0–15). */
    private static final int[] ANSI16 = {
            Color.parseColor("#000000"), Color.parseColor("#CC0000"),
            Color.parseColor("#4E9A06"), Color.parseColor("#C4A000"),
            Color.parseColor("#3465A4"), Color.parseColor("#75507B"),
            Color.parseColor("#06989A"), Color.parseColor("#D3D7CF"),
            Color.parseColor("#555753"), Color.parseColor("#EF2929"),
            Color.parseColor("#8AE234"), Color.parseColor("#FCE94F"),
            Color.parseColor("#729FCF"), Color.parseColor("#AD7FA8"),
            Color.parseColor("#34E2E2"), Color.parseColor("#EEEEEC"),
    };

    private static final int[] XTERM6 = {0, 95, 135, 175, 215, 255};

    private AnsiText() {}

    public static boolean containsEscape(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.indexOf(ESC) >= 0) return true;
        if (text.indexOf('\\') >= 0) {
            if (LITERAL_X1B.matcher(text).find()) return true;
            if (LITERAL_OCT.matcher(text).find()) return true;
            if (LITERAL_E.matcher(text).find()) return true;
        }
        return BARE_SGR.matcher(text).find();
    }

    /**
     * Convertit les formes littérales ({@code \\x1b[38;5;39m}) et SGR nus en vraies séquences CSI.
     * Inclut la fusion des {@code \\r} (mode flux linéaire).
     */
    public static String normalize(String text) {
        return collapseCarriageReturns(normalizeEscapes(text));
    }

    /** Normalisation sans fusion des retours chariot (mode grille terminal). */
    public static String normalizeEscapes(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text;
        if (s.indexOf('\\') >= 0) {
            s = LITERAL_X1B.matcher(s).replaceAll("\u001B");
            s = LITERAL_OCT.matcher(s).replaceAll("\u001B");
            s = LITERAL_E.matcher(s).replaceAll("\u001B[");
        }
        if (s.indexOf('[') >= 0) {
            s = BARE_SGR.matcher(s).replaceAll("\u001B[$1m");
        }
        return s;
    }

    /** {@code \\r} sans {@code \\n} : écrase le début de la ligne courante (comportement terminal). */
    private static String collapseCarriageReturns(String text) {
        if (text.indexOf('\r') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    continue;
                }
                int lastNl = out.lastIndexOf("\n");
                out.setLength(lastNl < 0 ? 0 : lastNl + 1);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Retire toutes les séquences d'échappement CSI / OSC. */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        text = normalize(text);
        if (text.indexOf(ESC) < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int ch = text.charAt(i);
            if (ch == ESC) {
                int next = skipEscape(text, i);
                if (next < 0) {
                    out.append((char) ch);
                    i++;
                } else {
                    i = next;
                }
                continue;
            }
            out.append((char) ch);
            i++;
        }
        return out.toString();
    }

    public static SpannableStringBuilder toSpannable(String text, int defaultFg) {
        if (needsTerminalGrid(text)) {
            return AnsiTerminalRenderer.render(text, SshConnectionManager.PTY_COLS, defaultFg);
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        appendTo(out, text, defaultFg);
        return out;
    }

    /** Détecte un positionnement curseur (fastfetch, neofetch, etc.). */
    public static boolean needsTerminalGrid(String text) {
        return AnsiTerminalRenderer.needsGridRender(text);
    }

    /** Ajoute {@code text} dans {@code target} en interprétant les codes SGR. */
    public static void appendTo(SpannableStringBuilder target, String text, int defaultFg) {
        if (text == null || text.isEmpty()) return;
        text = normalize(text);
        if (text.indexOf(ESC) < 0) {
            appendPlain(target, text, defaultFg, 0, false);
            return;
        }

        Style state = new Style(defaultFg, Color.TRANSPARENT, false);
        int i = 0;
        int segmentStart = 0;
        while (i < text.length()) {
            int ch = text.charAt(i);
            if (ch == ESC) {
                flushSegment(target, text, segmentStart, i, state);
                int next = parseCsiSgr(text, i, state);
                if (next < 0) {
                    int skipped = skipEscape(text, i);
                    if (skipped < 0) {
                        target.append((char) ch);
                        segmentStart = i + 1;
                        i++;
                    } else {
                        segmentStart = skipped;
                        i = skipped;
                    }
                } else {
                    segmentStart = next;
                    i = next;
                }
                continue;
            }
            i++;
        }
        flushSegment(target, text, segmentStart, text.length(), state);
    }

    private static void flushSegment(SpannableStringBuilder target, String text,
            int textStart, int textEnd, Style state) {
        if (textStart >= textEnd) return;
        appendPlain(target, text.substring(textStart, textEnd), state.fg, state.bg, state.bold);
    }

    private static void appendPlain(SpannableStringBuilder target, String chunk,
            int fg, int bg, boolean bold) {
        if (chunk.isEmpty()) return;
        int start = target.length();
        target.append(chunk);
        int end = target.length();
        target.setSpan(new ForegroundColorSpan(fg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bg != Color.TRANSPARENT) {
            target.setSpan(new BackgroundColorSpan(bg), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            target.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** @return index après la séquence, ou -1 si ce n'est pas une séquence reconnue. */
    private static int skipEscape(String text, int escIndex) {
        int len = text.length();
        if (escIndex + 1 >= len) return -1;
        char lead = text.charAt(escIndex + 1);
        if (lead == '[') {
            return skipCsi(text, escIndex + 2);
        }
        if (lead == ']') {
            return skipOsc(text, escIndex + 2);
        }
        if (lead == '(' || lead == ')' || lead == '*' || lead == '+' || lead == '-') {
            return escIndex + 2 < len ? escIndex + 3 : len;
        }
        return -1;
    }

    private static int parseCsiSgr(String text, int escIndex, Style state) {
        int len = text.length();
        if (escIndex + 1 >= len || text.charAt(escIndex + 1) != '[') return -1;
        int i = escIndex + 2;
        while (i < len) {
            char c = text.charAt(i);
            if (c >= 0x40 && c <= 0x7E) {
                if (c == 'm') {
                    applySgr(text.substring(escIndex + 2, i), state);
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int skipCsi(String text, int i) {
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c >= 0x40 && c <= 0x7E) return i + 1;
            i++;
        }
        return len;
    }

    private static int skipOsc(String text, int i) {
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == 0x07) return i + 1;
            if (c == ESC && i + 1 < len && text.charAt(i + 1) == '\\') return i + 2;
            i++;
        }
        return len;
    }

    private static void applySgr(String params, Style state) {
        if (params.isEmpty()) {
            state.reset(state.defaultFg);
            return;
        }
        int i = 0;
        int len = params.length();
        while (i < len) {
            int semi = params.indexOf(';', i);
            String token = semi < 0 ? params.substring(i) : params.substring(i, semi);
            i = semi < 0 ? len : semi + 1;
            int code;
            try {
                code = token.isEmpty() ? 0 : Integer.parseInt(token);
            } catch (NumberFormatException e) {
                continue;
            }

            if (code == 0) {
                state.reset(state.defaultFg);
            } else if (code == 1) {
                state.bold = true;
            } else if (code == 22) {
                state.bold = false;
            } else if (code >= 30 && code <= 37) {
                state.fg = ANSI16[code - 30];
            } else if (code == 39) {
                state.fg = state.defaultFg;
            } else if (code >= 40 && code <= 47) {
                state.bg = ANSI16[code - 40];
            } else if (code == 49) {
                state.bg = Color.TRANSPARENT;
            } else if (code >= 90 && code <= 97) {
                state.fg = ANSI16[code - 90 + 8];
            } else if (code >= 100 && code <= 107) {
                state.bg = ANSI16[code - 100 + 8];
            } else if (code == 38 && i < len) {
                i = applyExtendedColor(params, i, true, state);
            } else if (code == 48 && i < len) {
                i = applyExtendedColor(params, i, false, state);
            }
        }
    }

    /** Consomme les paramètres après {@code 38} ou {@code 48} ; retourne nouvel index dans {@code params}. */
    private static int applyExtendedColor(String params, int i, boolean fg, Style state) {
        int len = params.length();
        int semi = params.indexOf(';', i);
        String modeToken = semi < 0 ? params.substring(i) : params.substring(i, semi);
        int mode;
        try {
            mode = Integer.parseInt(modeToken);
        } catch (NumberFormatException e) {
            return len;
        }
        i = semi < 0 ? len : semi + 1;

        if (mode == 5) {
            int semi2 = params.indexOf(';', i);
            String nToken = semi2 < 0 ? params.substring(i) : params.substring(i, semi2);
            try {
                int color = color256(Integer.parseInt(nToken));
                if (fg) state.fg = color;
                else state.bg = color;
            } catch (NumberFormatException ignored) {
            }
            return semi2 < 0 ? len : semi2 + 1;
        }
        if (mode == 2) {
            int[] rgb = new int[3];
            for (int c = 0; c < 3; c++) {
                int semiN = params.indexOf(';', i);
                String part = semiN < 0 ? params.substring(i) : params.substring(i, semiN);
                try {
                    rgb[c] = clamp255(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    return len;
                }
                i = semiN < 0 ? len : semiN + 1;
            }
            int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
            if (fg) state.fg = color;
            else state.bg = color;
            return i;
        }
        return i;
    }

    private static int color256(int n) {
        if (n < 0) n = 0;
        if (n > 255) n = 255;
        if (n < 16) return ANSI16[n];
        if (n < 232) {
            n -= 16;
            int r = XTERM6[n / 36];
            int g = XTERM6[(n / 6) % 6];
            int b = XTERM6[n % 6];
            return Color.rgb(r, g, b);
        }
        int gray = 8 + (n - 232) * 10;
        return Color.rgb(gray, gray, gray);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static final class Style {
        final int defaultFg;
        int fg;
        int bg;
        boolean bold;

        Style(int defaultFg, int bg, boolean bold) {
            this.defaultFg = defaultFg;
            reset(defaultFg);
            this.bg = bg;
            this.bold = bold;
        }

        void reset(int fg) {
            this.fg = fg;
            this.bg = Color.TRANSPARENT;
            this.bold = false;
        }
    }
}
