package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Feuille du bureau.
 *
 * Deux calques strictement séparés :
 *  - userStrokes : tes traits. Pégase ne les modifie JAMAIS.
 *  - pegaseItems : ce qu'elle écrit / encadre, à côté.
 *
 * Nouveautés : lissage quadratique, rejet de paume, gomme, undo/redo,
 * ancrage des réponses sur la LIGNE d'écriture (pas sur le dernier trait),
 * taille de police calée sur ton écriture, restauration indépendante de la résolution.
 */
public class BureauCanvasView extends View {

    public static final String KIND_TEXT = "text";
    public static final String KIND_BOX = "box";
    public static final String KIND_CALC = "calc";

    public interface Listener {
        void onStrokeFinished();
        /** Un élément de Pégase vient d'être déplacé → l'activité persiste. */
        default void onItemMoved() {}
    }

    // ---------- modèle ----------

    public static final class Point {
        public float x, y;
        public Point(float x, float y) { this.x = x; this.y = y; }
    }

    public static final class Stroke {
        public final List<Point> points = new ArrayList<>();
        public int color = Color.parseColor("#D8F8F4");
        public float width = 5f;
        /** Encre déjà lue par Pégase — flag interne, aucun impact visuel. */
        public boolean processed;

        public Stroke copy() {
            Stroke s = new Stroke();
            s.color = color;
            s.width = width;
            s.processed = processed;
            for (Point p : points) s.points.add(new Point(p.x, p.y));
            return s;
        }
    }

    public static final class PegaseItem {
        public String id;
        public String kind = KIND_TEXT;
        public String text;
        public float x, y, w, h;
        public float size = 44f;

        public PegaseItem(String id, String kind, String text,
                          float x, float y, float w, float h, float size) {
            this.id = id;
            this.kind = kind == null ? KIND_TEXT : kind;
            this.text = text;
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.size = size <= 0 ? 44f : size;
        }
    }

    public static final class Snapshot {
        public List<Stroke> userStrokes = new ArrayList<>();
        public List<PegaseItem> pegaseItems = new ArrayList<>();
        /** Taille du canvas au moment de la capture — permet de rescaler à la restauration. */
        public float canvasW;
        public float canvasH;
    }

    // ---------- état ----------

    private final Paint userPaint = new Paint();
    private final Paint pegaseTextPaint = new Paint();
    private final Paint pegaseBoxPaint = new Paint();
    private final Paint gridPaint = new Paint();

    private final List<Stroke> userStrokes = new ArrayList<>();
    private final List<PegaseItem> pegaseItems = new ArrayList<>();
    private final List<Stroke> redoStack = new ArrayList<>();

    private Stroke activeStroke;
    private Path activePath;
    private float lastX, lastY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private boolean eraserMode;
    private boolean moveMode;

    // déplacement
    private PegaseItem dragItem;
    private float dragDx, dragDy;
    private Runnable longPress;
    private float downX, downY;
    private static final float TOUCH_SLOP = 14f;
    private boolean stylusSeen;          // dès qu'un stylet écrit, on ignore les doigts
    private int penColor = Color.parseColor("#D8F8F4");
    private float penWidth = 5f;
    private Listener listener;

    private float pendingRestoreW, pendingRestoreH;
    private Snapshot pendingRestore;

    public BureauCanvasView(Context context) { super(context); init(); }
    public BureauCanvasView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        setBackgroundColor(Color.parseColor("#12151C"));

        // "cursive" est joli mais absent/incomplet sur certains ROM → repli sûr.
        Typeface face;
        try {
            face = Typeface.create("cursive", Typeface.NORMAL);
            if (face == null || Typeface.DEFAULT.equals(face)) {
                face = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
            }
        } catch (Exception e) {
            face = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
        }

        userPaint.setAntiAlias(true);
        userPaint.setStyle(Paint.Style.STROKE);
        userPaint.setStrokeJoin(Paint.Join.ROUND);
        userPaint.setStrokeCap(Paint.Cap.ROUND);

