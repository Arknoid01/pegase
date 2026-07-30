package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Outils fichiers Orion pour Ollama tool calling ({@code write_file} / {@code read_file} /
 * {@code append_file}) + application locale / pod.
 */
public final class OrionFileTools {

    public static final String WRITE = "write_file";
    public static final String APPEND = "append_file";
    public static final String READ = "read_file";

    /** Max tours où le lint demande une correction via write_file. */
    public static final int MAX_LINT_FIX_ROUNDS = 2;

    public static final class WriteResult {
        public final String filename;
        public final String content;
        public final String message;

        public WriteResult(String filename, String content, String message) {
            this.filename = filename != null ? filename : "";
            this.content = content != null ? content : "";
            this.message = message != null ? message : "";
        }
    }

    private OrionFileTools() {}

    /** Schéma tools Ollama / OpenAI-compat. */
    public static JSONArray toolSchemas() {
        JSONArray tools = new JSONArray();
        tools.put(functionTool(WRITE,
                "Écrit ou remplace un fichier du projet web actif (HTML/CSS/JS de préférence).",
                props("filename", "Nom du fichier, ex: index.html ou ball.js",
                        "content", "Contenu complet du fichier"),
                required("filename", "content")));
        tools.put(functionTool(APPEND,
                "Ajoute du contenu à la fin d'un fichier existant.",
                props("filename", "Nom du fichier",
                        "content", "Texte à ajouter à la fin"),
                required("filename", "content")));
        tools.put(functionTool(READ,
                "Lit un fichier du projet actif pour contexte avant de modifier.",
                props("filename", "Nom du fichier à lire"),
                required("filename")));
        return tools;
    }

    /**
     * Exécute un tool call. Les écritures vont en session + projet actif (remplace) + pod si prêt.
     * @return message pour le rôle tool Ollama ; writes remplies pour write/append
     */
    public static String execute(Context ctx, String name, JSONObject args,
            List<WriteResult> writesOut) {
        return execute(ctx, name, args, writesOut, null);
    }

    /**
     * @param lintRoundsByFile compteur par fichier (write/append) pour plafonner
     *        les demandes de correction lint à {@link #MAX_LINT_FIX_ROUNDS}.
     */
    public static String execute(Context ctx, String name, JSONObject args,
            List<WriteResult> writesOut, java.util.Map<String, Integer> lintRoundsByFile) {
        if (TextUtils.isEmpty(name) || args == null) {
            return "Erreur : appel outil invalide.";
        }
        String filename = args.optString("filename", args.optString("path", "")).trim();
        String content = args.optString("content", "");
        switch (name) {
            case WRITE:
                return doWrite(ctx, filename, content, false, writesOut, lintRoundsByFile);
            case APPEND:
                return doWrite(ctx, filename, content, true, writesOut, lintRoundsByFile);
            case READ: {
                String body = OrionProjectStore.get(ctx).readFile(filename);
                if (TextUtils.isEmpty(body)) {
                    body = sessionFileContent(filename);
                }
                if (TextUtils.isEmpty(body)) {
                    String missing = TextUtils.isEmpty(filename) ? "(sans nom)" : filename;
                    return missing + " n'existe pas — utilise write_file pour le créer.";
                }
                return body;
            }
            default:
                return "Outil inconnu : " + name;
        }
    }

    private static String doWrite(Context ctx, String filename, String content, boolean append,
            List<WriteResult> writesOut) {
        return doWrite(ctx, filename, content, append, writesOut, null);
    }

    private static String doWrite(Context ctx, String filename, String content, boolean append,
            List<WriteResult> writesOut, java.util.Map<String, Integer> lintRoundsByFile) {
        if (TextUtils.isEmpty(filename)) return "Erreur : filename manquant.";
        String finalContent = content != null ? content : "";
        if (append) {
            String prev = OrionProjectStore.get(ctx).readFile(filename);
            if (TextUtils.isEmpty(prev)) prev = sessionFileContent(filename);
            finalContent = (prev == null ? "" : prev) + finalContent;
        }
        String baseName = basename(filename);
        OrionFileStore.get().addFileToSession(baseName, finalContent);
        OrionProjectStore.SaveResult saved = OrionProjectStore.get(ctx)
                .saveFile(baseName, finalContent, true, false);
        String msg = saved != null ? saved.message : "écrit";
        if (writesOut != null) {
            writesOut.add(new WriteResult(baseName, finalContent, msg));
        }
        try {
            PodFileClient.writeBestEffort(ctx, baseName, finalContent);
            OrionGraphClient.reindexBestEffort(ctx, baseName);
        } catch (Exception ignored) {
        }
        String ok = "OK — " + msg;
        String lintTail = lintAfterWrite(ctx, baseName, lintRoundsByFile);
        if (!TextUtils.isEmpty(lintTail)) {
            return ok + "\n\n" + lintTail;
        }
        return ok;
    }

