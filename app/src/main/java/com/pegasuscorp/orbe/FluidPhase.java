package com.pegasuscorp.orbe;

import java.util.Calendar;

/**
 * Palette horaire du fond Fluid — transitions douces entre les phases de la journée.
 */
public final class FluidPhase {

    public static final class State {
        public final String label;
        public final int topColor;
        public final int bottomColor;
        public final int blobA;
        public final int blobB;
        public final int blobC;
        public final int veilAccent;

        State(String label, int top, int bottom, int blobA, int blobB, int blobC, int veil) {
            this.label = label;
            this.topColor = top;
            this.bottomColor = bottom;
            this.blobA = blobA;
            this.blobB = blobB;
            this.blobC = blobC;
            this.veilAccent = veil;
        }
    }

    private static final Keyframe[] KEYS = {
            new Keyframe(0f,   "Nuit",       0xFF060810, 0xFF0B0E14, 0x331A4A6E, 0x22183550, 0x180E2838, 0x330B5A72),
            new Keyframe(5.5f, "Aurore",     0xFF0A1420, 0xFF101828, 0x553A6080, 0x33487890, 0x28406070, 0x44387088),
            new Keyframe(8f,   "Matin",      0xFF0C1824, 0xFF0E1C2A, 0x6640A0B0, 0x4438B0C8, 0x3358A0B8, 0x5535A8C0),
            new Keyframe(12f,  "Midday",     0xFF0B1A22, 0xFF0C2030, 0x7735D0DD, 0x5548E0F0, 0x4038C8DC, 0x6635D0DD),
            new Keyframe(16f,  "Après-midi", 0xFF0A1820, 0xFF0C1C28, 0x6640C0D0, 0x4838B8CC, 0x3850B8C8, 0x5538C0D0),
            new Keyframe(19f,  "Soir",       0xFF0A121C, 0xFF101820, 0x55487888, 0x38406070, 0x30384858, 0x44306070),
            new Keyframe(22f,  "Nuit",       0xFF060810, 0xFF0B0E14, 0x331A4A6E, 0x22183550, 0x180E2838, 0x330B5A72),
            new Keyframe(24f,  "Nuit",       0xFF060810, 0xFF0B0E14, 0x331A4A6E, 0x22183550, 0x180E2838, 0x330B5A72),
    };

    private FluidPhase() {}

    public static State current() {
        return forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    public static State forHour(int hour, int minute) {
        float t = hour + minute / 60f;
        if (t >= 24f) t -= 24f;

        Keyframe a = KEYS[0];
        Keyframe b = KEYS[KEYS.length - 1];
        for (int i = 0; i < KEYS.length - 1; i++) {
            if (t >= KEYS[i].hour && t < KEYS[i + 1].hour) {
                a = KEYS[i];
                b = KEYS[i + 1];
                break;
            }
        }
        float span = b.hour - a.hour;
        float mix = span <= 0f ? 0f : (t - a.hour) / span;
        mix = smooth(mix);
        return new State(
                mix < 0.5f ? a.label : b.label,
                lerpColor(a.top, b.top, mix),
                lerpColor(a.bottom, b.bottom, mix),
                tintBlob(lerpColor(a.blobA, b.blobA, mix)),
                tintBlob(lerpColor(a.blobB, b.blobB, mix)),
                tintBlob(lerpColor(a.blobC, b.blobC, mix)),
                lerpColor(a.veil, b.veil, mix));
    }

    /** Teinte les blobs avec la palette orbe active. */
    public static State tinted(State base, OrbThemes.Palette palette) {
        if (palette == null) return base;
        return new State(
                base.label,
                base.topColor,
                base.bottomColor,
                blendBlob(base.blobA, palette.middle),
                blendBlob(base.blobB, palette.core),
                blendBlob(base.blobC, palette.edge),
                blendBlob(base.veilAccent, palette.middle));
    }

    private static int blendBlob(int baseArgb, int accent) {
        int ba = (baseArgb >>> 24) & 0xFF;
        int br = (baseArgb >> 16) & 0xFF;
        int bg = (baseArgb >> 8) & 0xFF;
        int bb = baseArgb & 0xFF;
        int ar = (accent >> 16) & 0xFF;
        int ag = (accent >> 8) & 0xFF;
        int ab = accent & 0xFF;
        float w = 0.38f;
        int r = (int) (br * (1f - w) + ar * w);
        int g = (int) (bg * (1f - w) + ag * w);
        int b = (int) (bb * (1f - w) + ab * w);
        return (ba << 24) | (r << 16) | (g << 8) | b;
    }

    private static int tintBlob(int color) {
        return color;
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int na = (int) (aa + (ba - aa) * t);
        int nr = (int) (ar + (br - ar) * t);
        int ng = (int) (ag + (bg - ag) * t);
        int nb = (int) (ab + (bb - ab) * t);
        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    private static final class Keyframe {
        final float hour;
        final String label;
        final int top, bottom, blobA, blobB, blobC, veil;

        Keyframe(float hour, String label, int top, int bottom,
                 int blobA, int blobB, int blobC, int veil) {
            this.hour = hour;
            this.label = label;
            this.top = top;
            this.bottom = bottom;
            this.blobA = blobA;
            this.blobB = blobB;
            this.blobC = blobC;
            this.veil = veil;
        }
    }
}
