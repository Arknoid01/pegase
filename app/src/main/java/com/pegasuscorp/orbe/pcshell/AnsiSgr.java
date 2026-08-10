package com.pegasuscorp.orbe.pcshell;

import android.graphics.Color;

/** Parse des codes SGR ANSI ({@code \\e[…m}). */
final class AnsiSgr {

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

    private AnsiSgr() {}

    static void apply(String params, AnsiTerminalRenderer.Style state) {
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

    private static int applyExtendedColor(String params, int i, boolean fg,
            AnsiTerminalRenderer.Style state) {
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
}