    /**
     * Lint synchrone après écriture — message directif si erreurs.
     * Jamais bloquant : indisponible → "".
     */
    static String lintAfterWrite(Context ctx, String filename,
            java.util.Map<String, Integer> lintRoundsByFile) {
        if (ctx == null || TextUtils.isEmpty(filename)) return "";
        if (!OrionLintClient.isLintable(filename)) return "";
        LintReport report;
        try {
            report = OrionLintClient.check(ctx, filename);
        } catch (Exception e) {
            return "";
        }
        if (report == null || report.toolMissing) return "";
        if (report.hasVisibleIssues()) {
            try {
                OrionFileStore.get().notifyLintUpdated();
            } catch (Exception ignored) {
            }
        }
        if (report.errorCount() <= 0) return "";
        int attempt = 1;
        if (lintRoundsByFile != null) {
            Integer prev = lintRoundsByFile.get(filename);
            attempt = (prev == null ? 0 : prev) + 1;
            lintRoundsByFile.put(filename, attempt);
        }
        boolean askFix = attempt <= MAX_LINT_FIX_ROUNDS;
        try {
            com.pegasuscorp.orbe.diag.Trace.orionLintLoop(
                    filename, report.errorCount(), attempt, askFix);
        } catch (Exception ignored) {
        }
        return formatLintDirective(report, askFix);
    }

    /**
     * Message outil : erreurs seulement, direction claire.
     * @param askFix false = plafond atteint, on n'ordonne plus write_file
     */
    public static String formatLintDirective(LintReport report, boolean askFix) {
        if (report == null || report.errorCount() <= 0) return "";
        String file = TextUtils.isEmpty(report.file) ? "fichier" : report.file;
        int n = report.errorCount();
        StringBuilder sb = new StringBuilder();
        sb.append(file).append(" écrit — ").append(n)
                .append(n > 1 ? " erreurs" : " erreur").append(" de lint :\n");
        int shown = 0;
        for (LintReport.LintIssue issue : report.issues) {
            if (issue == null || !issue.isError()) continue;
            sb.append("  ligne ").append(issue.line).append(" · ");
            if (!TextUtils.isEmpty(issue.rule)) sb.append(issue.rule).append(" · ");
            sb.append(issue.message).append('\n');
            shown++;
            if (shown >= 8) {
                int left = n - shown;
                if (left > 0) sb.append("  … +").append(left).append(" autres\n");
                break;
            }
        }
        if (askFix) {
            sb.append("\nCorrige ces erreurs avec write_file.");
        } else {
            sb.append("\nCorrection lint arrêtée (max ")
                    .append(MAX_LINT_FIX_ROUNDS)
                    .append(" tours) — erreurs affichées à l'utilisateur.");
        }
        return sb.toString().trim();
    }

