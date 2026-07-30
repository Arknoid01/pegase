package com.pegasuscorp.orbe.ui;

import android.graphics.Color;
import android.graphics.Typeface;

/**
 * Tokens partagés Home ↔ Pégase (couleurs, densités, motion).
 */
public final class OrbeTokens {

    private OrbeTokens() {}

    /** Aligné sur orbe_window_bg / home. */
    public static final String BG = "#0B0E14";
    public static final String CARD = "#1A1A1A";
    public static final String CARD_ACTIVE = "#1E2A2A";
    public static final String CYAN = "#35D0DD";
    public static final String CYAN_CORE = "#B8FBF6";
    public static final String CYAN_EDGE = "#0B7D8F";
    public static final String TEXT = "#FFFFFF";
    public static final String MUTED = "#8A8A8A";
    public static final String SEP = "#2A2A2A";

    public static final int COLOR_BG = Color.parseColor(BG);
    public static final int COLOR_CARD = Color.parseColor(CARD);
    public static final int COLOR_CARD_ACTIVE = Color.parseColor(CARD_ACTIVE);
    public static final int COLOR_CYAN = Color.parseColor(CYAN);
    public static final int COLOR_TEXT = Color.parseColor(TEXT);
    public static final int COLOR_MUTED = Color.parseColor(MUTED);
    public static final int COLOR_SEP = Color.parseColor(SEP);

    /** Bulle copilote / overlay. */
    public static final String USER_BUBBLE = "#1E3A40";
    public static final String CHIP_BG = "#152428";
    public static final String CHIP_STROKE = "#2A4048";
    public static final String INPUT_BG = "#121820";
    public static final String MUTED_TEXT = "#88FFFFFF";

    public static final int COLOR_USER_BUBBLE = Color.parseColor(USER_BUBBLE);
    public static final int COLOR_CHIP_BG = Color.parseColor(CHIP_BG);
    public static final int COLOR_CHIP_STROKE = Color.parseColor(CHIP_STROKE);
    public static final int COLOR_INPUT_BG = Color.parseColor(INPUT_BG);
    public static final int COLOR_MUTED_TEXT = Color.parseColor(MUTED_TEXT);

    public static final int PAD_SM = 8;
    public static final int PAD_MD = 12;
    public static final int PAD_LG = 16;
    public static final int RADIUS_SM = 8;
    public static final int RADIUS_MD = 10;
    public static final int RADIUS_LG = 12;

    /** Fade contenu onglet / entrée interface. */
    public static final int FADE_OUT_MS = 110;
    public static final int FADE_IN_MS = 150;
    public static final int ENTER_MS = 220;

    public static Typeface typeLight() {
        return Typeface.create("sans-serif-light", Typeface.NORMAL);
    }

    public static Typeface typeMedium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }
}
