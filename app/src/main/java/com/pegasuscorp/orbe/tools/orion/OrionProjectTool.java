package com.pegasuscorp.orbe.tools.orion;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.git.GitHubApiClient;
import com.pegasuscorp.orbe.orion.OrionFileSession;
import com.pegasuscorp.orbe.orion.OrionFileStore;
import com.pegasuscorp.orbe.orion.OrionProjectStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Projets Orion locaux ({@code files/orion/projects/}) — CRUD + push conscient.
 */
public final class OrionProjectTool implements Tool {

    @Override
    public String id() {
        return "orion_project";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.ORION_CODE;
    }

    @Override
    public String description() {
        return "orion_project(action:\"list\"|\"create\"|\"switch\"|\"files\"|\"delete_file\""
                + "|\"rename\"|\"push\"|\"make_public\", name?:str, file?:str, new_name?:str, "
                + "files?:[str], message?:str, confirm?:bool, private?:bool) — "
                + "Workspace local Orion. "
                + "« nouveau projet X », « passe sur le projet Y », "
                + "« montre les fichiers du projet », « push le projet sur GitHub », "
                + "« push index.html seulement » (files:[…]), « renomme a.js en b.js », "
                + "« passe le dépôt en public » (make_public). "
                + "Push propose la liste des dépôts (création optionnelle). "
                + "Fichiers au même chemin = remplacés. private:true pour un dépôt privé.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "list").trim().toLowerCase(Locale.ROOT)
                : "list";
        OrionProjectStore store = OrionProjectStore.get(ctx);

        switch (action) {
            case "list":
            case "status":
            case "show":
                cb.onSuccess(ToolResult.text(store.speakSummary()));
                return;

            case "files":
            case "list_files":
                cb.onSuccess(ToolResult.text(store.speakSummary()));
                return;

            case "create":
            case "new":
            case "nouveau": {
                String name = firstNonEmpty(params, "name", "project", "projet");
                if (name.isEmpty()) {
                    cb.onError("Indique le nom du projet (ex. balle-html).");
                    return;
                }
                String created = store.createProject(name);
                if (created.isEmpty()) {
                    cb.onError("Nom de projet invalide : " + name);
                    return;
                }
                cb.onSuccess(ToolResult.text(
                        "Projet « " + created + " » créé et activé. Prêt pour coder."));
                return;
            }

            case "switch":
            case "set":
            case "passe":
            case "ouvrir": {
                String name = firstNonEmpty(params, "name", "project", "projet");
                if (name.isEmpty()) {
                    List<String> projects = store.listProjects();
                    if (projects.isEmpty()) {
                        cb.onError("Aucun projet — dis « nouveau projet … ».");
                        return;
                    }
                    String[] labels = new String[projects.size()];
                    for (int i = 0; i < projects.size(); i++) {
                        labels[i] = (i + 1) + ". " + projects.get(i);
                    }
                    final List<String> picks = new ArrayList<>(projects);
                    cb.onChoiceNeeded("Quel projet Orion ?", labels,
                            index -> {
                                if (index < 0 || index >= picks.size()) {
                                    cb.onError("Choix invalide.");
                                    return;
                                }
                                store.setActive(picks.get(index));
                                cb.onSuccess(ToolResult.text(store.speakSummary()));
                            },
                            () -> cb.onSuccess(ToolResult.text("Switch annulé.")));
                    return;
                }
                String resolved = resolveProjectName(store, name);
                if (resolved == null) {
                    String created = store.createProject(name);
                    if (created.isEmpty()) {
                        cb.onError("Projet introuvable : " + name);
                        return;
                    }
                    cb.onSuccess(ToolResult.text(
                            "Projet « " + created + " » créé et activé.\n"
                                    + store.speakSummary()));
                    return;
                }
                store.setActive(resolved);
                cb.onSuccess(ToolResult.text(store.speakSummary()));
                return;
            }

            case "rename":
            case "renomme": {
                String from = firstNonEmpty(params, "file", "filename", "path", "from", "old");
                String to = firstNonEmpty(params, "new_name", "to", "as");
                if (from.isEmpty() || to.isEmpty()) {
                    cb.onError("Indique l'ancien et le nouveau nom (file + new_name).");
                    return;
                }
                String renamed = store.renameFile(from, to);
                if (renamed.isEmpty()) {
                    cb.onError("Impossible de renommer « " + from + " » → « " + to
                            + " » (existe déjà ou introuvable).");
                    return;
                }
                cb.onSuccess(ToolResult.text("Renommé : " + from + " → " + renamed));
                return;
            }

            case "delete_file":
            case "supprime":
            case "remove": {
                String file = firstNonEmpty(params, "file", "filename", "path", "name");
                if (file.isEmpty()) {
                    cb.onError("Indique le fichier à supprimer.");
                    return;
                }
                boolean confirmed = params != null && params.optBoolean("confirm", false);
                if (!confirmed) {
                    cb.onConfirmNeeded(
                            "Supprimer « " + file + " » du projet « "
                                    + store.getActiveProject() + " » ?",
                            () -> {
                                boolean ok = store.deleteFile(file);
                                cb.onSuccess(ToolResult.text(ok
                                        ? "Fichier « " + file + " » supprimé."
                                        : "Fichier introuvable : " + file));
                            },
                            () -> cb.onSuccess(ToolResult.text("Suppression annulée.")));
                    return;
                }
                boolean ok = store.deleteFile(file);
                cb.onSuccess(ToolResult.text(ok
                        ? "Fichier « " + file + " » supprimé."
                        : "Fichier introuvable : " + file));
                return;
            }

            case "push":
            case "push_project":
            case "commit": {
                if (!store.hasActiveProject()) {
                    cb.onError("Aucun projet actif.");
                    return;
                }
                List<String> only = parseFileFilter(params);
                List<OrionFileSession.OrionFile> files = store.toOrionFiles(
                        only.isEmpty() ? null : only);
                if (files.isEmpty()) {
                    cb.onError(only.isEmpty()
                            ? "Le projet « " + store.getActiveProject()
                            + " » est vide — rien à pusher."
                            : "Aucun des fichiers demandés dans le projet.");
                    return;
                }
                OrionFileStore session = OrionFileStore.get();
                session.newSession(store.getActiveProject());
                for (OrionFileSession.OrionFile f : files) {
                    session.addFileToSession(f.path, f.content);
                }
                session.validateAll();
                final List<String> pushedNames = new ArrayList<>();
                for (OrionFileSession.OrionFile f : files) {
                    pushedNames.add(f.path);
                }
                try {
                    JSONObject git = new JSONObject();
                    git.put("action", "commit");
                    git.put("session", true);
                    String msg = firstNonEmpty(params, "message", "commit_message", "msg");
                    if (msg.isEmpty()) {
                        msg = "feat(" + store.getActiveProject() + "): update";
                    }
                    git.put("message", msg);
                    if (params != null) {
                        String repo = params.optString("repo", "").trim();
                        if (!repo.isEmpty()) git.put("repo", repo);
                        if (params.has("confirm")) {
                            git.put("confirm", params.optBoolean("confirm"));
                        }
                    }
                    // Sans repo explicite → proposer la liste (création optionnelle en bas).
                    if (!git.has("repo") || git.optString("repo", "").trim().isEmpty()) {
                        git.put("new_repo", GitHubApiClient.sanitizeRepoName(
                                store.getActiveProject()));
                        if (params != null && params.has("private")) {
                            git.put("private", params.optBoolean("private"));
                        } else {
                            git.put("private", false);
                        }
                        // create_repo seulement si demandé explicitement
                        if (params != null && (params.optBoolean("create_repo", false)
                                || params.optBoolean("create", false))) {
                            git.put("create_repo", true);
                        }
                    }
                    new GitCommitTool().execute(ctx, git, new ToolCallback() {
                        @Override
                        public void onSuccess(ToolResult result) {
                            store.recordPushSnapshot(pushedNames);
                            cb.onSuccess(result);
                        }

                        @Override
                        public void onSuccessAndExit(ToolResult result) {
                            store.recordPushSnapshot(pushedNames);
                            cb.onSuccessAndExit(result);
                        }

                        @Override
                        public void onConfirmNeeded(String question, Runnable onConfirm,
                                Runnable onCancel) {
                            cb.onConfirmNeeded(question, onConfirm, onCancel);
                        }

                        @Override
                        public void onChoiceNeeded(String title, String[] labels,
                                java.util.function.IntConsumer onChosen, Runnable onCancel) {
                            cb.onChoiceNeeded(title, labels, onChosen, onCancel);
                        }

                        @Override
                        public void onError(String error) {
                            cb.onError(error);
                        }
                    });
                } catch (Exception e) {
                    cb.onError("Push projet : "
                            + (e.getMessage() == null ? "erreur" : e.getMessage()));
                }
                return;
            }

            case "make_public":
            case "public":
            case "set_public": {
                if (!store.hasActiveProject()) {
                    cb.onError("Aucun projet actif.");
                    return;
                }
                try {
                    JSONObject git = new JSONObject();
                    git.put("action", "make_public");
                    git.put("new_repo", GitHubApiClient.sanitizeRepoName(
                            store.getActiveProject()));
                    if (params != null) {
                        String repo = params.optString("repo", "").trim();
                        if (!repo.isEmpty()) git.put("repo", repo);
                        if (params.has("confirm")) {
                            git.put("confirm", params.optBoolean("confirm"));
                        }
                    }
                    new GitCommitTool().execute(ctx, git, cb);
                } catch (Exception e) {
                    cb.onError("Visibilité : "
                            + (e.getMessage() == null ? "erreur" : e.getMessage()));
                }
                return;
            }

            default:
                cb.onError("Action inconnue : " + action
                        + " (list, create, switch, files, delete_file, rename, push, make_public)");
        }
    }

