package com.pegasuscorp.orbe.tools.orion;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.diag.CorrectionsStore;
import com.pegasuscorp.orbe.git.GitHubApiClient;
import com.pegasuscorp.orbe.git.HostingerApiClient;
import com.pegasuscorp.orbe.orion.GeneratedFiles;
import com.pegasuscorp.orbe.orion.OrionFileSession;
import com.pegasuscorp.orbe.orion.OrionFileStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Commit GitHub (Contents API) + déploiement Hostinger optionnel.
 * Toute écriture demande une validation explicite ({@link ToolCallback#onConfirmNeeded}).
 * Peut lire un fichier local ({@code local_file}) et proposer un dépôt (liste / créer).
 */
public final class GitCommitTool implements Tool {

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final String CREATE_REPO_LABEL = "Créer un nouveau dépôt…";
    private static final int MAX_REPO_CHOICES = 15;

    @Override
    public String id() {
        return "git_commit";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.GIT_COMMIT;
    }

    @Override
    public String description() {
        return "git_commit(action:\"commit\"|\"validate\"|\"make_public\", path?:str, content?:str, "
                + "local_file?:str, files?:[{path,content}], session?:bool, message?:str, "
                + "changes?:str|list, branch?:str, repo?:str, "
                + "create_repo?:bool, new_repo?:str, private?:bool, confirm?:bool, deploy?:bool) — "
                + "Commit 1 ou N fichiers sur GitHub (Git Trees si plusieurs). "
                + "session:true = fichiers VALIDATED de la session Orion. "
                + "Sans repo : propose dépôts ou création (public par défaut). "
                + "Même chemin de fichier = mise à jour (remplace l'existant). "
                + "private:true pour un dépôt privé. "
                + "action=make_public : passe un dépôt en public. "
                + "action=validate : vérifie GitHub / Hostinger. "
                + "Webhook Hostinger si configuré.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "commit").trim().toLowerCase(Locale.ROOT)
                : "commit";
        if ("validate".equals(action) || "check".equals(action) || "test".equals(action)) {
            BG.execute(() -> runValidate(ctx, cb));
            return;
        }
        if ("make_public".equals(action) || "public".equals(action)
                || "set_public".equals(action)) {
            BG.execute(() -> runMakePublic(ctx, params != null ? params : new JSONObject(), cb));
            return;
        }
        runCommitFlow(ctx, params != null ? params : new JSONObject(), cb);
    }

    private static void runMakePublic(Context ctx, JSONObject params, ToolCallback cb) {
        String token = ApiKeyStore.getGithubToken(ctx);
        if (TextUtils.isEmpty(token)) {
            cb.onError("Pas de token GitHub — ajoute-le dans Clés API.");
            return;
        }
        String repo = params.optString("repo", "").trim();
        if (repo.isEmpty()) {
            String newRepo = firstNonEmpty(params, "new_repo", "repo_name", "name");
            if (!newRepo.isEmpty()) {
                GitHubApiClient.Validation user = GitHubApiClient.validateToken(token);
                if (TextUtils.isEmpty(user.login)) {
                    cb.onError(user.message);
                    return;
                }
                repo = user.login + "/" + GitHubApiClient.sanitizeRepoName(newRepo);
            }
        }
        if (repo.isEmpty()) {
            repo = ApiKeyStore.getGithubRepo(ctx);
        }
        if (TextUtils.isEmpty(repo)) {
            cb.onError("Indique repo (owner/nom) ou new_repo.");
            return;
        }
        boolean confirmed = params.optBoolean("confirm", false);
        final String fRepo = repo;
        if (!confirmed) {
            cb.onConfirmNeeded(
                    "Passer le dépôt « " + fRepo + " » en public sur GitHub ?",
                    () -> BG.execute(() -> {
                        GitHubApiClient.Validation v =
                                GitHubApiClient.setRepoVisibility(token, fRepo, false);
                        if (v.ok) cb.onSuccess(ToolResult.text(v.message));
                        else cb.onError(v.message);
                    }),
                    () -> cb.onSuccess(ToolResult.text("Visibilité inchangée.")));
            return;
        }
        GitHubApiClient.Validation v = GitHubApiClient.setRepoVisibility(token, fRepo, false);
        if (v.ok) cb.onSuccess(ToolResult.text(v.message));
        else cb.onError(v.message);
    }

    private static void runValidate(Context ctx, ToolCallback cb) {
        StringBuilder sb = new StringBuilder();
        String ghToken = ApiKeyStore.getGithubToken(ctx);
        String repo = ApiKeyStore.getGithubRepo(ctx);
        if (TextUtils.isEmpty(ghToken)) {
            sb.append("GitHub : pas de token — ajoute-le dans Clés API.\n");
        } else if (TextUtils.isEmpty(repo)) {
            GitHubApiClient.Validation v = GitHubApiClient.validateToken(ghToken);
            sb.append(v.message).append('\n');
            sb.append("Repo : non configuré (owner/repo).\n");
        } else {
            GitHubApiClient.Validation v = GitHubApiClient.validateRepoAccess(ghToken, repo);
            sb.append(v.message).append('\n');
        }

        String hiToken = ApiKeyStore.getHostingerToken(ctx);
        if (TextUtils.isEmpty(hiToken)) {
            sb.append("Hostinger : pas de token.");
        } else {
            HostingerApiClient.Validation hv = HostingerApiClient.validateToken(hiToken);
            sb.append(hv.message);
            String hook = ApiKeyStore.getHostingerWebhook(ctx);
            if (!TextUtils.isEmpty(hook)) {
                sb.append("\nWebhook deploy : configuré.");
            } else {
                sb.append("\nWebhook deploy : absent (commit GitHub seulement).");
            }
        }
        cb.onSuccess(ToolResult.text(sb.toString().trim()));
    }

    private static void runCommitFlow(Context ctx, JSONObject params, ToolCallback cb) {
        if (TextUtils.isEmpty(ApiKeyStore.getGithubToken(ctx))) {
            cb.onError("Pas de token GitHub — ajoute-le dans Clés API.");
            return;
        }

        // --- Multi-fichiers : session Orion ou tableau files ---
        List<GitHubApiClient.FileChange> batch = resolveBatchFiles(ctx, params);
        if (batch != null) {
            if (batch.isEmpty()) {
                cb.onError("Aucun fichier à committer (valide la session ou fournis files).");
                return;
            }
            runMultiCommitFlow(ctx, params, batch, cb);
            return;
        }

        String path = firstNonEmpty(params, "path", "file", "filename");
        String localFile = firstNonEmpty(params, "local_file", "source_file", "generated");
        String content = params.optString("content", "");
        if (content.isEmpty()) content = params.optString("text", "");

        if (content.isEmpty() && localFile.isEmpty() && !path.isEmpty()) {
            File maybe = GeneratedFiles.findByName(ctx, path);
            if (maybe != null) {
                localFile = maybe.getName();
            }
        }

        if (content.isEmpty() && localFile.isEmpty() && path.isEmpty()) {
            // Préférer la session Orion s'il y en a une
            if (OrionFileStore.get().hasSession()) {
                try {
                    JSONObject next = new JSONObject(params.toString());
                    next.put("session", true);
                    runCommitFlow(ctx, next, cb);
                    return;
                } catch (Exception e) {
                    cb.onError("Session : " + msg(e));
                    return;
                }
            }
            List<File> recent = GeneratedFiles.listRecent(ctx);
            if (recent.isEmpty()) {
                cb.onError("Aucun fichier — génère avec Orion ou indique path / local_file.");
                return;
            }
            if (recent.size() == 1) {
                localFile = recent.get(0).getName();
            } else {
                String[] labels = new String[Math.min(recent.size(), 12)];
                List<File> slice = recent.subList(0, labels.length);
                for (int i = 0; i < slice.size(); i++) {
                    labels[i] = (i + 1) + ". " + slice.get(i).getName();
                }
                final List<File> picks = new ArrayList<>(slice);
                cb.onChoiceNeeded("Quel fichier pousser sur GitHub ?", labels,
                        index -> {
                            if (index < 0 || index >= picks.size()) {
                                cb.onError("Choix de fichier invalide.");
                                return;
                            }
                            try {
                                JSONObject next = new JSONObject(params.toString());
                                next.put("local_file", picks.get(index).getName());
                                runCommitFlow(ctx, next, cb);
                            } catch (Exception e) {
                                cb.onError("Fichier : " + msg(e));
                            }
                        },
                        () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
                return;
            }
        }

        if (content.isEmpty() && !localFile.isEmpty()) {
            File f = GeneratedFiles.findByName(ctx, localFile);
            if (f == null) {
                cb.onError("Fichier introuvable dans cache/generated : " + localFile);
                return;
            }
            try {
                content = GeneratedFiles.readUtf8(f);
            } catch (Exception e) {
                cb.onError("Lecture impossible : " + msg(e));
                return;
            }
            if (path.isEmpty()) path = f.getName();
        }

        if (TextUtils.isEmpty(path)) {
            cb.onError("Indique le chemin du fichier à committer (path) ou local_file.");
            return;
        }
        if (content == null) content = "";

        List<String> changes = parseChanges(params);
        String message = firstNonEmpty(params, "message", "commit_message", "msg");
        if (TextUtils.isEmpty(message)) {
            message = defaultCommitMessage(path, changes);
        }
        String branch = params.optString("branch", "").trim();
        if (branch.isEmpty()) branch = ApiKeyStore.getGithubBranch(ctx);
        boolean confirmed = params.optBoolean("confirm", false);
        boolean deploy = !params.has("deploy") || params.optBoolean("deploy", true);
        String newRepo = firstNonEmpty(params, "new_repo", "repo_name", "create_repo_name");
        String repo = params.optString("repo", "").trim();
        boolean createRepo = params.optBoolean("create_repo", false)
                || params.optBoolean("create", false);

        // Param repo explicite → pas de choix. Sinon si create_repo+nom → créer. Sinon choisir.
        if (!repo.isEmpty()) {
            proceedToConfirm(ctx, cb, repo, branch, path, content, message, changes,
                    confirmed, deploy, false);
            return;
        }

        if (createRepo) {
            if (newRepo.isEmpty()) {
                newRepo = GitHubApiClient.sanitizeRepoName(path);
            }
            if (newRepo.isEmpty()) {
                cb.onError("Indique new_repo (nom du dépôt à créer).");
                return;
            }
            // public par défaut ; private:true pour garder privé
            final boolean fPrivate = params.optBoolean("private", false);
            final String fPath = path;
            final String fContent = content;
            final String fMessage = message;
            final String fBranch = branch;
            final boolean fDeploy = deploy;
            final List<String> fChanges = changes;
            final String fNewRepo = newRepo;
            if (!confirmed) {
                String vis = fPrivate ? "privé" : "public";
                String q = buildConfirmQuestion(fPath, fChanges, fMessage, fDeploy, ctx)
                        + "\n📦 Nouveau dépôt : " + fNewRepo + " (" + vis + ")";
                cb.onConfirmNeeded(q,
                        () -> BG.execute(() -> createThenCommit(ctx, fNewRepo, fBranch, fPath,
                                fContent, fMessage, fDeploy, fPrivate, cb)),
                        () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
                return;
            }
            BG.execute(() -> createThenCommit(ctx, fNewRepo, fBranch, fPath, fContent,
                    fMessage, fDeploy, fPrivate, cb));
            return;
        }

        // Pas de repo : lister + choix (défaut Clés API inclus)
        final String fPath = path;
        final String fContent = content;
        final String fMessage = message;
        final String fBranch = branch;
        final boolean fDeploy = deploy;
        final boolean fConfirmed = confirmed;
        final List<String> fChanges = changes;
        final String suggestedName = GitHubApiClient.sanitizeRepoName(
                !newRepo.isEmpty() ? newRepo : path);

        BG.execute(() -> {
            String token = ApiKeyStore.getGithubToken(ctx);
            GitHubApiClient.ListReposResult listed =
                    GitHubApiClient.listRepos(token, MAX_REPO_CHOICES);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            String def = ApiKeyStore.getGithubRepo(ctx);
            if (!TextUtils.isEmpty(def)) names.add(def.trim());
            if (listed.ok) {
                for (GitHubApiClient.RepoInfo info : listed.repos) {
                    if (info != null && !TextUtils.isEmpty(info.fullName)) {
                        names.add(info.fullName);
                    }
                }
            }
            List<String> options = orderRepoChoices(names, suggestedName, def);
            options.add(CREATE_REPO_LABEL);

            String[] labels = new String[options.size()];
            for (int i = 0; i < options.size(); i++) {
                String n = options.get(i);
                if (CREATE_REPO_LABEL.equals(n)) {
                    labels[i] = (i + 1) + ". " + n
                            + (suggestedName.isEmpty() ? "" : " (« " + suggestedName + " »)");
                } else {
                    String mark = n.equals(def) ? " (défaut)" : "";
                    if (!suggestedName.isEmpty()
                            && n.toLowerCase(Locale.ROOT).endsWith("/" + suggestedName)) {
                        mark = " (projet)";
                    }
                    labels[i] = (i + 1) + ". " + n + mark;
                }
            }
            final List<String> fOptions = options;
            final String fSuggested = suggestedName;
            final boolean fPrivate = params.optBoolean("private", false);

            // onChoiceNeeded doit être sur le thread UI — ToolCallback wrappers le font en général
            cb.onChoiceNeeded("Sur quel dépôt GitHub pousser ?", labels,
                    index -> {
                        if (index < 0 || index >= fOptions.size()) {
                            cb.onError("Choix de dépôt invalide.");
                            return;
                        }
                        String pick = fOptions.get(index);
                        if (CREATE_REPO_LABEL.equals(pick)) {
                            if (fSuggested.isEmpty()) {
                                cb.onError("Impossible de dériver un nom de dépôt — "
                                        + "indique new_repo ou repo.");
                                return;
                            }
                            try {
                                JSONObject next = new JSONObject();
                                next.put("path", fPath);
                                next.put("content", fContent);
                                next.put("message", fMessage);
                                next.put("branch", fBranch);
                                next.put("deploy", fDeploy);
                                next.put("create_repo", true);
                                next.put("new_repo", fSuggested);
                                next.put("private", fPrivate);
                                if (fConfirmed) next.put("confirm", true);
                                JSONArray ch = new JSONArray();
                                for (String c : fChanges) ch.put(c);
                                if (ch.length() > 0) next.put("changes", ch);
                                runCommitFlow(ctx, next, cb);
                            } catch (Exception e) {
                                cb.onError("Création dépôt : " + msg(e));
                            }
                            return;
                        }
                        proceedToConfirm(ctx, cb, pick, fBranch, fPath, fContent, fMessage,
                                fChanges, fConfirmed, fDeploy, false);
                    },
                    () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
        });
    }

    private static void proceedToConfirm(Context ctx, ToolCallback cb, String repo,
            String branch, String path, String content, String message, List<String> changes,
            boolean confirmed, boolean deploy, boolean creating) {
        final String fPath = path;
        final String fContent = content;
        final String fMessage = message;
        final String fBranch = branch;
        final String fRepo = repo;
        final boolean fDeploy = deploy;
        final List<String> fChanges = changes;

        if (!confirmed) {
            String q = buildConfirmQuestion(fPath, fChanges, fMessage, fDeploy, ctx);
            q = q.replace("Je vais committer :",
                    "Je vais committer sur " + fRepo + " :");
            if (creating) {
                q = q + "\n📦 Nouveau dépôt";
            }
            cb.onConfirmNeeded(q,
                    () -> BG.execute(() -> doCommit(ctx, fRepo, fBranch, fPath, fContent,
                            fMessage, fDeploy, cb)),
                    () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
            return;
        }
        BG.execute(() -> doCommit(ctx, fRepo, fBranch, fPath, fContent, fMessage, fDeploy, cb));
    }

    private static void createThenCommit(Context ctx, String newRepoName, String branch,
            String path, String content, String message, boolean deploy, boolean isPrivate,
            ToolCallback cb) {
        try {
            String token = ApiKeyStore.getGithubToken(ctx);
            GitHubApiClient.CreateRepoResult created = GitHubApiClient.createRepo(
                    token, newRepoName, isPrivate);
            if (!created.ok) {
                cb.onError(created.message);
                return;
            }
            if (!isPrivate && !TextUtils.isEmpty(created.fullName)) {
                // Si le dépôt existait déjà en privé → forcer public.
                GitHubApiClient.setRepoVisibility(token, created.fullName, false);
            }
            // Petite pause : GitHub peut mettre un instant à exposer le contenu initial
            try {
                Thread.sleep(800);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            doCommit(ctx, created.fullName, branch, path, content, message, deploy, cb);
        } catch (Exception e) {
            cb.onError("Création dépôt : " + msg(e));
        }
    }

    /** Confirmation enrichie : fichier + changements + message. */
    public static String buildConfirmQuestion(String path, List<String> changes, String message,
            boolean deploy, Context ctx) {
        StringBuilder q = new StringBuilder();
        q.append("Je vais committer :\n");
        q.append("📁 ").append(displayFileName(path)).append('\n');
        q.append("(même nom = remplace le fichier existant sur GitHub)\n");
        q.append("✏️ ");
        if (changes == null || changes.isEmpty()) {
            q.append("(pas de détail de changements)\n");
        } else {
            boolean first = true;
            for (String c : changes) {
                if (c == null || c.trim().isEmpty()) continue;
                if (!first) q.append("\n   ");
                first = false;
                String line = c.trim();
                if (!line.startsWith("-")) line = "- " + line;
                q.append(line);
            }
            q.append('\n');
        }
        q.append("💬 ").append(message == null || message.isEmpty() ? "(auto)" : message).append('\n');
        if (deploy && ctx != null && !TextUtils.isEmpty(ApiKeyStore.getHostingerWebhook(ctx))) {
            q.append("Puis déploiement Hostinger.\n");
        }
        q.append("C'est bon ?");
        return q.toString();
    }

    static List<String> parseChanges(JSONObject params) {
        List<String> out = new ArrayList<>();
        if (params == null) return out;
        JSONArray arr = params.optJSONArray("changes");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        String raw = firstNonEmpty(params, "changes", "changelog", "change_list", "diff_summary");
        if (raw.isEmpty()) return out;
        for (String line : raw.split("[\n;|]")) {
            String t = line.trim();
            if (t.startsWith("-")) t = t.substring(1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static String defaultCommitMessage(String path, List<String> changes) {
        String scope = scopeFromPath(path);
        String desc = "mise à jour";
        if (changes != null && !changes.isEmpty()) {
            String first = changes.get(0).trim();
            if (first.length() > 72) first = first.substring(0, 69) + "…";
            desc = first.substring(0, 1).toLowerCase(Locale.ROOT) + first.substring(1);
        }
        return "fix(" + scope + "): " + desc;
    }

    static String scopeFromPath(String path) {
        if (path == null || path.isEmpty()) return "orbe";
        String name = displayFileName(path);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("(?i)tool$", "").replaceAll("(?i)helper$", "");
        if (name.isEmpty()) return "orbe";
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }

    static String displayFileName(String path) {
        if (path == null || path.isEmpty()) return "(fichier)";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static void doCommit(Context ctx, String repo, String branch, String path,
            String content, String message, boolean deploy, ToolCallback cb) {
        try {
            GitHubApiClient.CommitResult result = GitHubApiClient.commitFile(
                    ApiKeyStore.getGithubToken(ctx), repo, branch, path, content, message);
            if (!result.ok) {
                cb.onError(result.message);
                return;
            }
            StringBuilder out = new StringBuilder(result.message);
            if (!TextUtils.isEmpty(result.htmlUrl)) {
                out.append("\n").append(result.htmlUrl);
            }
            if (deploy) {
                String hook = ApiKeyStore.getHostingerWebhook(ctx);
                if (!TextUtils.isEmpty(hook)) {
                    HostingerApiClient.Validation d =
                            HostingerApiClient.triggerDeployWebhook(hook);
                    out.append('\n').append(d.message);
                }
            }
            afterSuccessfulCommit(ctx, message, out);
            cb.onSuccess(ToolResult.text(out.toString().trim()));
        } catch (Exception e) {
            cb.onError("Commit : " + (e.getMessage() == null ? "erreur" : e.getMessage()));
        }
    }

    /**
     * @return null si mode mono-fichier classique ; liste (éventuellement vide) si batch.
     */
    private static List<GitHubApiClient.FileChange> resolveBatchFiles(
            Context ctx, JSONObject params) {
        boolean session = params.optBoolean("session", false)
                || params.optBoolean("commit_all", false)
                || "session".equalsIgnoreCase(params.optString("source", ""));
        JSONArray arr = params.optJSONArray("files");
        if (!session && arr == null) return null;

        List<GitHubApiClient.FileChange> out = new ArrayList<>();
        if (session) {
            OrionFileStore store = OrionFileStore.get();
            List<OrionFileSession.OrionFile> ready = store.getReadyFiles();
            if (ready.isEmpty() && store.hasSession()) {
                OrionFileSession s = store.getCurrentSession();
                if (s != null && !s.getPendingFiles().isEmpty()) {
                    store.validateAll();
                    ready = store.getReadyFiles();
                }
            }
            for (OrionFileSession.OrionFile f : ready) {
                out.add(new GitHubApiClient.FileChange(f.path, f.content));
            }
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String p = o.optString("path", o.optString("file", "")).trim();
            String c = o.optString("content", o.optString("text", ""));
            if (p.isEmpty()) continue;
            if (c.isEmpty()) {
                File local = GeneratedFiles.findByName(ctx, p);
                if (local != null) {
                    try {
                        c = GeneratedFiles.readUtf8(local);
                    } catch (Exception ignored) {
                    }
                }
            }
            out.add(new GitHubApiClient.FileChange(p, c));
        }
        return out;
    }

    private static void runMultiCommitFlow(Context ctx, JSONObject params,
            List<GitHubApiClient.FileChange> files, ToolCallback cb) {
        List<String> changes = parseChanges(params);
        if (changes.isEmpty()) {
            for (GitHubApiClient.FileChange f : files) {
                changes.add(f.path);
            }
        }
        String message = firstNonEmpty(params, "message", "commit_message", "msg");
        if (TextUtils.isEmpty(message)) {
            if (params.optBoolean("session", false) || OrionFileStore.get().hasSession()) {
                message = OrionFileStore.get().defaultCommitMessage();
            } else {
                message = "feat: " + files.size() + " fichiers";
            }
        }
        String branch = params.optString("branch", "").trim();
        if (branch.isEmpty()) branch = ApiKeyStore.getGithubBranch(ctx);
        boolean confirmed = params.optBoolean("confirm", false);
        boolean deploy = !params.has("deploy") || params.optBoolean("deploy", true);
        String newRepo = firstNonEmpty(params, "new_repo", "repo_name", "create_repo_name");
        String repo = params.optString("repo", "").trim();
        // create_repo explicite seulement — sinon toujours proposer la liste.
        boolean createRepo = params.optBoolean("create_repo", false)
                || params.optBoolean("create", false);

        final List<GitHubApiClient.FileChange> fFiles = files;
        final String fMessage = message;
        final String fBranch = branch;
        final boolean fDeploy = deploy;
        final List<String> fChanges = changes;

        if (!repo.isEmpty()) {
            proceedMultiConfirm(ctx, cb, repo, fBranch, fFiles, fMessage, fChanges,
                    confirmed, fDeploy);
            return;
        }
        if (createRepo) {
            if (newRepo.isEmpty()) {
                newRepo = GitHubApiClient.sanitizeRepoName(files.get(0).path);
            }
            if (newRepo.isEmpty()) {
                cb.onError("Indique new_repo.");
                return;
            }
            final boolean fPrivate = params.optBoolean("private", false);
            final String fNew = newRepo;
            if (!confirmed) {
                String vis = fPrivate ? "privé" : "public";
                String q = buildMultiConfirmQuestion(fFiles, fChanges, fMessage, fDeploy, ctx)
                        + "\n📦 Nouveau dépôt : " + fNew + " (" + vis + ")";
                cb.onConfirmNeeded(q,
                        () -> BG.execute(() -> createThenCommitMulti(ctx, fNew, fBranch,
                                fFiles, fMessage, fDeploy, fPrivate, cb)),
                        () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
                return;
            }
            BG.execute(() -> createThenCommitMulti(ctx, fNew, fBranch, fFiles, fMessage,
                    fDeploy, fPrivate, cb));
            return;
        }

        final boolean fConfirmed = confirmed;
        final String suggestedName = GitHubApiClient.sanitizeRepoName(
                !newRepo.isEmpty() ? newRepo : files.get(0).path);
        BG.execute(() -> {
            String token = ApiKeyStore.getGithubToken(ctx);
            GitHubApiClient.ListReposResult listed =
                    GitHubApiClient.listRepos(token, MAX_REPO_CHOICES);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            String def = ApiKeyStore.getGithubRepo(ctx);
            if (!TextUtils.isEmpty(def)) names.add(def.trim());
            if (listed.ok) {
                for (GitHubApiClient.RepoInfo info : listed.repos) {
                    if (info != null && !TextUtils.isEmpty(info.fullName)) {
                        names.add(info.fullName);
                    }
                }
            }
            List<String> options = orderRepoChoices(names, suggestedName, def);
            options.add(CREATE_REPO_LABEL);
            String[] labels = new String[options.size()];
            for (int i = 0; i < options.size(); i++) {
                String n = options.get(i);
                if (CREATE_REPO_LABEL.equals(n)) {
                    labels[i] = (i + 1) + ". " + n
                            + (suggestedName.isEmpty() ? "" : " (« " + suggestedName + " »)");
                } else {
                    String mark = n.equals(def) ? " (défaut)" : "";
                    if (!suggestedName.isEmpty()
                            && n.toLowerCase(Locale.ROOT).endsWith("/" + suggestedName)) {
                        mark = " (projet)";
                    }
                    labels[i] = (i + 1) + ". " + n + mark;
                }
            }
            final List<String> fOptions = options;
            final String fSuggested = suggestedName;
            final boolean fPrivate = params.optBoolean("private", false);
            cb.onChoiceNeeded("Sur quel dépôt pousser " + fFiles.size() + " fichier(s) ?",
                    labels,
                    index -> {
                        if (index < 0 || index >= fOptions.size()) {
                            cb.onError("Choix de dépôt invalide.");
                            return;
                        }
                        String pick = fOptions.get(index);
                        if (CREATE_REPO_LABEL.equals(pick)) {
                            if (fSuggested.isEmpty()) {
                                cb.onError("Impossible de dériver un nom de dépôt.");
                                return;
                            }
                            try {
                                JSONObject next = new JSONObject();
                                next.put("session", params.optBoolean("session", false));
                                next.put("message", fMessage);
                                next.put("branch", fBranch);
                                next.put("deploy", fDeploy);
                                next.put("create_repo", true);
                                next.put("new_repo", fSuggested);
                                next.put("private", fPrivate);
                                if (fConfirmed) next.put("confirm", true);
                                JSONArray filesArr = new JSONArray();
                                for (GitHubApiClient.FileChange fc : fFiles) {
                                    filesArr.put(new JSONObject()
                                            .put("path", fc.path)
                                            .put("content", fc.content));
                                }
                                next.put("files", filesArr);
                                runCommitFlow(ctx, next, cb);
                            } catch (Exception e) {
                                cb.onError("Création dépôt : " + msg(e));
                            }
                            return;
                        }
                        proceedMultiConfirm(ctx, cb, pick, fBranch, fFiles, fMessage,
                                fChanges, fConfirmed, fDeploy);
                    },
                    () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
        });
    }

    private static void proceedMultiConfirm(Context ctx, ToolCallback cb, String repo,
            String branch, List<GitHubApiClient.FileChange> files, String message,
            List<String> changes, boolean confirmed, boolean deploy) {
        if (!confirmed) {
            String q = buildMultiConfirmQuestion(files, changes, message, deploy, ctx);
            q = q.replace("Je vais committer :",
                    "Je vais committer sur " + repo + " :");
            cb.onConfirmNeeded(q,
                    () -> BG.execute(() -> doCommitMulti(ctx, repo, branch, files,
                            message, deploy, cb)),
                    () -> cb.onSuccess(ToolResult.text("Commit annulé.")));
            return;
        }
        BG.execute(() -> doCommitMulti(ctx, repo, branch, files, message, deploy, cb));
    }

    static String buildMultiConfirmQuestion(List<GitHubApiClient.FileChange> files,
            List<String> changes, String message, boolean deploy, Context ctx) {
        StringBuilder q = new StringBuilder();
        q.append("Je vais committer :\n");
        for (GitHubApiClient.FileChange f : files) {
            q.append("✅ ").append(f.path).append('\n');
        }
        q.append("(même nom = remplace le fichier existant sur GitHub)\n");
        q.append("💬 ").append(message == null || message.isEmpty() ? "(auto)" : message)
                .append('\n');
        if (deploy && ctx != null && !TextUtils.isEmpty(ApiKeyStore.getHostingerWebhook(ctx))) {
            q.append("Puis déploiement Hostinger.\n");
        }
        q.append("C'est bon ?");
        return q.toString();
    }

    /**
     * Place le dépôt du projet en tête s'il existe, puis le défaut, puis le reste.
     */
    private static List<String> orderRepoChoices(LinkedHashSet<String> names,
            String suggestedName, String def) {
        List<String> ordered = new ArrayList<>();
        String projectMatch = null;
        if (!TextUtils.isEmpty(suggestedName)) {
            String needle = "/" + suggestedName.toLowerCase(Locale.ROOT);
            for (String n : names) {
                if (n != null && n.toLowerCase(Locale.ROOT).endsWith(needle)) {
                    projectMatch = n;
                    break;
                }
            }
        }
        if (projectMatch != null) ordered.add(projectMatch);
        if (!TextUtils.isEmpty(def) && !ordered.contains(def.trim())) {
            ordered.add(def.trim());
        }
        for (String n : names) {
            if (n != null && !ordered.contains(n)) ordered.add(n);
        }
        if (ordered.size() > MAX_REPO_CHOICES) {
            return new ArrayList<>(ordered.subList(0, MAX_REPO_CHOICES));
        }
        return ordered;
    }

    private static void createThenCommitMulti(Context ctx, String newRepoName, String branch,
            List<GitHubApiClient.FileChange> files, String message, boolean deploy,
            boolean isPrivate, ToolCallback cb) {
        try {
            String token = ApiKeyStore.getGithubToken(ctx);
            GitHubApiClient.CreateRepoResult created = GitHubApiClient.createRepo(
                    token, newRepoName, isPrivate);
            if (!created.ok) {
                cb.onError(created.message);
                return;
            }
            if (!isPrivate && !TextUtils.isEmpty(created.fullName)) {
                GitHubApiClient.setRepoVisibility(token, created.fullName, false);
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            doCommitMulti(ctx, created.fullName, branch, files, message, deploy, cb);
        } catch (Exception e) {
            cb.onError("Création dépôt : " + msg(e));
        }
    }

    private static void doCommitMulti(Context ctx, String repo, String branch,
            List<GitHubApiClient.FileChange> files, String message, boolean deploy,
            ToolCallback cb) {
        try {
            GitHubApiClient.CommitResult result;
            if (files.size() == 1) {
                result = GitHubApiClient.commitFile(
                        ApiKeyStore.getGithubToken(ctx), repo, branch,
                        files.get(0).path, files.get(0).content, message);
            } else {
                result = GitHubApiClient.commitFiles(
                        ApiKeyStore.getGithubToken(ctx), repo, branch, files, message);
            }
            if (!result.ok) {
                cb.onError(result.message);
                return;
            }
            StringBuilder out = new StringBuilder(result.message);
            if (!TextUtils.isEmpty(result.htmlUrl)) {
                out.append("\n").append(result.htmlUrl);
            }
            if (deploy) {
                String hook = ApiKeyStore.getHostingerWebhook(ctx);
                if (!TextUtils.isEmpty(hook)) {
                    HostingerApiClient.Validation d =
                            HostingerApiClient.triggerDeployWebhook(hook);
                    out.append('\n').append(d.message);
                }
            }
            OrionFileStore.get().recordCommit(message);
            afterSuccessfulCommit(ctx, message, out);
            cb.onSuccess(ToolResult.text(out.toString().trim()));
        } catch (Exception e) {
            cb.onError("Commit multi : "
                    + (e.getMessage() == null ? "erreur" : e.getMessage()));
        }
    }

    /** Coche corrections.md si le message ressemble à un fix. */
    private static void afterSuccessfulCommit(Context ctx, String message, StringBuilder out) {
        if (ctx == null || TextUtils.isEmpty(message)) return;
        String lower = message.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("fix") || lower.contains("fix(")
                || lower.contains("correction"))) {
            return;
        }
        try {
            String spoken = CorrectionsStore.markDone(ctx, message);
            if (spoken != null && spoken.contains("C'est noté")) {
                out.append('\n').append(spoken);
            }
        } catch (Exception ignored) {
        }
    }

    private static String firstNonEmpty(JSONObject params, String... keys) {
        if (params == null || keys == null) return "";
        for (String k : keys) {
            String v = params.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
