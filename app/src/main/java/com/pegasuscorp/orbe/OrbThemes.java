package com.pegasuscorp.orbe;

import android.graphics.Color;

/**
 * Palettes de couleurs pour l'orbe et les accents UI.
 */
public final class OrbThemes {

    public static final class Palette {
        public final String label;
        public final int core;
        public final int middle;
        public final int edge;

        Palette(String label, int core, int middle, int edge) {
            this.label = label;
            this.core = core;
            this.middle = middle;
            this.edge = edge;
        }
    }

    public static final Palette[] ALL = {
            new Palette("Cyan", Color.parseColor("#B8FBF6"),
                    Color.parseColor("#35D0DD"), Color.parseColor("#0B7D8F")),
            new Palette("Émeraude", Color.parseColor("#B8FBE6"),
                    Color.parseColor("#35DDAC"), Color.parseColor("#0B8F6B")),
            new Palette("Azur", Color.parseColor("#B8E6FB"),
                    Color.parseColor("#35ACDD"), Color.parseColor("#0B6B8F")),
            new Palette("Améthyste", Color.parseColor("#E8D4FB"),
                    Color.parseColor("#9B6BDD"), Color.parseColor("#5B2D8F")),
            new Palette("Corail", Color.parseColor("#FFD4C8"),
                    Color.parseColor("#FF7A5C"), Color.parseColor("#B33A22")),
    };

    private OrbThemes() {}

    public static Palette get(int index) {
        if (index < 0 || index >= ALL.length) return ALL[0];
        return ALL[index];
    }
}
