package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Boucle Ollama {@code /api/chat} + tools fichiers.
 * Fallback : retourne null → l'appelant utilise {@code /api/generate}.
 */
public final class OrionToolLoop {

    public static final int MAX_ROUNDS = 6;

    /** System message API /api/chat (hors contenu assemble). */
    public static final String SYSTEM_PROMPT =
            "Tu es Orion. Stack par défaut : HTML/CSS/JS. "
                    + "Règle absolue : toute création ou modification "
                    + "de fichier passe UNIQUEMENT par write_file "
                    + "(ou append_file). "
                    + "Interdit : blocs markdown ``` , dumps de code "
                    + "dans le texte, « voici le fichier ». "
                    + "Lis avec read_file avant un patch si besoin. "
                    + "Si read_file indique qu'un fichier n'existe pas "
                    + "et que la mission le demande, enchaîne aussitôt "
                    + "avec write_file pour le créer — "
                    + "ne pose pas de question, n'attends pas confirmation. "
                    + "Si un retour d'outil signale des erreurs de lint, "
                    + "corrige immédiatement avec write_file. "
                    + "Pas de Java/Kotlin sauf demande explicite.";

    public static final class Result {
        public final String assistantText;
        public final List<OrionFileTools.WriteResult> writes;
        public final boolean usedTools;

        public Result(String assistantText, List<OrionFileTools.WriteResult> writes,
                boolean usedTools) {
            this.assistantText = assistantText != null ? assistantText : "";
            this.writes = writes != null ? writes : new ArrayList<>();
            this.usedTools = usedTools;
        }

        /** Texte affichable + fences pour ingest. */
        public String displayAndIngest() {
            String fences = OrionFileTools.toFenceDump(writes);
            if (TextUtils.isEmpty(assistantText)) return fences;
            if (TextUtils.isEmpty(fences)) return assistantText;
            return assistantText.trim() + "\n\n" + fences;
        }
    }

    private OrionToolLoop() {}

    /**
     * @return null si le pod ne gère pas les tools / erreur non récupérable soft
     */
    public static Result run(Context ctx, String ollamaUrl, String bearerToken,
            String systemAndUserPrompt, OrionStreamCallback progress) {
        if (TextUtils.isEmpty(ollamaUrl) || TextUtils.isEmpty(systemAndUserPrompt)) {
            return null;
        }
        String base = normalizeBase(ollamaUrl);
        List<String> models;
        try {
            models = OrionOllamaClient.listModels(base, bearerToken);
        } catch (Exception e) {
            return null;
        }
        String model = OrionOllamaClient.pickModel(models);
        if (model == null) return null;

        try {
            JSONArray messages = new JSONArray()
                    .put(new JSONObject()
                            .put("role", "system")
                            .put("content", SYSTEM_PROMPT))
                    .put(new JSONObject()
                            .put("role", "user")
                            .put("content", systemAndUserPrompt));

            List<OrionFileTools.WriteResult> writes = new ArrayList<>();
            boolean usedTools = false;
            String lastText = "";
            java.util.Map<String, Integer> lintRounds = new java.util.HashMap<>();

            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (OrionOllamaClient.isCancelled()) {
                    if (progress != null) progress.onError("Génération arrêtée.");
                    return new Result(lastText, writes, usedTools);
                }
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("stream", false)
                        .put("tools", OrionFileTools.toolSchemas())
                        .put("options", new JSONObject()
                                .put("temperature", 0.7)
                                .put("num_ctx", 32768));

                JSONObject resp = postChat(base, bearerToken, body);
                if (resp == null) return usedTools ? new Result(lastText, writes, true) : null;

                JSONObject msg = resp.optJSONObject("message");
                if (msg == null) return usedTools ? new Result(lastText, writes, true) : null;

                String content = msg.optString("content", "");
                if (!TextUtils.isEmpty(content)) {
                    lastText = content;
                    if (progress != null) progress.onToken(content);
                }

                List<JSONObject> calls = OrionFileTools.parseToolCalls(msg);
                if (calls.isEmpty()) {
                    return new Result(lastText, writes, usedTools);
                }

                usedTools = true;
                // Remettre le message assistant (avec tool_calls) dans l'historique
                messages.put(msg);
                for (JSONObject call : calls) {
                    String name = call.optString("name", "");
                    JSONObject args = call.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    if (progress != null && OrionFileTools.WRITE.equals(name)) {
                        progress.onToken("\n⚙️ write_file "
                                + args.optString("filename", "") + "…\n");
                    }
                    String toolResult = OrionFileTools.execute(
                            ctx, name, args, writes, lintRounds);
                    JSONObject toolMsg = new JSONObject()
                            .put("role", "tool")
                            .put("content", toolResult);
                    String id = call.optString("id", "");
                    if (!TextUtils.isEmpty(id)) toolMsg.put("tool_call_id", id);
                    // Ollama accepte aussi name sur tool message
                    if (!TextUtils.isEmpty(name)) toolMsg.put("name", name);
                    messages.put(toolMsg);
                }
            }
            return new Result(lastText, writes, usedTools);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject postChat(String base, String bearerToken, JSONObject body)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/chat")
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(OrionOllamaClient.TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
        if (!TextUtils.isEmpty(bearerToken)) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        String raw = readAll(conn);
        conn.disconnect();
        if (code >= 400) return null;
        if (TextUtils.isEmpty(raw)) return null;
        return new JSONObject(raw);
    }

    private static String readAll(HttpURLConnection conn) {
        try {
            java.io.InputStream in = conn.getErrorStream() != null
                    ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalizeBase(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