    /** files:[…] ou file: seul → filtre push. */
    private static List<String> parseFileFilter(JSONObject params) {
        List<String> out = new ArrayList<>();
        if (params == null) return out;
        JSONArray arr = params.optJSONArray("files");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof String) {
                    String s = ((String) o).trim();
                    if (!s.isEmpty()) out.add(s);
                } else if (o instanceof JSONObject) {
                    String p = ((JSONObject) o).optString("path",
                            ((JSONObject) o).optString("file", "")).trim();
                    if (!p.isEmpty()) out.add(p);
                }
            }
        }
        String single = firstNonEmpty(params, "file", "filename", "path");
        if (!single.isEmpty() && !out.contains(single)) out.add(single);
        return out;
    }

    private static String resolveProjectName(OrionProjectStore store, String raw) {
        String want = OrionProjectStore.sanitizeProjectName(raw);
        if (want.isEmpty()) {
            want = raw.trim().toLowerCase(Locale.ROOT);
        }
        for (String p : store.listProjects()) {
            if (p.equalsIgnoreCase(want) || p.contains(want) || want.contains(p)) {
                return p;
            }
        }
        return null;
    }

    private static String firstNonEmpty(JSONObject params, String... keys) {
        if (params == null || keys == null) return "";
        for (String k : keys) {
            String v = params.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
