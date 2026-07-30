package com.pegasuscorp.orbe.bureau;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Analyse 100 % locale — aucun réseau. S'appuie sur la géométrie vectorielle,
 * qui suffit déjà à décrire la feuille de façon utile.
 */
public final class BureauLocalAnalyzer {

    private BureauLocalAnalyzer() {}

    public static BureauBrain.Result analyze(BureauSheetReader.SheetContext sheet,
                                             String userPhrase, String sceneHint,
                                             Set<String> processed) {
        String phrase = userPhrase == null ? "" : userPhrase.trim();
        String fold = phrase.toLowerCase(Locale.ROOT);
        String ink = sheet == null ? "" : sheet.handwriting;
        int strokes = sheet == null ? 0 : sheet.strokeCount;

        BureauCalcHelper.Result calc = BureauCalcHelper.trySolve(phrase, processed, false);
        if (calc == null && sheet != null) {
            String pending = sheet.lastPendingText();
            if (!pending.isEmpty()) {
                calc = BureauCalcHelper.trySolve(pending, processed, false);
            }
        }
        if (calc == null && !ink.isEmpty() && wantsCalcFromInk(fold)) {
            calc = BureauCalcHelper.trySolve(ink, processed, false);
        }
        if (calc == null && BureauCorrectionHelper.parse(null, phrase).type
                != BureauCorrectionHelper.Type.NONE) {
            if (!ink.isEmpty()) {
                calc = BureauCalcHelper.trySolve(ink, processed, true);
            }
        }
        if (calc != null) {
            return new BureauBrain.Result(calc.speak, calc.allDisplayLines(),
                    new ArrayList<>(), calc);
        }

        List<String> lines = new ArrayList<>();
        List<BureauBrain.BoxSpec> boxes = new ArrayList<>();
        String speak;

        boolean structure = fold.contains("structure") || fold.contains("liste")
                || fold.contains("résume") || fold.contains("resume")
                || fold.contains("clarifie") || fold.contains("organise");

        BureauGeometryReader.Scene scene = sheet == null ? null : sheet.scene;

        if (strokes == 0) {
            speak = "Feuille vide. Griffonne, ou donne-moi un calcul à voix haute.";
            lines.add("→ prêt pour tes notes");
            return new BureauBrain.Result(speak, lines, boxes);
        }

        if (!ink.isEmpty()) {
            if (structure) {
                speak = "Voici ce que je déchiffre.";
                for (String chunk : splitIdeas(ink)) lines.add("• " + chunk);
                if (scene != null && !scene.rows.isEmpty()) {
                    boxes.add(boxAroundRows(scene));
                }
            } else {
                speak = "Je lis : " + truncate(ink, 80);
                lines.add(truncate(ink, 60));
            }
        } else if (scene != null && !scene.shapes.isEmpty()) {
            // pas de texte lisible, mais on comprend le schéma grâce aux vecteurs
            speak = "Je vois " + scene.summary() + ".";
            for (BureauGeometryReader.Shape s : scene.shapes) {
                if (s.kind == BureauGeometryReader.Kind.ARROW
                        && s.connectFrom != null && s.connectTo != null) {
                    lines.add(s.connectFrom + " → " + s.connectTo);
                }
                if (lines.size() >= 3) break;
            }
            if (lines.isEmpty()) lines.add("(" + scene.summary() + ")");
        } else {
            speak = "Je vois du griffonnage sans texte lisible. Dicte-moi ce que c'est.";
            lines.add("(dessin — dis-moi ce que c'est)");
        }

        if (sceneHint != null && !sceneHint.isEmpty() && structure) {
            lines.add("Suite : " + truncate(sceneHint, 50));
        }

        return new BureauBrain.Result(speak, lines, boxes);
    }

    /** Encadré calé sur les vraies coordonnées des lignes — pas une valeur inventée. */
    private static BureauBrain.BoxSpec boxAroundRows(BureauGeometryReader.Scene scene) {
        BureauGeometryReader.Box b = null;
        for (BureauGeometryReader.Row r : scene.rows) {
            if (b == null) b = new BureauGeometryReader.Box(
                    r.box.left, r.box.top, r.box.right, r.box.bottom);
            else b.union(r.box);
        }
        if (b == null) return new BureauBrain.BoxSpec("Idées", 0.07f, 0.12f, 0.42f, 0.14f);
        float pad = 12f;
        return new BureauBrain.BoxSpec("Idées",
                Math.max(0f, (b.left - pad) / scene.canvasW),
                Math.max(0f, (b.top - pad) / scene.canvasH),
                Math.min(1f, (b.width() + pad * 2) / scene.canvasW),
                Math.min(1f, (b.height() + pad * 2) / scene.canvasH));
    }

    private static boolean wantsCalcFromInk(String fold) {
        return fold.contains("calcule") || fold.contains("calcul")
                || fold.contains("combien") || fold.contains("marge");
    }

    private static List<String> splitIdeas(String ink) {
        List<String> out = new ArrayList<>();
        for (String part : ink.split("[\\n;,/]+")) {
            String t = part.trim();
            if (t.length() < 2) continue;
            out.add(t);
            if (out.size() >= 5) break;
        }
        if (out.isEmpty()) {
            for (String word : ink.split("\\s+")) {
                if (word.length() < 3) continue;
                out.add(word);
                if (out.size() >= 4) break;
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
