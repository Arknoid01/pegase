package com.pegasuscorp.orbe.bureau;

import com.google.mlkit.vision.digitalink.Ink;
import com.pegasuscorp.orbe.DigitalInkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lit la feuille : géométrie (vecteurs) + encre (ML Kit, groupée PAR LIGNE).
 *
 * Avant : une reconnaissance par trait → "12 x 4 =" arrivait en bouillie.
 * Maintenant : tous les traits d'une même ligne sont envoyés dans un seul Ink,
 * ce pour quoi ML Kit est fait.
 */
public final class BureauSheetReader {

    public static final class SheetContext {
        public final int strokeCount;
        public final int pendingStrokeCount;
        public final String handwriting;     // texte lu (lignes séparées par \n)
        public final String pegaseSummary;
        public final String geometry;        // description vectorielle pour le LLM
        public final String shapesSummary;   // résumé court pour la voix
        public final BureauGeometryReader.Scene scene;

        public SheetContext(int strokeCount, int pendingStrokeCount,
                            String handwriting, String pegaseSummary) {
            this(strokeCount, pendingStrokeCount, handwriting, pegaseSummary,
                    "", "feuille vide", null);
        }

        public SheetContext(int strokeCount, int pendingStrokeCount,
                            String handwriting, String pegaseSummary,
                            String geometry, String shapesSummary,
                            BureauGeometryReader.Scene scene) {
            this.strokeCount = strokeCount;
            this.pendingStrokeCount = pendingStrokeCount;
            this.handwriting = handwriting == null ? "" : handwriting;
            this.pegaseSummary = pegaseSummary == null ? "" : pegaseSummary;
            this.geometry = geometry == null ? "" : geometry;
            this.shapesSummary = shapesSummary == null ? "" : shapesSummary;
            this.scene = scene;
        }

        public boolean hasContent() {
            return strokeCount > 0 || !handwriting.isEmpty() || !pegaseSummary.isEmpty();
        }

        /** Texte de la dernière ligne non traitée — celle que tu viens d'écrire. */
        public String lastPendingText() {
            if (scene == null) return "";
            BureauGeometryReader.Row row = scene.lastPendingRow();
            return row == null ? "" : row.text;
        }
    }

    private BureauSheetReader() {}

    public static SheetContext read(BureauCanvasView.Snapshot snapshot) {
        if (snapshot == null || snapshot.userStrokes == null) {
            return new SheetContext(0, 0, "", "");
        }

        BureauGeometryReader.Scene scene = BureauGeometryReader.read(
                snapshot.userStrokes,
                snapshot.canvasW > 0 ? snapshot.canvasW : 1080f,
                snapshot.canvasH > 0 ? snapshot.canvasH : 1920f);

        // Reco d'encre : une passe par ligne, uniquement les lignes non traitées.
        DigitalInkManager mgr = DigitalInkManager.getInstance();
        boolean inkReady = mgr != null && mgr.isReady();
        StringBuilder text = new StringBuilder();

        if (inkReady) {
            for (BureauGeometryReader.Row row : scene.rows) {
                if (row.allProcessed) continue;
                List<BureauCanvasView.Stroke> rowStrokes = new ArrayList<>();
                for (int idx : row.strokeIndexes) {
                    if (idx >= 0 && idx < snapshot.userStrokes.size()) {
                        rowStrokes.add(snapshot.userStrokes.get(idx));
                    }
                }
                String line = recognize(mgr, buildInk(rowStrokes));
                line = InkFixups.clean(line);
                row.text = line;
                if (!line.isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(line);
                }
            }
        }

        String pegase = summarizePegase(snapshot.pegaseItems);
        return new SheetContext(
                scene.strokeCount,
                scene.pendingCount,
                text.toString(),
                pegase,
                scene.describe(),
                scene.summary(),
                scene);
    }

