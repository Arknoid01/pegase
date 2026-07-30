package com.pegasuscorp.orbe.git;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Client GitHub Contents API — validation token + commit de fichier.
 */
public final class GitHubApiClient {

    private static final String TAG = "GitHubApi";
    private static final String API = "https://api.github.com";
    private static final int TIMEOUT_MS = 20_000;

    public static final class Validation {
        public final boolean ok;
        public final String login;
        public final String message;

        public Validation(boolean ok, String login, String message) {
            this.ok = ok;
            this.login = login == null ? "" : login;
            this.message = message == null ? "" : message;
        }
    }

    public static final class CommitResult {
        public final boolean ok;
        public final String htmlUrl;
        public final String message;

        public CommitResult(boolean ok, String htmlUrl, String message) {
            this.ok = ok;
            this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
            this.message = message == null ? "" : message;
        }
    }

    public static final class RepoInfo {
        public final String fullName;
        public final boolean isPrivate;

        public RepoInfo(String fullName, boolean isPrivate) {
            this.fullName = fullName == null ? "" : fullName;
            this.isPrivate = isPrivate;
        }
    }

    public static final class ListReposResult {
        public final boolean ok;
        public final List<RepoInfo> repos;
        public final String message;

        public ListReposResult(boolean ok, List<RepoInfo> repos, String message) {
            this.ok = ok;
            this.repos = repos != null ? repos : Collections.emptyList();
            this.message = message == null ? "" : message;
        }
    }

    public static final class CreateRepoResult {
        public final boolean ok;
        public final String fullName;
        public final String htmlUrl;
        public final String message;

        public CreateRepoResult(boolean ok, String fullName, String htmlUrl, String message) {
            this.ok = ok;
            this.fullName = fullName == null ? "" : fullName;
            this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
            this.message = message == null ? "" : message;
        }
    }

    private GitHubApiClient() {}

    public static Validation validateToken(String token) {
        if (TextUtils.isEmpty(token)) {
            return new Validation(false, "", "Token GitHub vide.");
        }
        try {
            HttpResult r = request("GET", API + "/user", token, null);
            if (r.code == 200) {
                JSONObject o = new JSONObject(r.body);
                String login = o.optString("login", "");
                return new Validation(true, login,
                        "GitHub OK — connecté en tant que " + login + ".");
            }
            if (r.code == 401 || r.code == 403) {
                return new Validation(false, "", "Token GitHub refusé (HTTP " + r.code + ").");
            }
            return new Validation(false, "", "GitHub HTTP " + r.code + " : " + clip(r.body, 120));
        } catch (Exception e) {
            Log.w(TAG, "validateToken", e);
            return new Validation(false, "", "Validation GitHub impossible : " + msg(e));
        }
    }

    public static Validation validateRepoAccess(String token, String ownerRepo) {
        ParsedRepo repo = ParsedRepo.parse(ownerRepo);
        if (!repo.valid) {
            return new Validation(false, "", "Repo invalide — format attendu : owner/repo.");
        }
        Validation user = validateToken(token);
        if (!user.ok) return user;
        try {
            HttpResult r = request("GET",
                    API + "/repos/" + repo.owner + "/" + repo.name, token, null);
            if (r.code == 200) {
                return new Validation(true, user.login,
                        "Accès OK à " + repo.owner + "/" + repo.name + ".");
            }
            if (r.code == 404) {
                return new Validation(false, user.login,
                        "Repo introuvable ou sans droit : " + ownerRepo);
            }
            return new Validation(false, user.login,
                    "Repo HTTP " + r.code + " : " + clip(r.body, 120));
        } catch (Exception e) {
            return new Validation(false, user.login, "Vérif repo : " + msg(e));
        }
    }

