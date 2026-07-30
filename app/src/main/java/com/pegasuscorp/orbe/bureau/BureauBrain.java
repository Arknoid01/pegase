package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyse le bureau SANS vision : la géométrie vectorielle est transmise
 * au LLM en texte, avec des coordonnées exactes. Cascade :
 * calcul déterministe → LLM (local ou Groq) → repli 100 % local.
 */
public final class BureauBrain {

    public static final class BoxSpec {
        public final String label;
        public final float rx, ry, rw, rh;

        public BoxSpec(String label, float rx, float ry, float rw, float rh) {
            this.label = label;
            this.rx = rx; this.ry = ry; this.rw = rw; this.rh = rh;
        }
    }

    public static final class Result {
        public final String speak;
        public final List<String> lines;
        public final List<BoxSpec> boxes;
        public final BureauCalcHelper.Result calcResult;

        public Result(String speak, List<String> lines, List<BoxSpec> boxes) {
            this(speak, lines, boxes, null);
        }

        public Result(String speak, List<String> lines, List<BoxSpec> boxes,
                      BureauCalcHelper.Result calcResult) {
            this.speak = speak == null ? "" : speak;
            this.lines = lines == null ? new ArrayList<>() : lines;
            this.boxes = boxes == null ? new ArrayList<>() : boxes;
            this.calcResult = calcResult;
        }
    }

    public interface Callback {
        void onResult(Result result);
        void onError(String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BureauBrain() {}

    public static void analyze(Context context, BureauCanvasView.Snapshot snapshot,
                               String userPhrase, String sceneHint,
                               Set<String> processedExprKeys, Callback callback) {
        Context app = context.getApplicationContext();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        IO.execute(() -> {
            try {
                BureauSheetReader.SheetContext sheet = BureauSheetReader.read(snapshot);
                Result result = analyzeInternal(app, sheet, userPhrase, sceneHint, processedExprKeys);
                if (!cancelled.get()) MAIN.post(() -> callback.onResult(result));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Analyse impossible" : e.getMessage();
                if (!cancelled.get()) MAIN.post(() -> callback.onError(msg));
            }
        });
    }

    private static Result analyzeInternal(Context app, BureauSheetReader.SheetContext sheet,
                                          String userPhrase, String sceneHint,
                                          Set<String> processed) {
        // 1. Calcul déterministe : d'abord la voix, puis l'encre de la dernière ligne.
        boolean correction = BureauCorrectionHelper.parse(null, userPhrase).type
                != BureauCorrectionHelper.Type.NONE;
        boolean force = correction;

        BureauCalcHelper.Result calc = BureauCalcHelper.trySolve(userPhrase, processed, force);
        if (calc == null) {
            String pending = sheet.lastPendingText();
            if (!pending.isEmpty() && (wantsCalcIntent(userPhrase) || looksLikeExpression(pending) || correction)) {
                calc = BureauCalcHelper.trySolve(pending, processed, force);
            }
        }
        if (calc == null && !sheet.handwriting.isEmpty() && (wantsCalcIntent(userPhrase) || correction)) {
            calc = BureauCalcHelper.trySolve(sheet.handwriting, processed, force);
        }
        if (calc != null) {
            return new Result(calc.speak, calc.allDisplayLines(), new ArrayList<>(), calc);
        }

        // 2. LLM via PegaseSession (même backend que voix/texte).
        if (sheet.hasContent()) {
            try {
                PegaseSession session = PegaseSession.get(app);
                if (session.getChannel() != Channel.BUREAU) {
                    session.init(new SessionContext(Channel.BUREAU, false));
                }
                String reply = session.completeBureauSync(
                        buildPrompt(sheet, userPhrase, sceneHint));
                Result parsed = parseModelJson(reply);
                if (parsed != null) return parsed;
            } catch (Exception cloudErr) {
                android.util.Log.w("BureauBrain", "LLM indisponible → repli local", cloudErr);
            }
        }

        // 3. Repli local.
        return BureauLocalAnalyzer.analyze(sheet, userPhrase, sceneHint, processed);
    }

    private static boolean wantsCalcIntent(String phrase) {
        if (phrase == null) return false;
        String fold = phrase.toLowerCase(Locale.ROOT);
        return fold.contains("calcule") || fold.contains("calcul")
                || fold.contains("combien") || fold.contains("marge")
                || fold.contains("résultat") || fold.contains("resultat");
    }

    /** "12 x 4 =" ressemble à une expression : on tente le calcul même sans mot-clé. */
    private static boolean looksLikeExpression(String ink) {
        return ink != null && Pattern.compile("\\d\\s*[x*+\\-/]\\s*\\d").matcher(ink).find();
    }

    /**
     * Le prompt ne contient PAS d'image : il contient la géométrie exacte de la feuille.
     * Le modèle sait donc précisément où sont les choses — mieux qu'un modèle vision.
     */
    private static String buildPrompt(BureauSheetReader.SheetContext sheet,
                                      String userPhrase, String sceneHint) {
        return "Tu es Pégase, sur le bureau interactif Orbe. Français, ton bref, style croquis.\n"
                + "Tu ne vois pas d'image : voici la feuille décrite en vecteurs. "
                + "Les coordonnées sont relatives (0..1), origine en haut à gauche, et elles sont EXACTES.\n\n"
                + "=== FEUILLE ===\n"
                + (sheet.geometry.isEmpty() ? "(vide)" : sheet.geometry) + "\n\n"
                + "Texte manuscrit déchiffré : "
                + (sheet.handwriting.isEmpty() ? "(rien de lisible)" : sheet.handwriting) + "\n"
                + "Déjà écrit par toi : "
                + (sheet.pegaseSummary.isEmpty() ? "(rien)" : sheet.pegaseSummary) + "\n"
                + "Contexte : " + (sceneHint == null || sceneHint.isEmpty() ? "aucun" : sceneHint) + "\n"
                + "Demande : " + userPhrase + "\n\n"
                + "Règles :\n"
                + "- Tu écris sur TON calque uniquement, jamais sur les traits de l'utilisateur.\n"
                + "- Si l'utilisateur corrige (« non », « c'était », « recalcule », « enlève le résultat »), "
                + "REMPLACE ta dernière réponse — n'empile pas.\n"
                + "- Si le texte manuscrit semble faux, privilégie la correction vocale.\n"
                + "- Les encadrés doivent utiliser les coordonnées ci-dessus, pas des valeurs inventées.\n"
                + "- Max 4 lignes, 2 encadrés. Pas de markdown.\n\n"
                + "Réponds UNIQUEMENT en JSON valide :\n"
                + "{\"speak\":\"phrase vocale courte\",\"lines\":[\"note 1\"],"
                + "\"boxes\":[{\"label\":\"Thème\",\"rx\":0.08,\"ry\":0.14,\"rw\":0.35,\"rh\":0.11}]}";
    }

    /** Corrigé : CountDownLatch, plus de race notify/wait (qui bloquait 75 s). */
    private static Result parseModelJson(String raw) {
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(extractJsonObject(raw));
            String speak = o.optString("speak", "D'accord.");

            List<String> lines = new ArrayList<>();
            JSONArray arr = o.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length() && lines.size() < 4; i++) {
                    String line = arr.optString(i, "").trim();
                    if (!line.isEmpty()) lines.add(line);
                }
            }