    /** Reconstruit un texte type fences pour ingest / affichage. */
    public static String toFenceDump(List<WriteResult> writes) {
        if (writes == null || writes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (WriteResult w : writes) {
            if (w == null || TextUtils.isEmpty(w.filename)) continue;
            String lang = langFor(w.filename);
            sb.append("```").append(lang).append(':').append(w.filename).append('\n')
                    .append(w.content).append('\n')
                    .append("```\n\n");
        }
        return sb.toString().trim();
    }

    public static boolean isWebAsset(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".css")
                || p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".svg")
                || p.endsWith(".json") || p.endsWith(".webp") || p.endsWith(".png")
                || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".gif");
    }

    public static String pickMainPage(List<OrionFileSession.OrionFile> files) {
        if (files == null) return null;
        String fallback = null;
        for (OrionFileSession.OrionFile f : files) {
            if (f == null || f.path == null) continue;
            String n = f.path.toLowerCase(Locale.ROOT);
            if (n.equals("index.html") || n.equals("index.htm")) return f.path;
            if (fallback == null && OrionPagePreview.isPage(f.path, f.content)) {
                fallback = f.path;
            }
        }
        return fallback;
    }

    /** Résultat d'application session web → projet (+ lint synchrone). */
    public static final class ApplyResult {
        public final int filesApplied;
        public final int lintErrorFiles;
        public final String lintSummary;

        public ApplyResult(int filesApplied, int lintErrorFiles, String lintSummary) {
            this.filesApplied = Math.max(0, filesApplied);
            this.lintErrorFiles = Math.max(0, lintErrorFiles);
            this.lintSummary = lintSummary != null ? lintSummary : "";
        }

        public boolean hasLintErrors() {
            return lintErrorFiles > 0;
        }
    }

    /** Applique les fichiers web de session dans le projet actif (écrase). */
    public static int applyWebSessionToProject(Context ctx,
            List<OrionFileSession.OrionFile> files) {
        return applyWebSessionToProjectDetailed(ctx, files).filesApplied;
    }

    /**
     * Comme {@link #applyWebSessionToProject} + lint synchrone (même chemin que write_file).
     * Les fences markdown ne passent pas par la boucle outil : on lint quand même ici
     * (badges UI + Trace). La correction auto modèle reste sur write_file.
     */
    public static ApplyResult applyWebSessionToProjectDetailed(Context ctx,
            List<OrionFileSession.OrionFile> files) {
        if (ctx == null || files == null || files.isEmpty()) {
            return new ApplyResult(0, 0, "");
        }
        OrionProjectStore store = OrionProjectStore.get(ctx);
        if (!store.hasActiveProject()) return new ApplyResult(0, 0, "");
        int n = 0;
        int lintErrFiles = 0;
        StringBuilder lintSb = new StringBuilder();
        for (OrionFileSession.OrionFile f : files) {
            if (f == null || !isWebAsset(f.path)) continue;
            OrionProjectStore.SaveResult r = store.saveFile(f.path, f.content, true, false);
            if (r != null && (r.outcome == OrionProjectStore.SaveOutcome.CREATED
                    || r.outcome == OrionProjectStore.SaveOutcome.REPLACED)) {
                n++;
                String base = basename(f.path);
                try {
                    PodFileClient.writeBestEffort(ctx, base, f.content);
                    OrionGraphClient.reindexBestEffort(ctx, base);
                } catch (Exception ignored) {
                }
                // Sync lint — pas de checkBestEffort doublon
                String lintTail = lintAfterWrite(ctx, base, null);
                if (!TextUtils.isEmpty(lintTail)) {
                    lintErrFiles++;
                    if (lintSb.length() < 800) {
                        if (lintSb.length() > 0) lintSb.append("\n\n");
                        lintSb.append(lintTail);
                    }
                }
            }
        }
        return new ApplyResult(n, lintErrFiles, lintSb.toString().trim());
    }

    private static String sessionFileContent(String filename) {
        OrionFileSession s = OrionFileStore.get().getCurrentSession();
        if (s == null) return "";
        String want = basename(filename);
        for (OrionFileSession.OrionFile f : s.getFiles()) {
            if (f != null && want.equalsIgnoreCase(basename(f.path))) {
                return f.content != null ? f.content : "";
            }
        }
        return "";
    }

    static String basename(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/').trim();
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    static String langFor(String filename) {
        String n = filename != null ? filename.toLowerCase(Locale.ROOT) : "";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "html";
        if (n.endsWith(".css")) return "css";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "javascript";
        if (n.endsWith(".svg")) return "svg";
        if (n.endsWith(".json")) return "json";
        if (n.endsWith(".java")) return "java";
        return "text";
    }

    private static JSONObject functionTool(String name, String desc, JSONObject properties,
            JSONArray required) {
        try {
            return new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", name)
                            .put("description", desc)
                            .put("parameters", new JSONObject()
                                    .put("type", "object")
                                    .put("properties", properties)
                                    .put("required", required)));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject props(String k1, String d1, String k2, String d2) {
        try {
            JSONObject p = new JSONObject();
            p.put(k1, new JSONObject().put("type", "string").put("description", d1));
            if (k2 != null) {
                p.put(k2, new JSONObject().put("type", "string").put("description", d2));
            }
            return p;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject props(String k1, String d1) {
        return props(k1, d1, null, null);
    }

    private static JSONArray required(String... keys) {
        JSONArray a = new JSONArray();
        if (keys != null) {
            for (String k : keys) a.put(k);
        }
        return a;
    }

    /** Parse tool_calls Ollama (message.tool_calls). */
    public static List<JSONObject> parseToolCalls(JSONObject message) {
        List<JSONObject> out = new ArrayList<>();
        if (message == null) return out;
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls == null) return out;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) continue;
            JSONObject fn = call.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name", "");
            Object rawArgs = fn.opt("arguments");
            JSONObject args;
            try {
                if (rawArgs instanceof JSONObject) {
                    args = (JSONObject) rawArgs;
                } else if (rawArgs instanceof String) {
                    String s = ((String) rawArgs).trim();
                    args = s.isEmpty() ? new JSONObject() : new JSONObject(s);
                } else {
                    args = new JSONObject();
                }
            } catch (Exception e) {
                args = new JSONObject();
            }
            try {
                out.add(new JSONObject().put("name", name).put("arguments", args)
                        .put("id", call.optString("id", "call_" + i)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