    /**
     * Liste les dépôts de l'utilisateur (les plus récemment mis à jour).
     */
    public static ListReposResult listRepos(String token, int limit) {
        if (TextUtils.isEmpty(token)) {
            return new ListReposResult(false, Collections.emptyList(), "Token GitHub manquant.");
        }
        int perPage = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 30));
        try {
            String url = API + "/user/repos?sort=updated&direction=desc&per_page=" + perPage;
            HttpResult r = request("GET", url, token, null);
            if (r.code != 200) {
                return new ListReposResult(false, Collections.emptyList(),
                        "Liste repos HTTP " + r.code + " : " + clip(r.body, 160));
            }
            JSONArray arr = new JSONArray(r.body);
            List<RepoInfo> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String full = o.optString("full_name", "").trim();
                if (full.isEmpty()) continue;
                out.add(new RepoInfo(full, o.optBoolean("private", false)));
            }
            return new ListReposResult(true, out, "OK — " + out.size() + " dépôt(s).");
        } catch (Exception e) {
            Log.w(TAG, "listRepos", e);
            return new ListReposResult(false, Collections.emptyList(),
                    "Liste repos impossible : " + msg(e));
        }
    }

    /**
     * Crée un dépôt utilisateur via {@code POST /user/repos}.
     * @return full_name {@code owner/repo} si OK
     */
    public static CreateRepoResult createRepo(String token, String name, boolean isPrivate) {
        if (TextUtils.isEmpty(token)) {
            return new CreateRepoResult(false, "", "", "Token GitHub manquant.");
        }
        String repoName = sanitizeRepoName(name);
        if (repoName.isEmpty()) {
            return new CreateRepoResult(false, "", "",
                    "Nom de dépôt invalide — lettres, chiffres, - et _.");
        }
        try {
            JSONObject body = new JSONObject()
                    .put("name", repoName)
                    .put("private", isPrivate)
                    .put("auto_init", true);
            HttpResult r = request("POST", API + "/user/repos", token, body.toString());
            if (r.code == 201) {
                JSONObject o = new JSONObject(r.body);
                String full = o.optString("full_name", "").trim();
                String html = o.optString("html_url", "");
                if (full.isEmpty()) {
                    Validation user = validateToken(token);
                    full = user.login.isEmpty() ? repoName : (user.login + "/" + repoName);
                }
                return new CreateRepoResult(true, full, html,
                        "Dépôt créé : " + full);
            }
            if (r.code == 422) {
                // Déjà existant → réutiliser owner/name pour le commit.
                Validation user = validateToken(token);
                if (!TextUtils.isEmpty(user.login)) {
                    String full = user.login + "/" + repoName;
                    Validation access = validateRepoAccess(token, full);
                    if (access.ok) {
                        return new CreateRepoResult(true, full, "",
                                "Dépôt déjà existant : " + full);
                    }
                }
                return new CreateRepoResult(false, "", "",
                        "Impossible de créer « " + repoName
                                + " » (déjà existant ailleurs ou invalide).");
            }
            if (r.code == 401 || r.code == 403 || r.code == 404) {
                return new CreateRepoResult(false, "", "",
                        "Création repo refusée (HTTP " + r.code
                                + ") — vérifie le token GitHub (scope « repo »). "
                                + clip(r.body, 120));
            }
            return new CreateRepoResult(false, "", "",
                    "Création repo HTTP " + r.code + " : " + clip(r.body, 180));
        } catch (Exception e) {
            Log.w(TAG, "createRepo", e);
            return new CreateRepoResult(false, "", "", "Création repo : " + msg(e));
        }
    }

    /**
     * Change la visibilité d'un dépôt ({@code PATCH /repos/{owner}/{repo}}).
     * @param isPrivate true = privé, false = public
     */
    public static Validation setRepoVisibility(String token, String ownerRepo, boolean isPrivate) {
        ParsedRepo repo = ParsedRepo.parse(ownerRepo);
        if (!repo.valid) {
            return new Validation(false, "", "Repo invalide — owner/repo.");
        }
        if (TextUtils.isEmpty(token)) {
            return new Validation(false, "", "Token GitHub manquant.");
        }
        try {
            JSONObject body = new JSONObject().put("private", isPrivate);
            HttpResult r = request("PATCH",
                    API + "/repos/" + repo.owner + "/" + repo.name,
                    token, body.toString());
            if (r.code == 200) {
                return new Validation(true, repo.owner,
                        isPrivate
                                ? ("Dépôt passé en privé : " + repo.owner + "/" + repo.name)
                                : ("Dépôt passé en public : " + repo.owner + "/" + repo.name
                                        + "\nhttps://github.com/" + repo.owner + "/" + repo.name));
            }
            if (r.code == 401 || r.code == 403 || r.code == 404) {
                return new Validation(false, "",
                        "Visibilité refusée (HTTP " + r.code
                                + ") — token avec scope « repo » requis. "
                                + clip(r.body, 120));
            }
            return new Validation(false, "",
                    "Visibilité HTTP " + r.code + " : " + clip(r.body, 160));
        } catch (Exception e) {
            Log.w(TAG, "setRepoVisibility", e);
            return new Validation(false, "", "Visibilité : " + msg(e));
        }
    }

    /** Nom de dépôt GitHub sûr (slug). */
    public static String sanitizeRepoName(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        t = t.replaceAll("^-+|-+$", "");
        if (t.length() > 100) t = t.substring(0, 100);
        return t;
    }

    /**
     * Crée ou met à jour un fichier via Contents API (un commit).
     */
    public static CommitResult commitFile(String token, String ownerRepo, String branch,
            String path, String content, String message) {
        ParsedRepo repo = ParsedRepo.parse(ownerRepo);
        if (!repo.valid) {
            return new CommitResult(false, "", "Repo invalide — owner/repo.");
        }
        if (TextUtils.isEmpty(token)) {
            return new CommitResult(false, "", "Token GitHub manquant.");
        }
        String cleanPath = sanitizePath(path);
        if (cleanPath.isEmpty()) {
            return new CommitResult(false, "", "Chemin de fichier vide.");
        }
        String msg = TextUtils.isEmpty(message) ? ("Orbe : update " + cleanPath) : message.trim();
        String br = TextUtils.isEmpty(branch) ? "main" : branch.trim();

        try {
            String sha = fetchFileSha(token, repo, cleanPath, br);
            JSONObject body = new JSONObject()
                    .put("message", msg)
                    .put("content", Base64.encodeToString(
                            content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .put("branch", br);
            if (!TextUtils.isEmpty(sha)) {
                body.put("sha", sha);
            }

            String url = API + "/repos/" + repo.owner + "/" + repo.name
                    + "/contents/" + cleanPath;
            HttpResult r = request("PUT", url, token, body.toString());
            if (r.code == 200 || r.code == 201) {
                JSONObject o = new JSONObject(r.body);
                String html = o.optJSONObject("content") != null
                        ? o.getJSONObject("content").optString("html_url", "")
                        : o.optString("html_url", "");
                if (html.isEmpty() && o.optJSONObject("commit") != null) {
                    html = o.getJSONObject("commit").optString("html_url", "");
                }
                return new CommitResult(true, html,
                        "Commit OK sur " + repo.owner + "/" + repo.name
                                + " (" + br + ") — " + cleanPath);
            }
            return new CommitResult(false, "",
                    "Commit GitHub HTTP " + r.code + " : " + clip(r.body, 200));
        } catch (Exception e) {
            Log.w(TAG, "commitFile", e);
            return new CommitResult(false, "", "Commit impossible : " + msg(e));
        }
    }

    /** Un fichier à inclure dans un commit multi-fichiers (Git Trees). */
    public static final class FileChange {
        public final String path;
        public final String content;

        public FileChange(String path, String content) {
            this.path = path;
            this.content = content == null ? "" : content;
        }
    }

    /**
     * Commit N fichiers en un seul commit via Git Data API (blobs → tree → commit → ref).
     */
    public static CommitResult commitFiles(String token, String ownerRepo, String branch,
            List<FileChange> files, String message) {
        ParsedRepo repo = ParsedRepo.parse(ownerRepo);
        if (!repo.valid) {
            return new CommitResult(false, "", "Repo invalide — owner/repo.");
        }
        if (TextUtils.isEmpty(token)) {
            return new CommitResult(false, "", "Token GitHub manquant.");
        }
        if (files == null || files.isEmpty()) {
            return new CommitResult(false, "", "Aucun fichier à committer.");
        }
        String msg = TextUtils.isEmpty(message)
                ? ("Orbe : update " + files.size() + " fichiers")
                : message.trim();
        String br = TextUtils.isEmpty(branch) ? "main" : branch.trim();

        try {
            // 1. Resolve branch → commit SHA
            String refUrl = API + "/repos/" + repo.owner + "/" + repo.name
                    + "/git/ref/heads/" + br;
            HttpResult refR = request("GET", refUrl, token, null);
            String baseCommitSha;
            String baseTreeSha;
            if (refR.code == 404) {
                // Empty repo or missing branch — try default via contents API fallback: sequential
                if (files.size() == 1) {
                    return commitFile(token, ownerRepo, br, files.get(0).path,
                            files.get(0).content, msg);
                }
                CommitResult last = null;
                for (int i = 0; i < files.size(); i++) {
                    FileChange f = files.get(i);
                    String m = (i == files.size() - 1) ? msg
                            : ("chore: add " + sanitizePath(f.path));
                    last = commitFile(token, ownerRepo, br, f.path, f.content, m);
                    if (!last.ok) return last;
                }
                return new CommitResult(true, last != null ? last.htmlUrl : "",
                        "Commit OK (fallback Contents) — " + files.size()
                                + " fichiers sur " + repo.owner + "/" + repo.name);
            }
            if (refR.code != 200) {
                return new CommitResult(false, "",
                        "Ref HTTP " + refR.code + " : " + clip(refR.body, 160));
            }
            baseCommitSha = new JSONObject(refR.body)
                    .getJSONObject("object").getString("sha");

            HttpResult commitR = request("GET",
                    API + "/repos/" + repo.owner + "/" + repo.name
                            + "/git/commits/" + baseCommitSha, token, null);
            if (commitR.code != 200) {
                return new CommitResult(false, "",
                        "Commit parent HTTP " + commitR.code);
            }
            baseTreeSha = new JSONObject(commitR.body)
                    .getJSONObject("tree").getString("sha");

            // 2. Blobs + tree entries
            JSONArray tree = new JSONArray();
            int count = 0;
            for (FileChange f : files) {
                if (f == null) continue;
                String path = sanitizePath(f.path);
                if (path.isEmpty()) continue;
                JSONObject blobBody = new JSONObject()
                        .put("content", f.content)
                        .put("encoding", "utf-8");
                HttpResult blobR = request("POST",
                        API + "/repos/" + repo.owner + "/" + repo.name + "/git/blobs",
                        token, blobBody.toString());
                if (blobR.code != 201 && blobR.code != 200) {
                    return new CommitResult(false, "",
                            "Blob " + path + " HTTP " + blobR.code + " : "
                                    + clip(blobR.body, 120));
                }
                String blobSha = new JSONObject(blobR.body).getString("sha");
                tree.put(new JSONObject()
                        .put("path", path)
                        .put("mode", "100644")
                        .put("type", "blob")
                        .put("sha", blobSha));
                count++;
            }
            if (count == 0) {
                return new CommitResult(false, "", "Aucun chemin valide.");
            }

            JSONObject treeBody = new JSONObject()
                    .put("base_tree", baseTreeSha)
                    .put("tree", tree);
            HttpResult treeR = request("POST",
                    API + "/repos/" + repo.owner + "/" + repo.name + "/git/trees",
                    token, treeBody.toString());
            if (treeR.code != 201 && treeR.code != 200) {
                return new CommitResult(false, "",
                        "Tree HTTP " + treeR.code + " : " + clip(treeR.body, 160));
            }
            String newTreeSha = new JSONObject(treeR.body).getString("sha");

            // 3. Create commit
            JSONObject commitBody = new JSONObject()
                    .put("message", msg)
                    .put("tree", newTreeSha)
                    .put("parents", new JSONArray().put(baseCommitSha));
            HttpResult newCommitR = request("POST",
                    API + "/repos/" + repo.owner + "/" + repo.name + "/git/commits",
                    token, commitBody.toString());
            if (newCommitR.code != 201 && newCommitR.code != 200) {
                return new CommitResult(false, "",
                        "New commit HTTP " + newCommitR.code + " : "
                                + clip(newCommitR.body, 160));
            }
            JSONObject newCommit = new JSONObject(newCommitR.body);
            String newSha = newCommit.getString("sha");
            String html = newCommit.optString("html_url", "");

            // 4. Update ref (PATCH /git/refs/… — pluriel ; /git/ref/ → 404)
            JSONObject refBody = new JSONObject().put("sha", newSha).put("force", false);
            String updateRefUrl = API + "/repos/" + repo.owner + "/" + repo.name
                    + "/git/refs/heads/" + br;
            HttpResult updateR = request("PATCH", updateRefUrl, token, refBody.toString());
            if (updateR.code != 200) {
                // Fallback Contents API si PATCH refusé / introuvable
                if (updateR.code == 404 || updateR.code == 405) {
                    CommitResult fallback = commitFilesViaContents(
                            token, ownerRepo, br, files, msg);
                    if (fallback.ok) return fallback;
                }
                return new CommitResult(false, "",
                        "Mise à jour branche « " + br + " » impossible (HTTP "
                                + updateR.code + "). "
                                + "Vérifie le token (scope repo) et la branche. "
                                + clip(updateR.body, 120));
            }

            return new CommitResult(true, html,
                    "Commit OK — " + count + " fichier(s) sur "
                            + repo.owner + "/" + repo.name + " (" + br + ")");
        } catch (Exception e) {
            Log.w(TAG, "commitFiles", e);
            return new CommitResult(false, "", "Commit multi : " + msg(e));
        }
    }

    /** Fallback multi-fichiers via Contents API (un commit par fichier). */
    private static CommitResult commitFilesViaContents(String token, String ownerRepo,
            String branch, List<FileChange> files, String message) {
        CommitResult last = null;
        for (int i = 0; i < files.size(); i++) {
            FileChange f = files.get(i);
            String m = (i == files.size() - 1) ? message
                    : ("chore: add " + sanitizePath(f.path));
            last = commitFile(token, ownerRepo, branch, f.path, f.content, m);
            if (!last.ok) return last;
        }
        return new CommitResult(true, last != null ? last.htmlUrl : "",
                "Commit OK (Contents) — " + files.size()
                        + " fichiers sur " + ownerRepo);
    }

    /** Derniers commits du repo (message + date relative courte). */
    public static List<String> listRecentCommits(String token, String ownerRepo, int limit) {
        List<String> out = new ArrayList<>();
        ParsedRepo repo = ParsedRepo.parse(ownerRepo);
        if (!repo.valid || TextUtils.isEmpty(token)) return out;
        int n = Math.max(1, Math.min(limit <= 0 ? 5 : limit, 15));
        try {
            HttpResult r = request("GET",
                    API + "/repos/" + repo.owner + "/" + repo.name
                            + "/commits?per_page=" + n, token, null);
            if (r.code != 200) return out;
            JSONArray arr = new JSONArray(r.body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject c = o.optJSONObject("commit");
                String message = c != null ? c.optString("message", "") : "";
                if (message.contains("\n")) message = message.substring(0, message.indexOf('\n'));
                message = message.trim();
                if (message.length() > 80) message = message.substring(0, 77) + "…";
                if (!message.isEmpty()) out.add(message);
            }
        } catch (Exception e) {
            Log.w(TAG, "listRecentCommits", e);
        }
        return out;
    }

    /**
     * Contenu texte d'un fichier distant (Contents API).
     * @return contenu décodé, ou {@code null} si 404 / erreur
     */
    public static String fetchFileContent(String token, String ownerRepo,
            String path, String branch) {
        ParsedRepo repo = ParsedRepo.parse(ownerRepo);
        if (!repo.valid || TextUtils.isEmpty(token)) return null;
        String p = sanitizePath(path);
        if (p.isEmpty()) return null;
        String br = TextUtils.isEmpty(branch) ? "main" : branch.trim();
        try {
            String url = API + "/repos/" + repo.owner + "/" + repo.name
                    + "/contents/" + p + "?ref=" + br;
            HttpResult r = request("GET", url, token, null);
            if (r.code == 404) return null;
            if (r.code != 200) return null;
            JSONObject o = new JSONObject(r.body);
            String encoding = o.optString("encoding", "");
            String content = o.optString("content", "");
            if ("base64".equalsIgnoreCase(encoding) && !content.isEmpty()) {
                content = content.replace("\n", "");
                byte[] decoded = android.util.Base64.decode(content, android.util.Base64.DEFAULT);
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!content.isEmpty()) return content;
            return null;
        } catch (Exception e) {
            Log.w(TAG, "fetchFileContent", e);
            return null;
        }
    }

    private static String fetchFileSha(String token, ParsedRepo repo, String path, String branch)
            throws Exception {
        String url = API + "/repos/" + repo.owner + "/" + repo.name
                + "/contents/" + path + "?ref=" + branch;
        HttpResult r = request("GET", url, token, null);
        if (r.code == 404) return "";
        if (r.code != 200) {
            throw new IllegalStateException("Lecture fichier HTTP " + r.code);
        }
        return new JSONObject(r.body).optString("sha", "");
    }

    static String sanitizePath(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..")) return "";
        return p;
    }

    static final class ParsedRepo {
        final String owner;
        final String name;
        final boolean valid;

        ParsedRepo(String owner, String name, boolean valid) {
            this.owner = owner;
            this.name = name;
            this.valid = valid;
        }

        static ParsedRepo parse(String raw) {
            if (raw == null) return new ParsedRepo("", "", false);
            String t = raw.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("https://github.com/")) {
                t = t.substring("https://github.com/".length());
            }
            if (t.endsWith(".git")) t = t.substring(0, t.length() - 4);
            while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            String[] parts = t.split("/");
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                return new ParsedRepo("", "", false);
            }
            return new ParsedRepo(parts[0], parts[1], true);
        }
    }

    private static HttpResult request(String method, String urlStr, String token, String body)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        applyHttpMethod(c, method);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "Orbe-Pegase");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = readAll(stream);
        c.disconnect();
        return new HttpResult(code, resp);
    }

    /**
     * PATCH natif si possible. Ne jamais envoyer un POST « nu » à GitHub :
     * {@code X-HTTP-Method-Override} n'est pas honoré → 404 sur /git/refs.
     */
    private static void applyHttpMethod(HttpURLConnection c, String method) throws Exception {
        if (!"PATCH".equals(method)) {
            c.setRequestMethod(method);
            return;
        }
        try {
            c.setRequestMethod("PATCH");
            return;
        } catch (ProtocolException ignored) {
            // anciennes API Android
        }
        try {
            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
            f.setAccessible(true);
            f.set(c, "PATCH");
            return;
        } catch (Exception ignored) {
        }
        // Dernier recours : POST + override (souvent ignoré par GitHub)
        c.setRequestMethod("POST");
        c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
