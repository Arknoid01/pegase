package com.pegasuscorp.orbe.bureau;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interview de planification + matérialisation JSON (sans muter le Markdown).
 */
public final class BureauPlanningBrain {

    public enum Readiness {
        NEED_INFO,
        READY
    }

    public static final class InterviewTurnResult {
        public final String speakText;
        public final Readiness readiness;
        public final String summary;
        public final List<String> questions;

        public InterviewTurnResult(String speakText, Readiness readiness,
                String summary, List<String> questions) {
            this.speakText = speakText == null ? "" : speakText.trim();
            this.readiness = readiness == null ? Readiness.NEED_INFO : readiness;
            this.summary = summary == null ? "" : summary.trim();
            this.questions = questions != null ? questions : new ArrayList<>();
        }

        public boolean isReady() {
            return readiness == Readiness.READY;
        }
    }

    private static final Pattern READINESS = Pattern.compile(
            "(?im)READINESS\\s*[:=]\\s*(NEED_INFO|READY)\\b");
    private static final Pattern SUMMARY = Pattern.compile(
            "(?im)SUMMARY\\s*[:=]\\s*(.+)");

    private BureauPlanningBrain() {}

    public static String buildInterviewPrompt(String draftTitle, List<BureauChatStore.Turn> turns,
            String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es Pégase, copilote de planification dans Orbe (Bureau).\n");
        sb.append("MODE PLANIFICATION — conversation pour clarifier un projet AVANT de créer le plan.\n");
        sb.append("Tu poses 1 à 3 questions ciblées par tour (vision, contraintes tech, utilisateurs, scope).\n");
        sb.append("Tu ne génères PAS de Markdown, PAS de recherche web longue, PAS de code.\n");
        sb.append("Réponds en français, phrases courtes et naturelles.\n");
        sb.append("IMPORTANT : le texte oral (avant les marqueurs) DOIT contenir tes vraies questions.\n");
        sb.append("Interdit de répondre uniquement avec des marqueurs READINESS/QUESTIONS.\n");
        sb.append("N'utilise jamais la phrase générique « J'ai quelques questions pour clarifier ».\n\n");
        if (draftTitle != null && !draftTitle.trim().isEmpty()) {
            sb.append("Titre provisoire : ").append(draftTitle.trim()).append("\n\n");
        }
        sb.append("=== HISTORIQUE ===\n");
        if (turns != null) {
            int start = Math.max(0, turns.size() - 16);
            int userCount = 0;
            for (BureauChatStore.Turn t : turns) {
                if (t != null && t.fromUser) userCount++;
            }
            for (int i = start; i < turns.size(); i++) {
                BureauChatStore.Turn t = turns.get(i);
                if (t == null) continue;
                String body = t.text == null ? "" : t.text;
                // Ne pas réinjecter le fallback générique qui provoque la boucle
                if (!t.fromUser && isGenericClarifyFallback(body)) {
                    body = "(questions déjà posées — ne répète pas une phrase générique)";
                }
                sb.append(t.fromUser ? "Utilisateur" : "Pégase").append(" : ")
                        .append(body).append("\n");
            }
            if (userCount >= 2) {
                sb.append("\nNote : l'utilisateur a déjà répondu plusieurs fois. ");
                sb.append("Si la vision + plateforme + stockage sont connus, préfère READINESS: READY.\n");
            }
        }
        sb.append("\n=== MESSAGE ===\n")
                .append(userMessage == null ? "" : userMessage.trim()).append("\n\n");
        sb.append("Format OBLIGATOIRE :\n");
        sb.append("1) D'abord 2–6 phrases orales avec tes questions (lisibles par l'utilisateur).\n");
        sb.append("2) Puis EN FIN de réponse uniquement le bloc machine :\n");
        sb.append("READINESS: NEED_INFO\n");
        sb.append("QUESTIONS:\n");
        sb.append("1. …\n");
        sb.append("2. …\n\n");
        sb.append("OU, si tu as assez d'info pour un premier plan (vision, 2–4 objectifs, ");
        sb.append("quelques décisions/tâches) :\n");
        sb.append("…phrase orale du type « On peut créer le plan. »…\n");
        sb.append("READINESS: READY\n");
        sb.append("SUMMARY: résumé en 2–4 phrases\n");
        return sb.toString();
    }