            List<BoxSpec> boxes = new ArrayList<>();
            JSONArray boxesArr = o.optJSONArray("boxes");
            if (boxesArr != null) {
                for (int i = 0; i < boxesArr.length() && boxes.size() < 2; i++) {
                    JSONObject b = boxesArr.optJSONObject(i);
                    if (b == null) continue;
                    boxes.add(new BoxSpec(
                            b.optString("label", ""),
                            (float) b.optDouble("rx", 0.1),
                            (float) b.optDouble("ry", 0.1),
                            (float) b.optDouble("rw", 0.25),
                            (float) b.optDouble("rh", 0.1)));
                }
            }

            if (lines.isEmpty() && boxes.isEmpty() && speak.length() > 80) {
                lines.add(truncate(speak, 60));
            }
            return new Result(speak, lines, boxes);
        } catch (Exception e) {
            return null;   // → repli local, au lieu de fabriquer une pseudo-réponse
        }
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** "enlève le triangle", "efface la flèche"… → cible à retirer du calque Pégase. */
    public static String extractRemoveTarget(String spoken) {
        if (spoken == null) return null;
        Matcher m = Pattern.compile(
                "(?:enl[eè]ve|supprime|retire|efface)\\s+(?:la |le |les |l')(.+)",
                Pattern.CASE_INSENSITIVE).matcher(spoken.trim());
        if (m.find()) {
            String target = m.group(1).trim();
            if (target.length() > 1 && target.length() < 40) return target;
        }
        return null;
    }
}
