package com.pegasuscorp.orbe.bureau;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lecture géométrique de la feuille : on n'a pas besoin de vision, on a les vecteurs.
 *
 * Classe chaque trait (écriture / ligne / flèche / rectangle / cercle / gribouillis),
 * regroupe l'écriture en lignes, détecte les flèches qui relient deux zones,
 * et produit une description textuelle avec coordonnées EXACTES pour le LLM.
 */
public final class BureauGeometryReader {

    // ---------- modèle ----------

    public enum Kind { INK, LINE, ARROW, RECT, ELLIPSE, SCRIBBLE }

    public static final class Box {
        public float left, top, right, bottom;

        public Box(float l, float t, float r, float b) {
            left = l; top = t; right = r; bottom = b;
        }

        public float width()  { return right - left; }
        public float height() { return bottom - top; }
        public float cx()     { return (left + right) / 2f; }
        public float cy()     { return (top + bottom) / 2f; }
        public float diag()   { return (float) Math.hypot(width(), height()); }

        public void union(Box o) {
            left = Math.min(left, o.left);
            top = Math.min(top, o.top);
            right = Math.max(right, o.right);
            bottom = Math.max(bottom, o.bottom);
        }

        /** Recouvrement vertical relatif avec une autre box (0..1). */
        public float yOverlap(Box o) {
            float inter = Math.min(bottom, o.bottom) - Math.max(top, o.top);
            if (inter <= 0) return 0f;
            float minH = Math.max(1f, Math.min(height(), o.height()));
            return inter / minH;
        }

        public boolean contains(float x, float y, float margin) {
            return x >= left - margin && x <= right + margin
                    && y >= top - margin && y <= bottom + margin;
        }
    }

    /** Un objet reconnu sur la feuille (peut regrouper plusieurs traits, ex. une flèche). */
    public static final class Shape {
        public final Kind kind;
        public final Box box;
        public final List<Integer> strokeIndexes = new ArrayList<>();
        public float ax, ay, bx, by;      // extrémités (flèches / lignes)
        public int id;
        public String connectFrom;        // rempli pour les flèches
        public String connectTo;

        Shape(Kind kind, Box box) {
            this.kind = kind;
            this.box = box;
        }

        public String label() {
            switch (kind) {
                case LINE:     return "ligne #" + id;
                case ARROW:    return "flèche #" + id;
                case RECT:     return "encadré #" + id;
                case ELLIPSE:  return "cercle #" + id;
                case SCRIBBLE: return "gribouillis #" + id;
                default:       return "écriture #" + id;
            }
        }
    }

    /** Une ligne d'écriture manuscrite : les traits qui se recouvrent verticalement. */
    public static final class Row {
        public final List<Integer> strokeIndexes = new ArrayList<>();
        public Box box;
        public boolean allProcessed = true;
        public int id;
        public String text = "";          // rempli plus tard par ML Kit
    }

    public static final class Scene {
        public final List<Shape> shapes = new ArrayList<>();
        public final List<Row> rows = new ArrayList<>();
        /** Boîte englobante de CHAQUE trait, indexée comme userStrokes. */
        public final List<Box> strokeBoxes = new ArrayList<>();
        public int strokeCount;
        public int pendingCount;
        public float canvasW = 1f;
        public float canvasH = 1f;

        public boolean isEmpty() {
            return strokeCount == 0;
        }

        /** Le dernier rectangle dessiné = la zone que tu désignes. */
        public Shape lastZone() {
            for (int i = shapes.size() - 1; i >= 0; i--) {
                if (shapes.get(i).kind == Kind.RECT) return shapes.get(i);
            }
            return null;
        }

        /** Index des traits d'écriture contenus dans une zone (le cadre lui-même exclu). */
        public List<Integer> inkInside(Box zone) {
            List<Integer> out = new ArrayList<>();
            if (zone == null) return out;
            for (Row r : rows) {
                for (int idx : r.strokeIndexes) {
                    if (idx < 0 || idx >= strokeBoxes.size()) continue;
                    Box b = strokeBoxes.get(idx);
                    if (zone.contains(b.cx(), b.cy(), 0f)) out.add(idx);
                }
            }
            return out;
        }