    public static String buildMaterializePrompt(String draftTitle,
            List<BureauChatStore.Turn> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es Pégase. À partir de l'entretien de planification ci-dessous, ");
        sb.append("produis UNIQUEMENT un objet JSON (pas de Markdown, pas de prose).\n");
        sb.append("Schéma strict :\n");
        sb.append("{\n");
        sb.append("  \"title\": \"string\",\n");
        sb.append("  \"vision\": \"une seule phrase\",\n");
        sb.append("  \"objectives\": [\"…\"],\n");
        sb.append("  \"decisions\": [{\"text\":\"…\",\"confidence\":\"CONFIRMED|HYPOTHESIS|TO_VERIFY\",\"reason\":\"\"}],\n");
        sb.append("  \"tasks\": [{\"text\":\"…\",\"done\":false}],\n");
        sb.append("  \"openQuestions\": [{\"text\":\"…\"}]\n");
        sb.append("}\n");
        sb.append("Règles : vision = 1 phrase ; décisions datées via confidence ; ");
        sb.append("tâches actionnables ; openQuestions seulement si vraiment non résolues.\n");
        sb.append("N'invente pas d'IDs (le code les génère). N'ajoute pas history ni references.\n\n");
        if (draftTitle != null && !draftTitle.trim().isEmpty()) {
            sb.append("Titre suggéré : ").append(draftTitle.trim()).append("\n\n");
        }
        sb.append("=== ENTRETIEN ===\n");
        if (turns != null) {
            for (BureauChatStore.Turn t : turns) {
                if (t == null) continue;
                sb.append(t.fromUser ? "Utilisateur" : "Pégase").append(" : ")
                        .append(t.text == null ? "" : t.text).append("\n");
            }
        }
        sb.append("\nRéponds avec le JSON seul (éventuellement dans ```json … ```).\n");
        return sb.toString();
    }

    /** Prompt édition projet structuré — commandes JSON uniquement. */
    public static String buildCommandsPrompt(BureauProject project, String userRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es Pégase. MODE COMMANDES — tu ne réécris JAMAIS le Markdown.\n");
        sb.append("Projet ouvert (résumé JSON) :\n");
        try {
            sb.append(BureauProjectStore.toJson(project).toString(2));
        } catch (Exception e) {
            sb.append("(indisponible)");
        }
        sb.append("\n\nDemande utilisateur :\n")
                .append(userRequest == null ? "" : userRequest.trim()).append("\n\n");
        sb.append("Réponds avec un court texte oral (2 phrases max) puis :\n");
        sb.append("COMMANDS:\n");
        sb.append("[{\"op\":\"appendTask\",\"text\":\"…\"}, ");
        sb.append("{\"op\":\"completeTask\",\"taskId\":\"…\"}, …]\n");
        sb.append("Ops : setVision, appendTask, completeTask, appendDecision, ");
        sb.append("promoteHypothesis, updateDecision, appendOpenQuestion, ");
        sb.append("removeOpenQuestion, appendResearch, appendObjective, replaceObjectives.\n");
        sb.append("Utilise les IDs existants pour completeTask / promoteHypothesis / updateDecision.\n");
        sb.append("Si aucune mutation nécessaire, COMMANDS: []\n");
        return sb.toString();
    }

    public static InterviewTurnResult parseInterviewReply(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new InterviewTurnResult(
                    "Peux-tu préciser un peu plus ton idée ?",
                    Readiness.NEED_INFO, "", new ArrayList<>());
        }
        String text = raw.trim();
        Readiness readiness = detectReadiness(text);
        String summary = "";
        Matcher sm = SUMMARY.matcher(text);
        // Dernier SUMMARY gagne (souvent après un READY au milieu)
        while (sm.find()) {
            summary = sm.group(1).trim();
        }