        pegaseTextPaint.setAntiAlias(true);
        pegaseTextPaint.setColor(Color.parseColor("#F5D78E"));
        pegaseTextPaint.setTypeface(face);
        pegaseTextPaint.setLetterSpacing(0.02f);

        pegaseBoxPaint.setAntiAlias(true);
        pegaseBoxPaint.setStyle(Paint.Style.STROKE);
        pegaseBoxPaint.setStrokeWidth(3f);
        pegaseBoxPaint.setStrokeJoin(Paint.Join.ROUND);
        pegaseBoxPaint.setColor(Color.parseColor("#F5D78E"));

        gridPaint.setAntiAlias(false);
        gridPaint.setColor(Color.parseColor("#1B2130"));
        gridPaint.setStrokeWidth(1f);
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setEraserMode(boolean on) { eraserMode = on; if (on) moveMode = false; }
    public boolean isEraserMode() { return eraserMode; }

    public void setMoveMode(boolean on) { moveMode = on; if (on) eraserMode = false; }
    public boolean isMoveMode() { return moveMode; }

    public void setPen(int color, float width) {
        penColor = color;
        penWidth = width;
    }

    // ---------- snapshot / restore ----------

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        for (Stroke stroke : userStrokes) s.userStrokes.add(stroke.copy());
        for (PegaseItem item : pegaseItems) {
            s.pegaseItems.add(new PegaseItem(item.id, item.kind, item.text,
                    item.x, item.y, item.w, item.h, item.size));
        }
        s.canvasW = getWidth();
        s.canvasH = getHeight();
        return s;
    }

    /** Restaure TOUS les calques (texte inclus) et rescale si la taille du canvas a changé. */
    public void restore(Snapshot snapshot) {
        userStrokes.clear();
        pegaseItems.clear();
        redoStack.clear();
        if (snapshot == null) { invalidate(); return; }

        if (getWidth() == 0 || getHeight() == 0) {
            pendingRestore = snapshot;   // pas encore mesuré → on rejoue dans onSizeChanged
            invalidate();
            return;
        }
        applyRestore(snapshot);
    }

    private void applyRestore(Snapshot snapshot) {
        float sx = 1f, sy = 1f;
        if (snapshot.canvasW > 0 && snapshot.canvasH > 0) {
            sx = getWidth() / snapshot.canvasW;
            sy = getHeight() / snapshot.canvasH;
        }
        float s = Math.min(sx, sy);   // homothétie : on ne déforme pas l'écriture

        for (Stroke stroke : snapshot.userStrokes) {
            Stroke c = stroke.copy();
            for (Point p : c.points) { p.x *= s; p.y *= s; }
            c.width *= s;
            userStrokes.add(c);
        }
        for (PegaseItem item : snapshot.pegaseItems) {
            pegaseItems.add(new PegaseItem(item.id, item.kind, item.text,
                    item.x * s, item.y * s, item.w * s, item.h * s, item.size * s));
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (pendingRestore != null && w > 0 && h > 0) {
            Snapshot s = pendingRestore;
            pendingRestore = null;
            applyRestore(s);
        }
    }

    public Bitmap captureBitmap() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        draw(c);
        return bmp;
    }

    // ---------- calques ----------

    public boolean hasUserInk() { return !userStrokes.isEmpty(); }
    public boolean hasPegaseInk() { return !pegaseItems.isEmpty(); }

    public void clearUserLayer() {
        redoStack.clear();
        userStrokes.clear();
        activeStroke = null;
        activePath = null;
        invalidate();
    }

    public void clearPegaseLayer() {
        pegaseItems.clear();
        invalidate();
    }

    public void unmarkAllStrokesProcessed() {
        for (Stroke s : userStrokes) s.processed = false;
    }

    public boolean undo() {
        if (userStrokes.isEmpty()) return false;
        redoStack.add(userStrokes.remove(userStrokes.size() - 1));
        invalidate();
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        userStrokes.add(redoStack.remove(redoStack.size() - 1));
        invalidate();
        return true;
    }

    public List<Stroke> strokes() { return userStrokes; }

    public BureauGeometryReader.Scene scene() {
        return BureauGeometryReader.read(userStrokes, getWidth(), getHeight());
    }

    // ---------- écriture de Pégase (son calque uniquement) ----------

    private PegaseItem addText(String text, float x, float y, float size) {
        PegaseItem item = new PegaseItem(UUID.randomUUID().toString(), KIND_TEXT,
                text, x, y, 0f, 0f, size);
        pegaseItems.add(item);
        invalidate();
        return item;
    }

    /**
     * Pose la réponse d'un calcul juste après la ligne écrite (typiquement après le « = »).
     * Robuste : ne sort jamais sans rien écrire, clampe dans la feuille, log de diagnostic.
     */
    public void addCalcResult(BureauCalcHelper.Result calc) {
        if (calc == null) return;

        // Le canvas n'est pas encore mesuré → on rejoue après le layout.
        if (getWidth() == 0 || getHeight() == 0) {
            post(() -> addCalcResult(calc));
            return;
        }

        String inline = sanitize(inlineAnswer(calc));
        if (inline.isEmpty()) inline = "?";   // on écrit TOUJOURS quelque chose

        BureauGeometryReader.Scene scene = scene();
        BureauGeometryReader.Row row = scene.lastPendingRow();

        float size, x, y;
        if (row == null || row.box.height() <= 1f) {
            size = 46f;
            x = 72f;
            y = nextFreeY();
        } else {
            size = clamp(row.box.height() * 0.95f, 30f, 80f);
            pegaseTextPaint.setTextSize(size);
            float textW = pegaseTextPaint.measureText(inline);
            x = row.box.right + size * 0.45f;
            y = row.box.cy() + size * 0.36f;      // baseline centrée sur ta ligne
            if (x + textW > getWidth() - dp(8)) { // ça déborde → on passe dessous
                x = row.box.left;
                y = row.box.bottom + size * 1.15f;
            }
            markRowProcessed(row);
        }

        // Clamp : jamais hors de la feuille (sinon l'item existe mais reste invisible).
        pegaseTextPaint.setTextSize(size);
        float textW = pegaseTextPaint.measureText(inline);
        x = clamp(x, dp(6), Math.max(dp(6), getWidth() - textW - dp(6)));
        y = clamp(y, size + dp(6), getHeight() - dp(6));

        PegaseItem item = addText(inline, x, y, size);
        item.kind = KIND_CALC;

        android.util.Log.d("Bureau", "calc → \"" + inline + "\" @ " + x + "," + y
                + " size=" + size
                + " row=" + (row == null ? "null"
                        : (row.box.left + "→" + row.box.right + " h=" + row.box.height()))
                + " items=" + pegaseItems.size()
                + " canvas=" + getWidth() + "x" + getHeight());
    }

    /**
     * Pose une réponse en la calant sur une ZONE (le rectangle que tu as tracé) :
     * à droite du cadre s'il y a la place, sinon juste en dessous.
     */
    public void addAnswerForZone(String text, BureauGeometryReader.Box zone) {
        if (getWidth() == 0 || getHeight() == 0) {
            post(() -> addAnswerForZone(text, zone));
            return;
        }
        String inline = sanitize(text == null ? "" : text.trim());
        if (inline.isEmpty()) inline = "?";
        if (zone == null) {
            addPegaseLines(java.util.Collections.singletonList(inline));
            return;
        }

        float size = clamp(zone.height() * 0.42f, 30f, 72f);
        pegaseTextPaint.setTextSize(size);
        float textW = pegaseTextPaint.measureText(inline);

        float x = zone.right + size * 0.5f;
        float y = zone.cy() + size * 0.36f;
        if (x + textW > getWidth() - dp(8)) {         // pas la place à droite
            x = zone.left;
            y = zone.bottom + size * 1.3f;
        }
        x = clamp(x, dp(6), Math.max(dp(6), getWidth() - textW - dp(6)));
        y = clamp(y, size + dp(6), getHeight() - dp(6));

        PegaseItem item = addText(inline, x, y, size);
        item.kind = KIND_CALC;

        android.util.Log.d("Bureau", "zone → \"" + inline + "\" @ " + x + "," + y
                + " size=" + size + " zone=" + zone.left + "," + zone.top
                + "→" + zone.right + "," + zone.bottom + " items=" + pegaseItems.size());
    }

    /** Marque comme lus tous les traits d'une zone. */
    public void markZoneProcessed(BureauGeometryReader.Scene scene, BureauGeometryReader.Box zone) {
        if (scene == null || zone == null) return;
        for (int idx : scene.inkInside(zone)) {
            if (idx >= 0 && idx < userStrokes.size()) userStrokes.get(idx).processed = true;
        }
    }

    /**
     * Test de rendu pur : écrit un texte au centre, sans passer par la géométrie
     * ni le calcul. Si ça ne s'affiche pas, le problème est le DESSIN, pas la logique.
     */
    public void debugRenderTest() {
        float x = Math.max(24f, getWidth() * 0.25f);
        float y = Math.max(120f, getHeight() * 0.5f);
        PegaseItem item = addText("TEST 48", x, y, 56f);
        item.kind = KIND_CALC;
        android.util.Log.d("Bureau", "TEST @ " + x + "," + y
                + " canvas=" + getWidth() + "x" + getHeight()
                + " items=" + pegaseItems.size()
                + " color=" + Integer.toHexString(pegaseTextPaint.getColor())
                + " visible=" + (getVisibility() == VISIBLE));
    }

    /** Notes libres de Pégase : empilées sous ce qu'elle a déjà écrit. */
    public void addPegaseLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        float size = 40f;
        float y = nextFreeY();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            addText(line.trim(), 64f, y, size);
            y += size * 1.25f;
        }
        invalidate();
    }

    public void addRelativeBoxes(List<BureauBrain.BoxSpec> boxes) {
        if (boxes == null || getWidth() <= 0 || getHeight() <= 0) return;
        float cw = getWidth(), ch = getHeight();
        for (BureauBrain.BoxSpec box : boxes) {
            if (box == null) continue;
            float x = clamp01(box.rx) * cw;
            float y = clamp01(box.ry) * ch;
            float w = Math.max(80f, clamp01(box.rw) * cw);
            float h = Math.max(48f, clamp01(box.rh) * ch);
            pegaseItems.add(new PegaseItem(UUID.randomUUID().toString(), KIND_BOX,
                    box.label == null ? "" : box.label, x, y, w, h, 36f));
        }
        invalidate();
    }

    public boolean removeLastPegaseItem() {
        if (pegaseItems.isEmpty()) return false;
        pegaseItems.remove(pegaseItems.size() - 1);
        invalidate();
        return true;
    }

    public boolean removeLastCalcAnswer() {
        for (int i = pegaseItems.size() - 1; i >= 0; i--) {
            if (KIND_CALC.equals(pegaseItems.get(i).kind)) {
                pegaseItems.remove(i);
                invalidate();
                return true;
            }
        }
        return false;
    }

    /** Réouvre la dernière ligne marquée traitée pour relire / recalculer. */
    public void unmarkLastProcessedRow() {
        BureauGeometryReader.Scene scene = scene();
        for (int i = scene.rows.size() - 1; i >= 0; i--) {
            BureauGeometryReader.Row row = scene.rows.get(i);
            boolean hadProcessed = false;
            for (int idx : row.strokeIndexes) {
                if (idx >= 0 && idx < userStrokes.size() && userStrokes.get(idx).processed) {
                    hadProcessed = true;
                    break;
                }
            }
            if (!hadProcessed) continue;
            for (int idx : row.strokeIndexes) {
                if (idx >= 0 && idx < userStrokes.size()) userStrokes.get(idx).processed = false;
            }
            invalidate();
            return;
        }
    }

    public boolean removePegaseMatching(String keyword) {
        if (keyword == null || keyword.isEmpty()) return false;
        String k = fold(keyword);
        for (int i = pegaseItems.size() - 1; i >= 0; i--) {
            PegaseItem item = pegaseItems.get(i);
            String text = item.text == null ? "" : fold(item.text);
            if (text.contains(k) || fuzzyContains(text, k)) {
                pegaseItems.remove(i);
                invalidate();
                return true;
            }
        }
        return false;
    }

    private static String fold(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('ù', 'u').replace('ô', 'o');
    }

    private static boolean fuzzyContains(String text, String keyword) {
        if ("resultat".equals(keyword) || "reponse".equals(keyword) || "calcul".equals(keyword)) {
            return text.contains("=") || text.contains(keyword);
        }
        return false;
    }

    public void markRowProcessed(BureauGeometryReader.Row row) {
        if (row == null) return;
        for (int idx : row.strokeIndexes) {
            if (idx >= 0 && idx < userStrokes.size()) userStrokes.get(idx).processed = true;
        }
    }

    public void markAllProcessed() {
        for (Stroke s : userStrokes) s.processed = true;
    }

    private float nextFreeY() {
        float y = 140f;
        for (PegaseItem item : pegaseItems) {
            y = Math.max(y, itemBottom(item) + 18f);
        }
        return Math.min(y, Math.max(160f, getHeight() - 60f));
    }

    private float itemBottom(PegaseItem item) {
        if (KIND_BOX.equals(item.kind)) return item.y + (item.h > 0 ? item.h : 72f);
        int lines = item.text == null ? 1 : item.text.split("\n").length;
        return item.y + lines * item.size * 1.2f;
    }

    /** Ne renvoie JAMAIS une chaîne vide : sketchLines → detailLines → speak. */
    static String inlineAnswer(BureauCalcHelper.Result calc) {
        for (int i = calc.sketchLines.size() - 1; i >= 0; i--) {
            String l = calc.sketchLines.get(i);
            if (l != null && !l.trim().isEmpty() && !"↓".equals(l.trim())) return l.trim();
        }
        for (int i = calc.detailLines.size() - 1; i >= 0; i--) {
            String l = calc.detailLines.get(i);
            if (l != null && !l.trim().isEmpty()) return l.trim();
        }
        return calc.speak == null ? "" : calc.speak.trim();
    }

    /**
     * La fonte "cursive" du système ne possède pas toujours ×, ↓, ≈, €.
     * Un glyphe manquant = texte invisible. On remplace ce qui n'est pas rendu.
     */
    private String sanitize(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            if (hasGlyph(ch)) { sb.append(ch); continue; }
            switch (cp) {
                case 0x00D7: sb.append('x'); break;   // ×
                case 0x2193: sb.append('v'); break;   // ↓
                case 0x2248: sb.append('~'); break;   // ≈
                case 0x20AC: sb.append("EUR"); break; // €
                case 0x2192: sb.append("->"); break;  // →
                default: sb.append('?'); break;
            }
        }
        return sb.toString();
    }

    private boolean hasGlyph(String ch) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return pegaseTextPaint.hasGlyph(ch);
        }
        return true;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---------- déplacement des éléments de Pégase ----------

    /** Boîte réelle d'un élément (texte mesuré ou encadré). */
    private RectF itemBounds(PegaseItem item) {
        if (KIND_BOX.equals(item.kind)) {
            float w = item.w > 0 ? item.w : 160f;
            float h = item.h > 0 ? item.h : 72f;
            return new RectF(item.x, item.y, item.x + w, item.y + h);
        }
        pegaseTextPaint.setTextSize(item.size);
        String[] lines = item.text == null ? new String[]{""} : item.text.split("\n");
        float maxW = 0f;
        for (String l : lines) maxW = Math.max(maxW, pegaseTextPaint.measureText(l));
        float h = lines.length * item.size * 1.2f;
        // y est la BASELINE de la première ligne → la boîte remonte au-dessus
        return new RectF(item.x, item.y - item.size, item.x + maxW, item.y - item.size + h);
    }

    /** Élément touché, du plus récent au plus ancien (celui du dessus gagne). */
    private PegaseItem hitTest(float x, float y) {
        float pad = dp(10);
        for (int i = pegaseItems.size() - 1; i >= 0; i--) {
            PegaseItem item = pegaseItems.get(i);
            RectF r = itemBounds(item);
            r.inset(-pad, -pad);
            if (r.contains(x, y)) return item;
        }
        return null;
    }

    private void beginDrag(PegaseItem item, float x, float y) {
        dragItem = item;
        dragDx = x - item.x;
        dragDy = y - item.y;
        activeStroke = null;
        activePath = null;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void endDrag() {
        if (dragItem != null) {
            dragItem = null;
            if (listener != null) listener.onItemMoved();
        }
    }

    private void cancelPendingLongPress() {
        if (longPress != null) { removeCallbacks(longPress); longPress = null; }
    }

    // ---------- rendu ----------

    @Override
    protected void onDraw(Canvas canvas) {
        drawGrid(canvas);

        for (Stroke stroke : userStrokes) drawStroke(canvas, stroke);
        if (activePath != null) {
            userPaint.setColor(activeStroke != null ? activeStroke.color : penColor);
            userPaint.setStrokeWidth(activeStroke != null ? activeStroke.width : penWidth);
            userPaint.setAlpha(255);
            canvas.drawPath(activePath, userPaint);
        }

        for (PegaseItem item : pegaseItems) {
            if (item == dragItem) drawDragHalo(canvas, item);
            if (KIND_BOX.equals(item.kind)) drawBox(canvas, item);
            else if (item.text != null && !item.text.isEmpty()) drawTextBlock(canvas, item);
        }
    }

    private void drawDragHalo(Canvas canvas, PegaseItem item) {
        RectF r = itemBounds(item);
        r.inset(-dp(8), -dp(8));
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.FILL);
        halo.setColor(Color.parseColor("#22F5D78E"));
        canvas.drawRoundRect(r, dp(10), dp(10), halo);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(dp(1.5f));
        halo.setColor(Color.parseColor("#88F5D78E"));
        canvas.drawRoundRect(r, dp(10), dp(10), halo);
    }

    private void drawGrid(Canvas canvas) {
        float step = 56f * getResources().getDisplayMetrics().density / 2f;
        for (float y = step; y < getHeight(); y += step) {
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        if (stroke == null || stroke.points.size() < 2) return;
        userPaint.setColor(stroke.color);
        userPaint.setStrokeWidth(stroke.width);
        userPaint.setAlpha(255);   // l'encre traitée ne pâlit plus : c'est TON calque
        canvas.drawPath(smoothPath(stroke.points), userPaint);
    }

    /** Lissage quadratique : plus de segments anguleux. */
    private static Path smoothPath(List<Point> pts) {
        Path path = new Path();
        if (pts.isEmpty()) return path;
        Point p0 = pts.get(0);
        path.moveTo(p0.x, p0.y);
        if (pts.size() == 1) {
            path.lineTo(p0.x + 0.5f, p0.y + 0.5f);
            return path;
        }
        for (int i = 1; i < pts.size() - 1; i++) {
            Point a = pts.get(i);
            Point b = pts.get(i + 1);
            path.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f);
        }
        Point last = pts.get(pts.size() - 1);
        path.lineTo(last.x, last.y);
        return path;
    }

    private void drawTextBlock(Canvas canvas, PegaseItem item) {
        pegaseTextPaint.setTextSize(item.size);
        float y = item.y;
        for (String line : item.text.split("\n")) {
            canvas.drawText(line, item.x, y, pegaseTextPaint);
            y += item.size * 1.2f;
        }
    }

    private void drawBox(Canvas canvas, PegaseItem item) {
        float w = item.w > 0 ? item.w : 160f;
        float h = item.h > 0 ? item.h : 72f;
        RectF rect = new RectF(item.x, item.y, item.x + w, item.y + h);
        canvas.drawRoundRect(rect, 12f, 12f, pegaseBoxPaint);
        if (item.text != null && !item.text.isEmpty()) {
            pegaseTextPaint.setTextSize(item.size);
            canvas.drawText(item.text, item.x + 12f, item.y - 10f, pegaseTextPaint);
        }
    }

    // ---------- saisie ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int toolType = event.getToolType(index);

        // Rejet de paume : dès qu'un stylet est utilisé, le doigt ne dessine plus.
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) stylusSeen = true;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (stylusSeen && toolType == MotionEvent.TOOL_TYPE_FINGER) return false;
            if (toolType == MotionEvent.TOOL_TYPE_ERASER) setEraserMode(true);
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                activePointerId = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                downX = x; downY = y;
                getParent().requestDisallowInterceptTouchEvent(true);

                if (moveMode) {                       // outil ✋ : on attrape directement
                    PegaseItem hit = hitTest(x, y);
                    if (hit != null) beginDrag(hit, x, y);
                    return true;
                }
                if (eraserMode) {
                    eraseAt(x, y);
                    return true;
                }
                // mode crayon : appui long sur un élément = déplacement, sans changer d'outil
                PegaseItem hit = hitTest(x, y);
                if (hit != null) {
                    longPress = () -> beginDrag(hit, downX, downY);
                    postDelayed(longPress, 400);
                }
                startStroke(x, y);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int p = event.findPointerIndex(activePointerId);
                if (p < 0) return true;

                if (dragItem != null) {
                    dragItem.x = event.getX(p) - dragDx;
                    dragItem.y = event.getY(p) - dragDy;
                    invalidate();
                    return true;
                }
                if (longPress != null
                        && Math.hypot(event.getX(p) - downX, event.getY(p) - downY) > TOUCH_SLOP) {
                    cancelPendingLongPress();   // la main bouge → c'est un trait, pas un déplacement
                }
                if (moveMode) return true;
                if (eraserMode) {
                    eraseAt(event.getX(p), event.getY(p));
                    invalidate();
                    return true;
                }
                if (activeStroke == null) return true;
                // points historiques : trait fidèle même si la main va vite
                for (int h = 0; h < event.getHistorySize(); h++) {
                    extendStroke(event.getHistoricalX(p, h), event.getHistoricalY(p, h));
                }
                extendStroke(event.getX(p), event.getY(p));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancelPendingLongPress();
                if (dragItem != null) {
                    endDrag();
                    activeStroke = null;
                    activePath = null;
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    invalidate();
                    return true;
                }
                if (activeStroke != null && action == MotionEvent.ACTION_UP) {
                    extendStroke(event.getX(), event.getY());
                    if (activeStroke.points.size() >= 2) {
                        userStrokes.add(activeStroke);
                        redoStack.clear();
                        if (listener != null) listener.onStrokeFinished();
                    }
                }
                activeStroke = null;
                activePath = null;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private void startStroke(float x, float y) {
        activeStroke = new Stroke();
        activeStroke.color = penColor;
        activeStroke.width = penWidth;
        activeStroke.points.add(new Point(x, y));
        activePath = new Path();
        activePath.moveTo(x, y);
        lastX = x; lastY = y;
    }

    private void extendStroke(float x, float y) {
        if (activeStroke == null) return;
        // on ignore les micro-déplacements : moins de points, moins de bruit pour ML Kit
        if (Math.hypot(x - lastX, y - lastY) < 2.0) return;
        activeStroke.points.add(new Point(x, y));
        if (activePath != null) {
            activePath.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f);
        }
        lastX = x; lastY = y;
    }

    /** Gomme : supprime le trait touché en entier (gomme "à l'objet", pas au pixel). */
    private void eraseAt(float x, float y) {
        float radius = 22f * getResources().getDisplayMetrics().density / 2f;
        for (int i = userStrokes.size() - 1; i >= 0; i--) {
            for (Point p : userStrokes.get(i).points) {
                if (Math.hypot(p.x - x, p.y - y) <= radius) {
                    userStrokes.remove(i);
                    if (listener != null) listener.onStrokeFinished();
                    return;
                }
            }
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