        /** Lignes d'écriture entièrement contenues dans une zone. */
        public List<Row> rowsInside(Box zone) {
            List<Row> out = new ArrayList<>();
            if (zone == null) return out;
            for (Row r : rows) {
                if (zone.contains(r.box.cx(), r.box.cy(), 0f)) out.add(r);
            }
            return out;
        }

        /** Dernière ligne d'écriture non encore traitée (celle où poser la réponse). */
        public Row lastPendingRow() {
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (!rows.get(i).allProcessed) return rows.get(i);
            }
            return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        }

        /**
         * Description textuelle envoyée au LLM. Coordonnées relatives 0..1,
         * origine en haut à gauche. C'est notre "vision" — exacte, pas devinée.
         */
        public String describe() {
            if (isEmpty()) return "(feuille vide)";
            StringBuilder sb = new StringBuilder();
            for (Row r : rows) {
                sb.append("- écriture #").append(r.id).append(' ')
                        .append(rect(r.box))
                        .append(" · ").append(r.strokeIndexes.size()).append(" traits");
                if (!r.text.isEmpty()) sb.append(" · lu : \"").append(r.text).append('"');
                if (r.allProcessed) sb.append(" · (déjà traitée)");
                sb.append('\n');
            }
            for (Shape s : shapes) {
                if (s.kind == Kind.INK) continue;
                sb.append("- ").append(s.label()).append(' ').append(rect(s.box));
                if (s.kind == Kind.ARROW || s.kind == Kind.LINE) {
                    sb.append(" · de ").append(pt(s.ax, s.ay))
                            .append(" vers ").append(pt(s.bx, s.by));
                    if (s.connectFrom != null && s.connectTo != null) {
                        sb.append(" · relie ").append(s.connectFrom)
                                .append(" → ").append(s.connectTo);
                    }
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        }

        /** Résumé court pour la voix / les logs. */
        public String summary() {
            if (isEmpty()) return "feuille vide";
            int arrows = 0, rects = 0, circles = 0, lines = 0;
            for (Shape s : shapes) {
                switch (s.kind) {
                    case ARROW: arrows++; break;
                    case RECT: rects++; break;
                    case ELLIPSE: circles++; break;
                    case LINE: lines++; break;
                    default: break;
                }
            }
            List<String> parts = new ArrayList<>();
            if (!rows.isEmpty()) parts.add(rows.size() + (rows.size() > 1 ? " lignes d'écriture" : " ligne d'écriture"));
            if (rects > 0) parts.add(rects + (rects > 1 ? " encadrés" : " encadré"));
            if (circles > 0) parts.add(circles + (circles > 1 ? " cercles" : " cercle"));
            if (arrows > 0) parts.add(arrows + (arrows > 1 ? " flèches" : " flèche"));
            if (lines > 0) parts.add(lines + (lines > 1 ? " traits" : " trait"));
            return parts.isEmpty() ? "quelques traits" : String.join(", ", parts);
        }

        private String rect(Box b) {
            return String.format(Locale.US, "[%.2f,%.2f → %.2f,%.2f]",
                    b.left / canvasW, b.top / canvasH, b.right / canvasW, b.bottom / canvasH);
        }

        private String pt(float x, float y) {
            return String.format(Locale.US, "(%.2f,%.2f)", x / canvasW, y / canvasH);
        }
    }

    private BureauGeometryReader() {}

    // ---------- lecture ----------

    public static Scene read(List<BureauCanvasView.Stroke> strokes, float canvasW, float canvasH) {
        Scene scene = new Scene();
        scene.canvasW = Math.max(1f, canvasW);
        scene.canvasH = Math.max(1f, canvasH);
        if (strokes == null || strokes.isEmpty()) return scene;

        scene.strokeCount = strokes.size();
        for (BureauCanvasView.Stroke s : strokes) {
            if (!s.processed) scene.pendingCount++;
        }

        // Taille de référence : médiane des diagonales, sert à distinguer écriture et schéma.
        List<Float> diags = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        for (BureauCanvasView.Stroke s : strokes) {
            Box b = boxOf(s);
            boxes.add(b);
            diags.add(b.diag());
        }
        List<Float> sorted = new ArrayList<>(diags);
        Collections.sort(sorted);
        float medianDiag = medianOf(sorted);
        // Seuil 100 % relatif à l'écriture : pas de plancher canvas (sinon sur tablette
        // un "1" de 200 px est classé LINE et sort du calque d'encre).
        float bigThreshold = medianDiag * 2.6f;

        boolean[] used = new boolean[strokes.size()];
        int nextId = 1;

        // 1. Formes géométriques (grands traits)
        List<Shape> geo = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            BureauCanvasView.Stroke s = strokes.get(i);
            Box b = boxes.get(i);
            if (b.diag() < bigThreshold) continue;

            Kind kind = classify(s, b, medianDiag);
            if (kind == Kind.INK) continue;

            Shape shape = new Shape(kind, b);
            shape.strokeIndexes.add(i);
            BureauCanvasView.Point p0 = s.points.get(0);
            BureauCanvasView.Point p1 = s.points.get(s.points.size() - 1);
            shape.ax = p0.x; shape.ay = p0.y;
            shape.bx = p1.x; shape.by = p1.y;
            geo.add(shape);
            used[i] = true;
        }

        // 2. Une ligne + 1..2 petits traits à son extrémité = flèche
        for (Shape shape : geo) {
            if (shape.kind != Kind.LINE) continue;
            List<Integer> barbs = findBarbs(strokes, boxes, used, shape, medianDiag);
            if (barbs.size() >= 1) {
                Shape arrow = new Shape(Kind.ARROW, shape.box);
                arrow.strokeIndexes.addAll(shape.strokeIndexes);
                arrow.ax = shape.ax; arrow.ay = shape.ay;
                arrow.bx = shape.bx; arrow.by = shape.by;
                // la pointe est du côté des barbules
                float dStart = 0, dEnd = 0;
                for (int bi : barbs) {
                    Box bb = boxes.get(bi);
                    dStart += dist(bb.cx(), bb.cy(), shape.ax, shape.ay);
                    dEnd += dist(bb.cx(), bb.cy(), shape.bx, shape.by);
                    arrow.strokeIndexes.add(bi);
                    arrow.box.union(bb);
                    used[bi] = true;
                }
                if (dStart < dEnd) { // pointe au début → on inverse pour que b = la pointe
                    float tx = arrow.ax, ty = arrow.ay;
                    arrow.ax = arrow.bx; arrow.ay = arrow.by;
                    arrow.bx = tx; arrow.by = ty;
                }
                shape.strokeIndexes.clear();
                geoReplace(geo, shape, arrow);
            }
        }
        geo.removeIf(s -> s.strokeIndexes.isEmpty());

        // 3. Le reste = écriture, regroupée en lignes
        List<Integer> inkIdx = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            if (!used[i]) inkIdx.add(i);
        }
        List<Row> rows = clusterRows(strokes, boxes, inkIdx);

