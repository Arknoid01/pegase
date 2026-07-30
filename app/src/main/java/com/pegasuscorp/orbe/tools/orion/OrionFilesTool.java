package com.pegasuscorp.orbe.tools.orion;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.OrionFileSession;
import com.pegasuscorp.orbe.orion.OrionFileStore;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Gestion de la session fichiers Orion : lister, valider, vider, committer.
 */
public final class OrionFilesTool implements Tool {

    @Override
    public String id() {
        return "orion_files";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.ORION_CODE;
    }

    @Override
    public String description() {
        return "orion_files(action:\"list\"|\"validate_all\"|\"validate\"|\"reject\""
                + "|\"clear\"|\"commit_all\"|\"suggest_message\", path?:str, message?:str) — "
                + "Session fichiers Orion (review). "
                + "« montre les fichiers Orion », « valide tout », « vide la session », "
                + "« committe tout » (délègue à git_commit session).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "list").trim().toLowerCase(Locale.ROOT)
                : "list";
        OrionFileStore store = OrionFileStore.get();

        switch (action) {
            case "list":
            case "show":
            case "status":
                cb.onSuccess(ToolResult.text(store.speakSummary()));
                return;

            case "validate_all":
            case "validate":
                if ("validate".equals(action) && params != null
                        && !TextUtils.isEmpty(params.optString("path", ""))) {
                    String path = params.optString("path", "").trim();
                    boolean ok = store.setStatus(path, OrionFileSession.FileStatus.VALIDATED);
                    cb.onSuccess(ToolResult.text(ok
                            ? "✅ " + path + " validé."
                            : "Fichier introuvable : " + path));
                    return;
                }
                if (!store.hasSession()) {
                    cb.onError("Pas de session Orion — génère d'abord du code.");
                    return;
                }
                store.validateAll();
                cb.onSuccess(ToolResult.text("Tous les fichiers en attente sont validés.\n"
                        + store.speakSummary()));
                return;

            case "reject":
                if (params == null || TextUtils.isEmpty(params.optString("path", ""))) {
                    cb.onError("Indique path pour rejeter un fichier.");
                    return;
                }
                String rej = params.optString("path", "").trim();
                boolean rok = store.setStatus(rej, OrionFileSession.FileStatus.REJECTED);
                cb.onSuccess(ToolResult.text(rok
                        ? "❌ " + rej + " rejeté."
                        : "Fichier introuvable : " + rej));
                return;

            case "clear":
            case "vide":
            case "empty":
                store.clearSession();
                cb.onSuccess(ToolResult.text("Session Orion vidée."));
                return;

            case "suggest_message":
            case "message":
                if (!store.hasSession()) {
                    cb.onError("Pas de session Orion.");
                    return;
                }
                String suggested = store.defaultCommitMessage();
                store.setSuggestedCommitMessage(suggested);
                cb.onSuccess(ToolResult.text("Message suggéré : " + suggested));
                return;

            case "commit_all":
            case "commit":
            case "push":
                if (!store.hasSession()) {
                    cb.onError("Pas de session Orion — génère d'abord du code.");
                    return;
                }
                List<OrionFileSession.OrionFile> ready = store.getReadyFiles();
                if (ready.isEmpty()) {
                    // Auto-valider si tout est encore PENDING
                    OrionFileSession session = store.getCurrentSession();
                    if (session != null && !session.getPendingFiles().isEmpty()
                            && session.getReadyFiles().isEmpty()) {
                        store.validateAll();
                        ready = store.getReadyFiles();
                    }
                }
                if (ready.isEmpty()) {
                    cb.onError("Aucun fichier validé — dis « valide tout » d'abord.");
                    return;
                }
                try {
                    JSONObject gitParams = new JSONObject();
                    gitParams.put("action", "commit");
                    gitParams.put("session", true);
                    if (params != null) {
                        String msg = params.optString("message", "").trim();
                        if (!msg.isEmpty()) gitParams.put("message", msg);
                        String repo = params.optString("repo", "").trim();
                        if (!repo.isEmpty()) gitParams.put("repo", repo);
                        if (params.has("confirm")) {
                            gitParams.put("confirm", params.optBoolean("confirm"));
                        }
                        if (params.has("deploy")) {
                            gitParams.put("deploy", params.optBoolean("deploy"));
                        }
                        if (params.has("create_repo")) {
                            gitParams.put("create_repo", params.optBoolean("create_repo"));
                        }
                        String newRepo = params.optString("new_repo", "").trim();
                        if (!newRepo.isEmpty()) gitParams.put("new_repo", newRepo);
                    }
                    new GitCommitTool().execute(ctx, gitParams, cb);
                } catch (Exception e) {
                    cb.onError("Commit session : "
                            + (e.getMessage() == null ? "erreur" : e.getMessage()));
                }
                return;

            default:
                cb.onError("Action inconnue : " + action
                        + " (list, validate_all, clear, commit_all, suggest_message)");
        }
    }
}