        List<String> questions = new ArrayList<>();
        String upper = text.toUpperCase(Locale.ROOT);
        int qIdx = upper.lastIndexOf("QUESTIONS:");
        if (qIdx < 0) qIdx = upper.lastIndexOf("QUESTIONS :");
        if (qIdx >= 0) {
            int start = text.indexOf(':', qIdx) + 1;
            if (start > 0) {
                String qBlock = text.substring(start);
                for (String line : qBlock.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    String u = t.toUpperCase(Locale.ROOT);
                    if (u.startsWith("READINESS") || u.startsWith("SUMMARY")) continue;
                    if (u.matches("^(NEED_INFO|READY)\\b.*")) continue;
                    t = t.replaceFirst("^\\d+[.)]\\s*", "").trim();
                    t = t.replaceAll("\\*+", "").trim();
                    if (!t.isEmpty() && !looksLikeMarkerLine(t)) questions.add(t);
                    if (questions.size() >= 3) break;
                }
            }
        }

        String speak = stripMarkers(text);
        speak = buildDisplaySpeak(speak, readiness, summary, questions);

        if (readiness != Readiness.READY) {
            String fold = foldFr(speak);
            if (fold.contains("assez d'elements")
                    || fold.contains("assez d elements")
                    || fold.contains("on peut creer le plan")
                    || fold.contains("tu peux creer le plan")
                    || fold.contains("pret a creer")
                    || fold.contains("j'ai assez")
                    || fold.contains("je pense qu'on a assez")
                    || fold.contains("on a assez d'info")) {
                readiness = Readiness.READY;
                if (summary.isEmpty()) summary = speak;
            }
        }
        return new InterviewTurnResult(speak, readiness, summary, questions);
    }

    /**
     * Texte montré à l'utilisateur : jamais le fallback vide sans questions.
     * Si le modèle n'a renvoyé que des marqueurs, on affiche la liste QUESTIONS.
     */
    static String buildDisplaySpeak(String stripped, Readiness readiness,
            String summary, List<String> questions) {
        String speak = stripped == null ? "" : stripped.trim();
        // Éviter la boucle sur le fallback générique seul
        if (isGenericClarifyFallback(speak)) {
            speak = "";
        }
        if (!speak.isEmpty()) {
            // Si le oral n'inclut pas déjà les questions, les ajouter
            if (questions != null && !questions.isEmpty()
                    && readiness != Readiness.READY
                    && !speakContainsAnyQuestion(speak, questions)) {
                return speak + "\n\n" + formatQuestionsBlock(questions);
            }
            return speak;
        }
        if (readiness == Readiness.READY) {
            if (summary != null && !summary.trim().isEmpty()) return summary.trim();
            return "J'ai assez d'éléments — tu peux créer le plan.";
        }
        if (questions != null && !questions.isEmpty()) {
            return "Quelques précisions :\n" + formatQuestionsBlock(questions);
        }
        return "Peux-tu préciser la vision, la plateforme (ex. Android) et le stockage des données ?";
    }

    static boolean isGenericClarifyFallback(String speak) {
        if (speak == null) return true;
        String fold = foldFr(speak).trim();
        return fold.equals("j'ai quelques questions pour clarifier.")
                || fold.equals("j'ai quelques questions pour clarifier")
                || fold.equals("j ai quelques questions pour clarifier.")
                || fold.equals("j ai quelques questions pour clarifier");
    }

    private static boolean speakContainsAnyQuestion(String speak, List<String> questions) {
        String fold = foldFr(speak);
        for (String q : questions) {
            if (q == null || q.length() < 8) continue;
            String qf = foldFr(q);
            if (qf.length() > 24) qf = qf.substring(0, 24);
            if (fold.contains(qf)) return true;
        }
        return false;
    }

    private static String formatQuestionsBlock(List<String> questions) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String q : questions) {
            if (q == null || q.trim().isEmpty()) continue;
            n++;
            sb.append(n).append(". ").append(q.trim()).append('\n');
            if (n >= 3) break;
        }
        return sb.toString().trim();
    }

    /**
     * Cherche READY / NEED_INFO n'importe où dans la réponse (milieu inclus).
     * Le dernier marqueur explicite gagne.
     */
    static Readiness detectReadiness(String text) {
        if (text == null || text.isEmpty()) return Readiness.NEED_INFO;
        Readiness last = null;
        Matcher rm = READINESS.matcher(text);
        while (rm.find()) {
            if ("READY".equalsIgnoreCase(rm.group(1))) last = Readiness.READY;
            else last = Readiness.NEED_INFO;
        }
        if (last != null) return last;

        // Variantes sans "READINESS" : ligne seule, markdown, parenthèses
        Matcher bare = Pattern.compile(
                "(?im)(?:^|[\\s*_(\\[])READY(?:\\s*$|[\\s*_\\])\\n.,;:])").matcher(text);
        Matcher bareNeed = Pattern.compile(
                "(?im)(?:^|[\\s*_(\\[])NEED[_ ]?INFO(?:\\s*$|[\\s*_\\])\\n.,;:])").matcher(text);
        boolean sawReady = bare.find();
        boolean sawNeed = bareNeed.find();
        // Si les deux, on regarde la position du dernier
        if (sawReady || sawNeed) {
            int readyPos = -1;
            int needPos = -1;
            Matcher b1 = Pattern.compile("(?im)\\bREADY\\b").matcher(text);
            while (b1.find()) readyPos = b1.start();
            Matcher b2 = Pattern.compile("(?im)\\bNEED[_ ]?INFO\\b").matcher(text);
            while (b2.find()) needPos = b2.start();
            if (readyPos > needPos) return Readiness.READY;
            if (needPos >= 0) return Readiness.NEED_INFO;
        }
        return Readiness.NEED_INFO;
    }

    private static String foldFr(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('â', 'a');
    }

    private static boolean looksLikeMarkerLine(String t) {
        String u = t.toUpperCase(Locale.ROOT);
        return u.startsWith("READINESS") || u.startsWith("SUMMARY")
                || u.equals("READY") || u.startsWith("NEED_INFO") || u.startsWith("NEED INFO");
    }

    public static String stripMarkers(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inQuestions = false;
        for (String line : lines) {
            String t = line.trim();
            String u = t.toUpperCase(Locale.ROOT)
                    .replace("*", "")
                    .replace("_", "")
                    .replace("`", "")
                    .trim();
            if (u.startsWith("QUESTIONS:") || u.equals("QUESTIONS") || u.startsWith("QUESTIONS :")) {
                inQuestions = true;
                continue;
            }
            if (u.startsWith("READINESS") || u.startsWith("SUMMARY")
                    || u.startsWith("COMMANDS")
                    || u.equals("READY") || u.equals("NEED_INFO") || u.equals("NEED INFO")
                    || READINESS.matcher(t).find()
                    || SUMMARY.matcher(t).find()) {
                continue;
            }
            if (inQuestions) {
                // Lignes du bloc QUESTIONS — pas dans le texte oral
                if (t.matches("^\\d+[.)].*") || t.startsWith("-")) continue;
                // Fin implicite du bloc si ligne prose sans numéro
                if (!t.isEmpty() && !t.matches("^\\d+[.)].*")) {
                    inQuestions = false;
                } else {
                    continue;
                }
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        String out = sb.toString().trim();
        out = READINESS.matcher(out).replaceAll("").trim();
        out = SUMMARY.matcher(out).replaceAll("").trim();
        out = out.replaceAll("[ \\t]{2,}", " ").trim();
        return out;
    }

    /**
     * Parse le JSON matérialisé et remplit un {@link BureauProject} (IDs générés ici).
     * @return projet rempli, ou {@code null} si JSON absent / projet sans substance
     *         (évite de sauver un plan vide à titres seuls).
     */
    public static BureauProject parseMaterializeJson(String raw, String fallbackTitle) {
        String json = extractJsonObject(raw);
        if (json == null || json.isEmpty()) {
            return null;
        }
        BureauProject p = new BureauProject();
        try {
            JSONObject o = new JSONObject(json);
            p.title = o.optString("title", fallbackTitle == null ? "Nouveau projet" : fallbackTitle).trim();
            if (p.title.isEmpty()) p.title = "Nouveau projet";
            p.slug = BureauProject.slugify(p.title);
            p.vision = o.optString("vision", "").trim();
            long now = System.currentTimeMillis();
            p.createdAt = now;
            p.updatedAt = now;
            p.id = BureauProject.newId();

            JSONArray objs = o.optJSONArray("objectives");
            if (objs != null) {
                for (int i = 0; i < objs.length(); i++) {
                    String s = objs.optString(i, "").trim();
                    if (!s.isEmpty()) p.objectives.add(s);
                }
            }
            JSONArray dec = o.optJSONArray("decisions");
            if (dec != null) {
                for (int i = 0; i < dec.length(); i++) {
                    JSONObject x = dec.optJSONObject(i);
                    if (x == null) continue;
                    String text = x.optString("text", "").trim();
                    if (text.isEmpty()) continue;
                    BureauProject.Decision d = new BureauProject.Decision();
                    d.id = BureauProject.newId();
                    d.text = text;
                    d.confidence = BureauProject.Confidence.fromString(
                            x.optString("confidence", "CONFIRMED"));
                    d.reason = x.optString("reason", "");
                    d.createdAt = now;
                    d.updatedAt = now;
                    p.decisions.add(d);
                }
            }
            JSONArray tasks = o.optJSONArray("tasks");
            if (tasks != null) {
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject x = tasks.optJSONObject(i);
                    String text;
                    boolean done = false;
                    if (x != null) {
                        text = x.optString("text", "").trim();
                        done = x.optBoolean("done", false);
                    } else {
                        text = tasks.optString(i, "").trim();
                    }
                    if (text.isEmpty()) continue;
                    BureauProject.Task t = new BureauProject.Task();
                    t.id = BureauProject.newId();
                    t.text = text;
                    t.done = done;
                    t.createdAt = now;
                    t.updatedAt = now;
                    p.tasks.add(t);
                }
            }
            JSONArray qs = o.optJSONArray("openQuestions");
            if (qs != null) {
                for (int i = 0; i < qs.length(); i++) {
                    JSONObject x = qs.optJSONObject(i);
                    String text;
                    if (x != null) text = x.optString("text", "").trim();
                    else text = qs.optString(i, "").trim();
                    if (text.isEmpty()) continue;
                    BureauProject.OpenQuestion q = new BureauProject.OpenQuestion();
                    q.id = BureauProject.newId();
                    q.text = text;
                    q.createdAt = now;
                    q.updatedAt = now;
                    p.openQuestions.add(q);
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (!hasSubstance(p)) return null;
        return p;
    }

    /** Vision / objectifs / tâches / décisions / questions — sinon plan « vide ». */
    public static boolean hasSubstance(BureauProject p) {
        if (p == null) return false;
        if (p.vision != null && !p.vision.trim().isEmpty()) return true;
        if (p.objectives != null) {
            for (String o : p.objectives) {
                if (o != null && !o.trim().isEmpty()) return true;
            }
        }
        if (p.tasks != null && !p.tasks.isEmpty()) return true;
        if (p.decisions != null && !p.decisions.isEmpty()) return true;
        if (p.openQuestions != null && !p.openQuestions.isEmpty()) return true;
        return false;
    }

    public static String extractJsonObject(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                int end = t.lastIndexOf("```");
                if (end > nl) t = t.substring(nl + 1, end).trim();
            }
        }
        int start = t.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return t.substring(start, i + 1);
            }
        }
        return null;
    }

    /** Sépare texte oral et bloc COMMANDS d'une réponse d'édition. */
    public static final class CommandsReply {
        public final String speak;
        public final String commandsJson;

        public CommandsReply(String speak, String commandsJson) {
            this.speak = speak == null ? "" : speak.trim();
            this.commandsJson = commandsJson == null ? "[]" : commandsJson;
        }
    }

    public static CommandsReply parseCommandsReply(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new CommandsReply("", "[]");
        }
        String speak = stripMarkers(raw);
        String cmds = BureauCommandExecutor.extractCommandsArray(raw);
        return new CommandsReply(speak, cmds);
    }
}