        // 4. Numérotation + connexions des flèches
        for (Row r : rows) r.id = nextId++;
        for (Shape s : geo) s.id = nextId++;
        for (Shape s : geo) {
            if (s.kind != Kind.ARROW && s.kind != Kind.LINE) continue;
            s.connectFrom = whatIsAt(s.ax, s.ay, rows, geo, s, medianDiag * 1.5f);
            s.connectTo = whatIsAt(s.bx, s.by, rows, geo, s, medianDiag * 1.5f);
        }

        scene.rows.addAll(rows);
        scene.shapes.addAll(geo);
        scene.strokeBoxes.addAll(boxes);
        return scene;
    }

    private static void geoReplace(List<Shape> geo, Shape old, Shape fresh) {
        int i = geo.indexOf(old);
        if (i >= 0) geo.set(i, fresh);
    }

    // ---------- classification d'un trait ----------

    private static Kind classify(BureauCanvasView.Stroke s, Box b, float medianDiag) {
        if (s.points.size() < 3) return Kind.INK;

        float pathLen = pathLength(s);
        if (pathLen < 1f) return Kind.INK;

        BureauCanvasView.Point p0 = s.points.get(0);
        BureauCanvasView.Point pN = s.points.get(s.points.size() - 1);
        float endGap = dist(p0.x, p0.y, pN.x, pN.y);
        float closedMinDiag = Math.max(medianDiag * 0.45f, 8f);
        boolean closed = endGap < pathLen * 0.22f && b.diag() > closedMinDiag;

        if (!closed) {
            float straightness = endGap / pathLen;
            if (straightness > 0.90f) {
                // Chiffre "1" manuscrit : trait fin et haut, pas une ligne de schéma.
                float aspect = b.height() / Math.max(4f, b.width());
                if (aspect > 2f) return Kind.INK;
                return Kind.LINE;
            }
            if (pathLen > b.diag() * 3.2f) return Kind.SCRIBBLE;
            return Kind.INK;
        }

        // Fermé : rectangle ou cercle ? Les "0" / "8" restent encre si proches de la taille habituelle.
        if (b.diag() < medianDiag * 3.2f) return Kind.INK;

        float rectPerim = 2f * (b.width() + b.height());
        float ellipsePerim = (float) (Math.PI * (b.width() + b.height()) / 2f);
        float toRect = Math.abs(pathLen - rectPerim);
        float toEllipse = Math.abs(pathLen - ellipsePerim);
        int corners = countCorners(s, medianDiag);
        if (corners >= 3 || toRect < toEllipse) return Kind.RECT;
        return Kind.ELLIPSE;
    }

    /** Compte les changements de direction brutaux (> 60°) — signature d'un rectangle. */
    private static int countCorners(BureauCanvasView.Stroke s, float medianDiag) {
        List<BureauCanvasView.Point> pts = simplify(s.points, Math.max(8f, medianDiag * 0.06f));
        if (pts.size() < 4) return 0;
        int corners = 0;
        for (int i = 1; i < pts.size() - 1; i++) {
            float a1 = angle(pts.get(i - 1), pts.get(i));
            float a2 = angle(pts.get(i), pts.get(i + 1));
            float diff = Math.abs(normalizeAngle(a2 - a1));
            if (diff > 60f && diff < 150f) corners++;
        }
        return corners;
    }

    /** Petits traits proches d'une extrémité de ligne, obliques : les barbules d'une flèche. */
    private static List<Integer> findBarbs(List<BureauCanvasView.Stroke> strokes, List<Box> boxes,
                                           boolean[] used, Shape line, float medianDiag) {
        List<Integer> barbs = new ArrayList<>();
        float maxBarbLen = Math.max(medianDiag * 1.6f, line.box.diag() * 0.35f);
        float radius = maxBarbLen * 1.2f;
        float lineAngle = angleOf(line.ax, line.ay, line.bx, line.by);

        for (int i = 0; i < strokes.size(); i++) {
            if (used[i]) continue;
            BureauCanvasView.Stroke s = strokes.get(i);
            Box b = boxes.get(i);
            if (b.diag() > maxBarbLen || s.points.size() < 2) continue;

            BureauCanvasView.Point p0 = s.points.get(0);
            BureauCanvasView.Point pN = s.points.get(s.points.size() - 1);
            float nearStart = Math.min(dist(p0.x, p0.y, line.ax, line.ay),
                    dist(pN.x, pN.y, line.ax, line.ay));
            float nearEnd = Math.min(dist(p0.x, p0.y, line.bx, line.by),
                    dist(pN.x, pN.y, line.bx, line.by));
            if (Math.min(nearStart, nearEnd) > radius) continue;

            float a = angleOf(p0.x, p0.y, pN.x, pN.y);
            float diff = Math.abs(normalizeAngle(a - lineAngle));
            if (diff > 100f) diff = 180f - diff;
            if (diff > 15f && diff < 75f) barbs.add(i);
            if (barbs.size() == 2) break;
        }
        return barbs;
    }

    /** Regroupe les traits d'écriture en lignes (recouvrement vertical). */
    private static List<Row> clusterRows(List<BureauCanvasView.Stroke> strokes,
                                         List<Box> boxes, List<Integer> inkIdx) {
        List<Row> rows = new ArrayList<>();
        if (inkIdx.isEmpty()) return rows;

        // tri par Y du centre
        inkIdx.sort((a, b) -> Float.compare(boxes.get(a).cy(), boxes.get(b).cy()));

        for (int idx : inkIdx) {
            Box b = boxes.get(idx);
            Row target = null;
            for (Row r : rows) {
                // même ligne si les boîtes se recouvrent verticalement de plus de 35 %
                if (r.box.yOverlap(b) > 0.35f) { target = r; break; }
            }
            if (target == null) {
                target = new Row();
                target.box = new Box(b.left, b.top, b.right, b.bottom);
                rows.add(target);
            } else {
                target.box.union(b);
            }
            target.strokeIndexes.add(idx);
            if (!strokes.get(idx).processed) target.allProcessed = false;
        }

        // ordre de lecture : haut → bas, puis traits ordonnés gauche → droite
        rows.sort((r1, r2) -> Float.compare(r1.box.top, r2.box.top));
        for (Row r : rows) {
            r.strokeIndexes.sort((a, b) -> Float.compare(boxes.get(a).left, boxes.get(b).left));
        }
        return rows;
    }

    /** Que touche ce point ? Sert à dire « la flèche relie X à Y ». */
    private static String whatIsAt(float x, float y, List<Row> rows, List<Shape> shapes,
                                   Shape self, float margin) {
        for (Shape s : shapes) {
            if (s == self) continue;
            if (s.box.contains(x, y, margin)) return s.label();
        }
        for (Row r : rows) {
            if (r.box.contains(x, y, margin)) {
                return r.text.isEmpty() ? ("écriture #" + r.id)
                        : ("écriture #" + r.id + " (\"" + r.text + "\")");
            }
        }
        return null;
    }

    // ---------- utilitaires ----------

    public static Box boxOf(BureauCanvasView.Stroke s) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE;
        float r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (BureauCanvasView.Point p : s.points) {
            l = Math.min(l, p.x); t = Math.min(t, p.y);
            r = Math.max(r, p.x); b = Math.max(b, p.y);
        }
        if (l > r) return new Box(0, 0, 0, 0);
        return new Box(l, t, r, b);
    }

    private static float pathLength(BureauCanvasView.Stroke s) {
        float len = 0;
        for (int i = 1; i < s.points.size(); i++) {
            BureauCanvasView.Point a = s.points.get(i - 1);
            BureauCanvasView.Point b = s.points.get(i);
            len += dist(a.x, a.y, b.x, b.y);
        }
        return len;
    }

    /** Réduction du nombre de points (distance minimale) avant analyse d'angles. */
    private static List<BureauCanvasView.Point> simplify(List<BureauCanvasView.Point> pts, float minDist) {
        List<BureauCanvasView.Point> out = new ArrayList<>();
        if (pts.isEmpty()) return out;
        out.add(pts.get(0));
        for (BureauCanvasView.Point p : pts) {
            BureauCanvasView.Point last = out.get(out.size() - 1);
            if (dist(last.x, last.y, p.x, p.y) >= minDist) out.add(p);
        }
        return out;
    }

    private static float angle(BureauCanvasView.Point a, BureauCanvasView.Point b) {
        return angleOf(a.x, a.y, b.x, b.y);
    }

    private static float angleOf(float ax, float ay, float bx, float by) {
        return (float) Math.toDegrees(Math.atan2(by - ay, bx - ax));
    }

    private static float normalizeAngle(float deg) {
        while (deg > 180f) deg -= 360f;
        while (deg < -180f) deg += 360f;
        return deg;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(bx - ax, by - ay);
    }

    private static float medianOf(List<Float> sorted) {
        if (sorted == null || sorted.isEmpty()) return 0f;
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2f;
    }
}
