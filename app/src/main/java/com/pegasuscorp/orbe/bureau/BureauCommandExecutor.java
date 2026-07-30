package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Mutations structurées sur {@link BureauProject} — jamais sur le Markdown.
 * Historique = événements significatifs uniquement.
 */
public final class BureauCommandExecutor {

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final BureauProject project;

        public Result(boolean ok, String message, BureauProject project) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.project = project;
        }
    }

    private BureauCommandExecutor() {}

    public static Result setVision(Context ctx, String slug, String vision) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        p.vision = vision == null ? "" : vision.trim();
        touch(p);
        return persist(ctx, p, "Vision mise à jour");
    }

    public static Result replaceObjectives(Context ctx, String slug, List<String> objectives) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        p.objectives.clear();
        if (objectives != null) {
            for (String o : objectives) {
                if (o != null && !o.trim().isEmpty()) p.objectives.add(o.trim());
            }
        }
        touch(p);
        return persist(ctx, p, "Objectifs mis à jour");
    }

    public static Result appendObjective(Context ctx, String slug, String text) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        if (text == null || text.trim().isEmpty()) return fail("Objectif vide");
        p.objectives.add(text.trim());
        touch(p);
        return persist(ctx, p, "Objectif ajouté");
    }

    public static Result appendDecision(Context ctx, String slug, String text,
            BureauProject.Confidence confidence, String reason) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        if (text == null || text.trim().isEmpty()) return fail("Décision vide");
        long now = System.currentTimeMillis();
        BureauProject.Decision d = new BureauProject.Decision();
        d.id = BureauProject.newId();
        d.text = text.trim();
        d.confidence = confidence == null ? BureauProject.Confidence.CONFIRMED : confidence;
        d.reason = reason == null ? "" : reason.trim();
        d.createdAt = now;
        d.updatedAt = now;
        p.decisions.add(d);
        if (d.confidence == BureauProject.Confidence.CONFIRMED) {
            record(p, "Décision « " + truncate(d.text, 60) + " » confirmée");
        } else if (d.confidence == BureauProject.Confidence.HYPOTHESIS) {
            record(p, "Hypothèse ajoutée : « " + truncate(d.text, 60) + " »");
        } else {
            record(p, "À vérifier : « " + truncate(d.text, 60) + " »");
        }
        touch(p);
        return persist(ctx, p, "Décision ajoutée");
    }

    public static Result updateDecision(Context ctx, String slug, String decisionId,
            String text, BureauProject.Confidence confidence, String reason) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        BureauProject.Decision d = findDecision(p, decisionId);
        if (d == null) return fail("Décision introuvable");
        if (text != null && !text.trim().isEmpty()) d.text = text.trim();
        if (confidence != null) d.confidence = confidence;
        if (reason != null) d.reason = reason.trim();
        d.updatedAt = System.currentTimeMillis();
        touch(p);
        return persist(ctx, p, "Décision mise à jour");
    }

    public static Result promoteHypothesis(Context ctx, String slug, String decisionId) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        BureauProject.Decision d = findDecision(p, decisionId);
        if (d == null) return fail("Décision introuvable");
        d.confidence = BureauProject.Confidence.CONFIRMED;
        d.updatedAt = System.currentTimeMillis();
        record(p, "Décision « " + truncate(d.text, 60) + " » confirmée");
        touch(p);
        return persist(ctx, p, "Hypothèse confirmée");
    }

    public static Result appendTask(Context ctx, String slug, String text) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        if (text == null || text.trim().isEmpty()) return fail("Tâche vide");
        long now = System.currentTimeMillis();
        BureauProject.Task t = new BureauProject.Task();
        t.id = BureauProject.newId();
        t.text = text.trim();
        t.done = false;
        t.createdAt = now;
        t.updatedAt = now;
        p.tasks.add(t);
        touch(p);
        return persist(ctx, p, "Tâche ajoutée");
    }

    public static Result completeTask(Context ctx, String slug, String taskId) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        BureauProject.Task t = findTask(p, taskId);
        if (t == null) return fail("Tâche introuvable");
        if (!t.done) {
            t.done = true;
            t.updatedAt = System.currentTimeMillis();
            record(p, "Tâche « " + truncate(t.text, 60) + " » terminée");
            touch(p);
            return persist(ctx, p, "Tâche terminée");
        }
        return new Result(true, "Déjà terminée", p);
    }

    public static Result uncompleteTask(Context ctx, String slug, String taskId) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        BureauProject.Task t = findTask(p, taskId);
        if (t == null) return fail("Tâche introuvable");
        t.done = false;
        t.updatedAt = System.currentTimeMillis();
        touch(p);
        return persist(ctx, p, "Tâche réouverte");
    }

    public static Result appendOpenQuestion(Context ctx, String slug, String text) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        if (text == null || text.trim().isEmpty()) return fail("Question vide");
        long now = System.currentTimeMillis();
        BureauProject.OpenQuestion q = new BureauProject.OpenQuestion();
        q.id = BureauProject.newId();
        q.text = text.trim();
        q.createdAt = now;
        q.updatedAt = now;
        p.openQuestions.add(q);
        touch(p);
        return persist(ctx, p, "Question ajoutée");
    }

    public static Result removeOpenQuestion(Context ctx, String slug, String questionId) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        boolean removed = false;
        Iterator<BureauProject.OpenQuestion> it = p.openQuestions.iterator();
        while (it.hasNext()) {
            BureauProject.OpenQuestion q = it.next();
            if (q != null && questionId != null && questionId.equals(q.id)) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (!removed) return fail("Question introuvable");
        touch(p);
        return persist(ctx, p, "Question retirée");
    }

    public static Result appendResearch(Context ctx, String slug, String title, String content) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        String t = title == null || title.trim().isEmpty() ? "note" : title.trim();
        String filename = BureauResearchStore.sanitizeFilename(
                BureauProject.slugify(t) + ".md");
        String body = content == null ? "" : content;
        if (!body.trim().startsWith("#")) {
            body = "# " + t + "\n\n" + body;
        }
        if (!BureauResearchStore.save(ctx, filename, body)) {
            return fail("Impossible d'écrire la recherche");
        }
        long now = System.currentTimeMillis();
        BureauProject.Reference ref = new BureauProject.Reference();
        ref.id = BureauProject.newId();
        ref.title = t;
        ref.path = BureauResearchStore.relativePath(filename);
        ref.createdAt = now;
        ref.updatedAt = now;
        p.references.add(ref);
        record(p, "Référence ajoutée : « " + truncate(t, 60) + " »");
        touch(p);
        return persist(ctx, p, "Recherche ajoutée");
    }

    public static Result removeReference(Context ctx, String slug, String referenceId) {
        BureauProject p = require(ctx, slug);
        if (p == null) return fail("Projet introuvable");
        BureauProject.Reference found = null;
        Iterator<BureauProject.Reference> it = p.references.iterator();
        while (it.hasNext()) {
            BureauProject.Reference r = it.next();
            if (r != null && referenceId != null && referenceId.equals(r.id)) {
                found = r;
                it.remove();
                break;
            }
        }
        if (found == null) return fail("Référence introuvable");
        if (found.path != null && found.path.startsWith("research/")) {
            String name = found.path.substring("research/".length());
            BureauResearchStore.delete(ctx, name);
        }
        touch(p);
        return persist(ctx, p, "Référence retirée");
    }

    /** Crée un projet neuf avec historique « Projet créé… ». */
    public static Result createFromMaterialized(Context ctx, BureauProject project,
            boolean fromInterview) {
        if (ctx == null || project == null) return fail("Projet invalide");
        if (!BureauPlanningBrain.hasSubstance(project)) {
            return fail("Plan vide — précise le projet puis réessaie");
        }
        ensureIds(project);
        if (project.slug == null || project.slug.isEmpty()) {
            project.slug = BureauProject.slugify(project.title);
        }
        long now = System.currentTimeMillis();
        if (project.createdAt <= 0) project.createdAt = now;
        project.updatedAt = now;
        if (project.history.isEmpty()) {
            record(project, fromInterview
                    ? "Projet créé après entretien avec Pégase"
                    : "Projet créé");
        }
        if (!BureauProjectStore.save(ctx, project)) {
            return fail("Échec de sauvegarde");
        }
        // Miroir dans les contextes chat — sinon « importer un plan » ne le trouve pas.
        try {
            String md = BureauMarkdownBuilder.render(project);
            ContextualFileStore.getInstance(ctx).save(project.slug, md);
        } catch (Exception ignored) {
        }
        return new Result(true, "Projet créé", project);
    }

    /**
     * Applique un tableau JSON de commandes (sortie LLM).
     * Ops supportées : setVision, appendTask, completeTask, appendDecision,
     * promoteHypothesis, appendOpenQuestion, removeOpenQuestion, appendResearch,
     * appendObjective, replaceObjectives, updateDecision.
     */
    public static Result applyCommandsJson(Context ctx, String slug, String commandsJson) {
        if (commandsJson == null || commandsJson.trim().isEmpty()) {
            return fail("Aucune commande");
        }
        try {
            String raw = extractCommandsArray(commandsJson);
            JSONArray arr = new JSONArray(raw);
            String lastMsg = "OK";
            BureauProject last = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject cmd = arr.optJSONObject(i);
                if (cmd == null) continue;
                Result r = applyOne(ctx, slug, cmd);
                if (!r.ok) return r;
                lastMsg = r.message;
                last = r.project;
            }
            return new Result(true, lastMsg, last != null ? last : require(ctx, slug));
        } catch (Exception e) {
            return fail("Commandes invalides : " + e.getMessage());
        }
    }

    private static Result applyOne(Context ctx, String slug, JSONObject cmd) {
        String op = cmd.optString("op", cmd.optString("action", "")).trim();
        String fold = op.toLowerCase(Locale.ROOT).replace('-', '_');
        switch (fold) {
            case "setvision":
            case "set_vision":
                return setVision(ctx, slug, cmd.optString("text", cmd.optString("vision", "")));
            case "appendtask":
            case "append_task":
                return appendTask(ctx, slug, cmd.optString("text", ""));
            case "completetask":
            case "complete_task":
                return completeTask(ctx, slug, cmd.optString("taskId", cmd.optString("id", "")));
            case "uncompletetask":
            case "uncomplete_task":
                return uncompleteTask(ctx, slug, cmd.optString("taskId", cmd.optString("id", "")));
            case "appenddecision":
            case "append_decision":
                return appendDecision(ctx, slug,
                        cmd.optString("text", ""),
                        BureauProject.Confidence.fromString(cmd.optString("confidence", "CONFIRMED")),
                        cmd.optString("reason", ""));
            case "updatedecision":
            case "update_decision":
                return updateDecision(ctx, slug,
                        cmd.optString("decisionId", cmd.optString("id", "")),
                        cmd.has("text") ? cmd.optString("text") : null,
                        cmd.has("confidence")
                                ? BureauProject.Confidence.fromString(cmd.optString("confidence"))
                                : null,
                        cmd.has("reason") ? cmd.optString("reason") : null);
            case "promotehypothesis":
            case "promote_hypothesis":
                return promoteHypothesis(ctx, slug,
                        cmd.optString("decisionId", cmd.optString("id", "")));
            case "appendopenquestion":
            case "append_open_question":
                return appendOpenQuestion(ctx, slug, cmd.optString("text", ""));
            case "removeopenquestion":
            case "remove_open_question":
                return removeOpenQuestion(ctx, slug,
                        cmd.optString("questionId", cmd.optString("id", "")));
            case "appendresearch":
            case "append_research":
                return appendResearch(ctx, slug,
                        cmd.optString("title", "note"),
                        cmd.optString("content", cmd.optString("text", "")));
            case "removereference":
            case "remove_reference":
                return removeReference(ctx, slug,
                        cmd.optString("referenceId", cmd.optString("id", "")));
            case "appendobjective":
            case "append_objective":
                return appendObjective(ctx, slug, cmd.optString("text", ""));
            case "replaceobjectives":
            case "replace_objectives": {
                List<String> list = new ArrayList<>();
                JSONArray a = cmd.optJSONArray("objectives");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        String s = a.optString(i, "").trim();
                        if (!s.isEmpty()) list.add(s);
                    }
                }
                return replaceObjectives(ctx, slug, list);
            }
            default:
                return fail("Opération inconnue : " + op);
        }
    }

    /** Extrait le tableau JSON depuis un blob pouvant contenir du texte autour. */
    public static String extractCommandsArray(String raw) {
        if (raw == null) return "[]";
        String t = raw.trim();
        int marker = t.toUpperCase(Locale.ROOT).indexOf("COMMANDS");
        if (marker >= 0) {
            int colon = t.indexOf(':', marker);
            int bracket = t.indexOf('[', marker);
            if (bracket >= 0 && (colon < 0 || bracket > colon)) {
                return extractBalancedArray(t, bracket);
            }
        }
        int first = t.indexOf('[');
        if (first >= 0) return extractBalancedArray(t, first);
        return t;
    }

    private static String extractBalancedArray(String t, int start) {
        int depth = 0;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return t.substring(start, i + 1);
            }
        }
        return t.substring(start);
    }

    private static BureauProject require(Context ctx, String slug) {
        if (ctx == null || slug == null) return null;
        return BureauProjectStore.load(ctx, slug);
    }

    private static void ensureIds(BureauProject p) {
        if (p.id == null || p.id.isEmpty()) p.id = BureauProject.newId();
        for (BureauProject.Decision d : p.decisions) {
            if (d.id == null || d.id.isEmpty()) d.id = BureauProject.newId();
            if (d.createdAt <= 0) d.createdAt = System.currentTimeMillis();
            if (d.updatedAt <= 0) d.updatedAt = d.createdAt;
        }
        for (BureauProject.Task t : p.tasks) {
            if (t.id == null || t.id.isEmpty()) t.id = BureauProject.newId();
            if (t.createdAt <= 0) t.createdAt = System.currentTimeMillis();
            if (t.updatedAt <= 0) t.updatedAt = t.createdAt;
        }
        for (BureauProject.OpenQuestion q : p.openQuestions) {
            if (q.id == null || q.id.isEmpty()) q.id = BureauProject.newId();
            if (q.createdAt <= 0) q.createdAt = System.currentTimeMillis();
            if (q.updatedAt <= 0) q.updatedAt = q.createdAt;
        }
        for (BureauProject.Reference r : p.references) {
            if (r.id == null || r.id.isEmpty()) r.id = BureauProject.newId();
            if (r.createdAt <= 0) r.createdAt = System.currentTimeMillis();
            if (r.updatedAt <= 0) r.updatedAt = r.createdAt;
        }
        for (BureauProject.HistoryEntry h : p.history) {
            if (h.id == null || h.id.isEmpty()) h.id = BureauProject.newId();
            if (h.createdAt <= 0) h.createdAt = System.currentTimeMillis();
        }
    }

    static void record(BureauProject p, String event) {
        if (p == null || event == null || event.trim().isEmpty()) return;
        BureauProject.HistoryEntry h = new BureauProject.HistoryEntry();
        h.id = BureauProject.newId();
        h.text = event.trim();
        h.createdAt = System.currentTimeMillis();
        p.history.add(h);
    }

    private static void touch(BureauProject p) {
        p.updatedAt = System.currentTimeMillis();
    }

    private static Result persist(Context ctx, BureauProject p, String msg) {
        if (!BureauProjectStore.save(ctx, p)) return fail("Échec de sauvegarde");
        try {
            ContextualFileStore.getInstance(ctx).save(p.slug, BureauMarkdownBuilder.render(p));
        } catch (Exception ignored) {
        }
        return new Result(true, msg, p);
    }

    private static Result fail(String msg) {
        return new Result(false, msg, null);
    }

    private static BureauProject.Decision findDecision(BureauProject p, String id) {
        if (id == null) return null;
        for (BureauProject.Decision d : p.decisions) {
            if (d != null && id.equals(d.id)) return d;
        }
        return null;
    }

    private static BureauProject.Task findTask(BureauProject p, String id) {
        if (id == null) return null;
        for (BureauProject.Task t : p.tasks) {
            if (t != null && id.equals(t.id)) return t;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