    /**
     * Lecture ciblée : uniquement l'encre contenue dans la zone que tu as encadrée.
     * Le cadre lui-même est exclu (c'est une forme, pas de l'écriture).
     */
    public static String readZone(BureauCanvasView.Snapshot snapshot,
                                  BureauGeometryReader.Scene scene,
                                  BureauGeometryReader.Box zone) {
        if (snapshot == null || scene == null || zone == null) return "";
        DigitalInkManager mgr = DigitalInkManager.getInstance();
        if (mgr == null || !mgr.isReady()) return "";

        StringBuilder text = new StringBuilder();
        for (BureauGeometryReader.Row row : scene.rowsInside(zone)) {
            List<BureauCanvasView.Stroke> rowStrokes = new ArrayList<>();
            for (int idx : row.strokeIndexes) {
                if (idx >= 0 && idx < snapshot.userStrokes.size()) {
                    rowStrokes.add(snapshot.userStrokes.get(idx));
                }
            }
            String line = InkFixups.clean(recognize(mgr, buildInk(rowStrokes)));
            row.text = line;
            if (!line.isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(line);
            }
        }
        return text.toString();
    }

    /** Tous les traits d'une ligne dans UN seul Ink : c'est ainsi que ML Kit fonctionne bien. */
    private static Ink buildInk(List<BureauCanvasView.Stroke> strokes) {
        Ink.Builder builder = Ink.builder();
        long t = 0L;
        for (BureauCanvasView.Stroke stroke : strokes) {
            if (stroke.points.size() < 2) continue;
            Ink.Stroke.Builder sb = Ink.Stroke.builder();
            for (BureauCanvasView.Point p : stroke.points) {
                sb.addPoint(Ink.Point.create(p.x, p.y, t));
                t += 8L;
            }
            builder.addStroke(sb.build());
            t += 120L;   // petite pause entre les traits, ML Kit s'en sert
        }
        return builder.build();
    }

    private static String recognize(DigitalInkManager mgr, Ink ink) {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {""};
        mgr.recognize(ink, new DigitalInkManager.RecognitionListener() {
            @Override
            public void onRecognized(String result) {
                out[0] = result == null ? "" : result.trim();
                latch.countDown();
            }

            @Override
            public void onRecognitionFailed(String reason) {
                latch.countDown();
            }
        });
        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private static String summarizePegase(List<BureauCanvasView.PegaseItem> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (BureauCanvasView.PegaseItem item : items) {
            if (item.text == null || item.text.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(item.text.replace('\n', ' ').trim());
            if (sb.length() > 200) break;
        }
        return sb.toString();
    }

    /**
     * ML Kit est un modèle de TEXTE, pas de maths. Il confond régulièrement
     * l/1, O/0, x/×, - et =. On rattrape ça avant le parsing.
     */
    static final class InkFixups {
        private InkFixups() {}

        static String clean(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.isEmpty()) return "";

            s = s.replace('×', 'x').replace('·', 'x').replace('*', 'x');
            s = s.replace('—', '-').replace('–', '-');
            s = s.replace(',', '.');

            // contexte numérique : confusions fréquentes ML Kit
            StringBuilder sb = new StringBuilder();
            char[] c = s.toCharArray();
            for (int i = 0; i < c.length; i++) {
                char ch = c[i];
                boolean numericCtx = nearDigit(c, i);
                if (numericCtx) {
                    if (ch == 'l' || ch == 'I' || ch == '|' || ch == '!') ch = '1';
                    else if (ch == 'O' || ch == 'o' || ch == 'Q') ch = '0';
                    else if (ch == 'S' || ch == 's') ch = '5';
                    else if (ch == 'B') ch = '8';
                    else if (ch == 'Z' || ch == 'z') ch = '2';
                    else if (ch == 'g') ch = '9';
                }
                sb.append(ch);
            }
            s = sb.toString();

            // "12 x 4 =" → garde le signal =
            s = s.replaceAll("\\s*=\\s*$", "=");
            s = s.replaceAll("(?<=\\d)\\s+(?=\\d)", "");  // "5 0" collé en "50" si collé
            s = s.replaceAll("\\s+", " ").trim();
            return s;
        }

        private static boolean nearDigit(char[] c, int i) {
            for (int d = 1; d <= 2; d++) {
                if (i - d >= 0 && Character.isDigit(c[i - d])) return true;
                if (i + d < c.length && Character.isDigit(c[i + d])) return true;
            }
            return false;
        }
    }
}
