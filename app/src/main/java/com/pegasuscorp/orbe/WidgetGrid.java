package com.pegasuscorp.orbe;

/**
 * Grille magnétique pour aligner et redimensionner les widgets.
 */
public final class WidgetGrid {

    public static final int COLUMNS = 4;

    private final int cellSizePx;
    private final int gapPx;

    public WidgetGrid(int screenWidthPx, float density) {
        gapPx = Math.max(2, (int) (4 * density));
        cellSizePx = Math.max(1, screenWidthPx / COLUMNS);
    }

    public int getCellSizePx() {
        return cellSizePx;
    }

    public int getGapPx() {
        return gapPx;
    }

    public int snapCoord(int px) {
        return Math.round((float) px / cellSizePx) * cellSizePx;
    }

    public int pxToSpan(int px) {
        return Math.max(1, Math.round((float) (px + gapPx) / cellSizePx));
    }

    public int spanToPx(int span) {
        return Math.max(cellSizePx - gapPx, span * cellSizePx - gapPx);
    }

    public int clampSpan(int span, int minSpan, int maxSpan) {
        return Math.max(minSpan, Math.min(maxSpan, span));
    }

    /**
     * info.minWidth / info.minHeight sont deja en PIXELS (le framework convertit les dp
     * du XML a la densite de l'appareil au chargement). Il ne faut donc PAS remultiplier
     * par la densite, sinon le span minimum est surdimensionne : le widget ne peut plus
     * retrecir et deborde de la grille.
     */
    public int minSpanForPx(int minPx) {
        if (minPx <= 0) return 1;
        return Math.max(1, pxToSpan(minPx));
    }

    /** Largeur totale de la grille (COLUMNS colonnes). */
    public int gridWidthPx() {
        return COLUMNS * cellSizePx;
    }

    /** Index de colonne (0..COLUMNS) correspondant a une coordonnee X. */
    public int columnOf(int xPx) {
        return Math.max(0, Math.round((float) xPx / cellSizePx));
    }
}
