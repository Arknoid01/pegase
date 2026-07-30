package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.pegasuscorp.orbe.chat.ChatBackend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Boîte noire locale. Append-only, JSONL (une ligne = un événement), 100 % sur l'appareil.
 *
 * Objectif : pendant les 15 jours de test, enregistrer assez de contexte pour que
 * DiagReport puisse détecter tout seul les anomalies (boucle de répétition, tour user
 * orphelin, doublon, JSON d'outil malformé, latences).
 *
 * Coût : une écriture asynchrone sur un thread unique. Zéro impact sur l'UI.
 */
public final class Trace {

    private static final long MAX_BYTES = 8L * 1024 * 1024;   // 8 Mo, largement assez pour 15 j
    private static final int PREVIEW_CHARS = 400;
    /** Plafond dédié aux champs user_msg des events enrichis. */
    private static final int USER_MSG_MAX = 100;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static File file;
    private static boolean enabled = false;
    /** Si true, les événements portent stress:true (sessions de test poussées). */
    private static boolean stress = false;
    /** Si true, les textes sont hachés au lieu d'être stockés (si tu veux partager le fichier). */
    private static boolean redact = false;

    private Trace() {}

    public static void init(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "diag");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, "trace.jsonl");
        enabled = true;
    }

    public static void setEnabled(boolean on) { enabled = on; }
    public static void setRedact(boolean on) { redact = on; }
    public static void setStressTest(boolean on) { stress = on; }
    public static boolean isStressTest() { return stress; }
    public static File file() { return file; }

    /** Rétention max des archives JSONL (jours). */
    public static final int ARCHIVE_RETENTION_DAYS = 7;

    /** Dossier {@code files/diag/archives/}. */
    public static File archivesDir() {
        if (file == null) return null;
        File dir = new File(file.getParentFile(), "archives");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Fichier archive nommé {@code trace-YYYY-MM-DD.jsonl}. */
    public static File archiveFile(java.time.LocalDate date) {
        File dir = archivesDir();
        if (dir == null || date == null) return null;
        return new File(dir, "trace-" + date + ".jsonl");
    }

    /**
     * Initialise le dossier diag si besoin, puis archive la trace courante
     * sous la date de la veille (rotation matin).
     */
    public static File archiveTrace(Context ctx) {
        if (ctx != null) init(ctx);
        return archiveTrace();
    }

    /**
     * Archive la trace courante sous la date de la veille (rotation matin),
     * puis repart sur un {@code trace.jsonl} vide. Purge au-delà de 7 jours.
     * @return fichier archive créé, ou null si rien à archiver
     */
    public static File archiveTrace() {
        return archiveTrace(java.time.LocalDate.now().minusDays(1));
    }

    /** Archive la trace courante sous {@code date} puis recrée un fichier vide. */
    public static File archiveTrace(java.time.LocalDate date) {
        if (file == null || date == null) return null;
        File dest = archiveFile(date);
        if (dest == null) return null;
        File dir = archivesDir();
        if (dir == null) return null;
        if (!file.exists() || file.length() == 0) {
            purgeOldArchives(ARCHIVE_RETENTION_DAYS);
            return null;
        }
        if (dest.exists()) {
            // Fusion append si déjà une archive pour ce jour (double appel).
            appendFile(file, dest);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } else {
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(dest);
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            file.createNewFile();
        } catch (Exception ignored) {}
        purgeOldArchives(ARCHIVE_RETENTION_DAYS);
        return dest.exists() ? dest : null;
    }

    /** Supprime {@code files/diag/archives/trace-*.jsonl} plus vieux que {@code keepDays}. */
    public static void purgeOldArchives(int keepDays) {
        File dir = archivesDir();
        if (dir == null || !dir.isDirectory()) return;
        java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(Math.max(1, keepDays));
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            java.time.LocalDate d = parseArchiveDate(f.getName());
            if (d != null && d.isBefore(cutoff)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Archives des N derniers jours calendaires (aujourd'hui inclus si fichier présent),
     * du plus récent au plus ancien. N borné à {@link #ARCHIVE_RETENTION_DAYS}.
     */
    public static java.util.List<File> listArchiveFiles(int daysBack) {
        java.util.List<File> out = new java.util.ArrayList<>();
        int n = Math.max(1, Math.min(daysBack, ARCHIVE_RETENTION_DAYS + 3));
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = 0; i < n; i++) {
            File f = archiveFile(today.minusDays(i));
            if (f != null && f.exists() && f.length() > 0) out.add(f);
        }
        return out;
    }

    static java.time.LocalDate parseArchiveDate(String name) {
        if (name == null || !name.startsWith("trace-") || !name.endsWith(".jsonl")) {
            return null;
        }
        String mid = name.substring("trace-".length(), name.length() - ".jsonl".length());
        try {
            return java.time.LocalDate.parse(mid);
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendFile(File src, File dest) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest, true)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {}
    }

    /** Efface trace.jsonl et orbe-diag-report.json — repartir à zéro pour les tests. */
    public static void clear(Context ctx) {
        if (ctx != null && file == null) {
            init(ctx);
        }
        File dir = file != null ? file.getParentFile()
                : new File(ctx.getApplicationContext().getFilesDir(), "diag");
        if (dir != null && !dir.exists()) dir.mkdirs();
        deleteQuiet(file);
        deleteQuiet(new File(dir, "orbe-diag-report.json"));
        if (file != null && dir != null) {
            file = new File(dir, "trace.jsonl");
        }
        enabled = true;
    }

    private static void deleteQuiet(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Partage trace.jsonl (mail, Drive, Files…). */
    public static void share(Context ctx) throws Exception {
        if (file == null || !file.exists() || file.length() == 0) {
            throw new Exception("Aucune trace enregistrée (trace.jsonl vide ou absent)");
        }
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "orbe-trace.jsonl")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(share, "Trace Orbe (trace.jsonl)")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Commande vocale ou texte : « mode test ». */
    public static boolean looksLikeStressToggle(String text) {
        if (text == null) return false;
        String fold = text.toLowerCase(java.util.Locale.ROOT)
                .replace("é", "e").replace("è", "e")
                .replace("'", " ").replace("’", " ")
                .trim();
        return fold.contains("mode test");
    }

    // ------------------------------------------------------------ événements

    /** Message utilisateur soumis. source = "voice" | "text" | "bureau" | "direct_tool" | "wake" */
    public static void userMessage(String text, String source, boolean locked) {
        JSONObject o = base("user_message");
        put(o, "text", text);
        put(o, "source", source);
        put(o, "locked", locked);
        write(o);
    }

    /**
     * Action bureau locale (voix / UI) — sans LLM.
     * action : voice_input | dictation | local_section | local_note | mode_preview | mode_edit
     *         | close | save | open_file | llm_request
     */
    public static void bureauAction(String action, String detail) {
        bureauAction(action, detail, -1);
    }

    /**
     * @param contextChunks nombre de chunks injectés (−1 = non applicable / omis).
     */
    public static void bureauAction(String action, String detail, int contextChunks) {
        JSONObject o = base("bureau_action");
        put(o, "action", action);
        if (detail != null && !detail.isEmpty()) {
            put(o, "detail", detail);
        }
        if (contextChunks >= 0) {
            put(o, "context_chunks", contextChunks);
        }
        write(o);
    }

    /**
     * Résultat édition bureau Markdown (LLM ou repli local).
     * Ne logue pas le document — seulement métadonnées.
     */
    public static void bureauEditResult(boolean fallback, boolean replaceAll,
            int markdownChars, String speakPreview) {
        bureauEditResult(fallback, replaceAll, markdownChars, speakPreview, false);
    }

    /**
     * @param potentialHallucination true si réponse mode question sans RAG
     *        mais avec formulations inventées du passé.
     */
    public static void bureauEditResult(boolean fallback, boolean replaceAll,
            int markdownChars, String speakPreview, boolean potentialHallucination) {
        JSONObject o = base("bureau_edit");
        put(o, "fallback", fallback);
        put(o, "replace_all", replaceAll);
        put(o, "markdown_chars", markdownChars);
        if (speakPreview != null && !speakPreview.isEmpty()) {
            put(o, "speak", speakPreview);
        }
        if (potentialHallucination) {
            put(o, "potentialHallucination", true);
        }
        write(o);
    }

    /** Réponse brute du LLM (avant tout traitement). */
    public static void llmReply(String rawText, String backend, long latencyMs,
                                boolean streamed, boolean isToolCall, boolean malformedTool,
                                int promptChars) {
        llmReply(rawText, backend, latencyMs, streamed, isToolCall, malformedTool,
                promptChars, false, null);
    }

    /** Variante avec contexte bureau / appel éphémère (hors historique chat). */
    public static void llmReply(String rawText, String backend, long latencyMs,
            boolean streamed, boolean isToolCall, boolean malformedTool, int promptChars,
            boolean ephemeral, String channel) {
        llmReply(rawText, backend, latencyMs, streamed, isToolCall, malformedTool,
                promptChars, ephemeral, channel, -1, -1, null);
    }

    /**
     * @param contextChunks −1 = omis ; memoriesUsed −1 = omis ;
     *        webSources null = omis
     */
    public static void llmReply(String rawText, String backend, long latencyMs,
            boolean streamed, boolean isToolCall, boolean malformedTool, int promptChars,
            boolean ephemeral, String channel, int contextChunks, int memoriesUsed,
            List<String> webSources) {
        JSONObject o = base("llm_reply");
        put(o, "text", rawText);
        put(o, "backend", backend);
        put(o, "latency_ms", latencyMs);
        put(o, "streamed", streamed);
        put(o, "is_tool_call", isToolCall);
        put(o, "malformed_tool", malformedTool);
        put(o, "prompt_chars", promptChars);
        if (ephemeral) put(o, "ephemeral", true);
        if (channel != null && !channel.isEmpty()) put(o, "channel", channel);
        if (contextChunks >= 0) put(o, "context_chunks", contextChunks);
        if (memoriesUsed >= 0) put(o, "memories_used", memoriesUsed);
        if (webSources != null && !webSources.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String s : webSources) {
                if (s != null && !s.isEmpty()) arr.put(s);
            }
            if (arr.length() > 0) {
                try {
                    o.put("web_sources", arr);
                } catch (Exception ignored) {
                }
            }
        }
        write(o);
    }

    /** Carte de raisonnement d'un tour (UI Discussion + Diag). */
    public static void reasoningCard(ReasoningCard card) {
        if (card == null) return;
        JSONObject o = base("reasoning_card");
        try {
            JSONObject body = card.toJson();
            java.util.Iterator<String> keys = body.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                o.put(k, body.get(k));
            }
            if (card.potentialHallucination) {
                put(o, "category", DiagCategory.HESITATION.name());
            }
        } catch (Exception ignored) {
        }
        write(o);
    }

    /** Filtre anti-fantôme a bloqué une prose « action faite » sans outil. */
    public static void phantomBlocked(String userRequest, String rawReply) {
        phantomBlocked(userRequest, rawReply, null);
    }

    /** @param reason ex. {@code diag_fallback_blocked} — omis si null/vide. */
    public static void phantomBlocked(String userRequest, String rawReply, String reason) {
        JSONObject o = base("phantom_blocked");
        put(o, "user_request", userRequest);
        put(o, "reply", rawReply);
        if (reason != null && !reason.isEmpty()) {
            put(o, "reason", reason);
        }
        put(o, "category", DiagCategory.PHANTOM_BLOCKED.name());
        write(o);
    }

    /**
     * Matching routing UserExamples (avant règles hardcodées).
     * {@code source} = {@code user_example} ou {@code hardcoded}.
     */
    public static void routingMatch(String userMessage, String tool, float score,
            String source, boolean exact) {
        JSONObject o = base("routing_match");
        put(o, "user_msg", userMessage);
        put(o, "tool", tool);
        try {
            o.put("score", score);
            o.put("exact", exact);
        } catch (Exception ignored) {
        }
        put(o, "routing_source", source != null ? source : "hardcoded");
        write(o);
    }

    public static void providerUsed(String provider, String model, long latencyMs) {
        JSONObject o = base("provider_used");
        put(o, "provider", provider);
        put(o, "model", model);
        try {
            o.put("latency_ms", latencyMs);
        } catch (Exception ignored) {
        }
        write(o);
    }

    public static void providerTimeout(String provider, int timeoutMs) {
        JSONObject o = base("provider_timeout");
        put(o, "provider", provider);
        try {
            o.put("timeout_ms", timeoutMs);
        } catch (Exception ignored) {
        }
        write(o);
    }

    public static void providerRateLimit(String provider, long retryAfterMs) {
        JSONObject o = base("provider_ratelimit");
        put(o, "provider", provider);
        try {
            o.put("retry_after_ms", retryAfterMs);
        } catch (Exception ignored) {
        }
        write(o);
    }

    /**
     * Hésitation / intention d'outil floue (fantôme, JSON malformé, etc.).
     * Event {@code type=tool_hesitation}.
     */
    public static void toolHesitation(String tool, String reason, String detail, String userMessage) {
        JSONObject o = base("tool_hesitation");
        put(o, "tool", tool != null ? tool : "");
        put(o, "reason", reason != null ? reason : "");
        put(o, "detail", detail != null ? detail : "");
        put(o, "user_msg", truncateUserMsg(userMessage));
        put(o, "category", DiagCategory.HESITATION.name());
        write(o);
    }

    /**
     * Échec d'outil ou validation HTTP outil, avec contexte.
     * Event {@code type=tool_failure_ctx}.
     */
    public static void toolFailureContext(String tool, String reason, String detail,
            String userMessage) {
        JSONObject o = base("tool_failure_ctx");
        put(o, "tool", tool != null ? tool : "");
        put(o, "reason", reason != null ? reason : "");
        put(o, "detail", detail != null ? detail : "");
        put(o, "user_msg", truncateUserMsg(userMessage));
        put(o, "category", DiagCategory.FAILURE.name());
        write(o);
    }

    /** Attend que la file d'écriture async soit drainée (tests). */
    public static void flushForTests() {
        try {
            IO.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static String truncateUserMsg(String msg) {
        if (msg == null) return "";
        String t = msg.trim();
        if (t.length() <= USER_MSG_MAX) return t;
        return t.substring(0, USER_MSG_MAX - 1).trim() + "…";
    }

    /** Outil sur le point de s'exécuter. */
    public static void toolStart(String toolId, JSONObject params, boolean fromLocalRouter) {
        JSONObject o = base("tool_start");
        put(o, "tool", toolId);
        put(o, "params", params != null ? params.toString() : "{}");
        put(o, "local_router", fromLocalRouter);   // court-circuit VoiceIntentRouter ?
        write(o);
    }

    /** Fin d'outil. ok=false -> error renseigné. */
    public static void toolEnd(String toolId, boolean ok, long durationMs,
                               String spokenReply, String error) {
        JSONObject o = base("tool_end");
        put(o, "tool", toolId);
        put(o, "ok", ok);
        put(o, "duration_ms", durationMs);
        put(o, "reply", truncateForTrace(spokenReply));
        put(o, "error", error);
        write(o);
    }

    /**
     * Appel Orion (qwen3-coder via Ollama).
     * @param promptChars taille du prompt injecté
     * @param responseChars taille de la réponse complète
     * @param wallMs latence totale
     * @param contextChunksUsed nombre de chunks RAG injectés
     */
    public static void orionCall(int promptChars, int responseChars, long wallMs,
            int contextChunksUsed) {
        JSONObject o = base("orion_call");
        put(o, "prompt_chars", Math.max(0, promptChars));
        put(o, "response_chars", Math.max(0, responseChars));
        put(o, "wall_ms", Math.max(0L, wallMs));
        put(o, "context_chunks", Math.max(0, contextChunksUsed));
        write(o);
    }

    /** Mission LARGE/MASSIVE détectée — découpage Phase 4. */
    public static void orionLargeTask(String rawInput, String complexity) {
        JSONObject o = base("orion_large_task");
        put(o, "complexity", complexity);
        put(o, "raw_preview", truncateForTrace(rawInput));
        write(o);
    }

    /** Échec de parsing / découpage mission Orion (Phase 4). */
    public static void orionChunkError(String message) {
        JSONObject o = base("orion_chunk_error");
        put(o, "message", message);
        write(o);
    }

    /** Échec de construction plan Orion (Phase 5). */
    public static void orionPlanError(String message) {
        JSONObject o = base("orion_plan_error");
        put(o, "message", message);
        write(o);
    }

    /** Reasoning Sandwich Phase 6 — plan / execute / verify. */
    public static void orionSandwich(String phase, int snippetTokens,
            boolean hasLocation, String result) {
        JSONObject o = base("orion_sandwich");
        put(o, "phase", phase);
        put(o, "snippet_tokens", Math.max(0, snippetTokens));
        put(o, "has_location", hasLocation);
        put(o, "result", result);
        write(o);
    }

    /**
     * Phase 2 graphe — injection projet ciblée.
     * @param source related | seed_only | all | preview | none
     */
    public static void orionGraphInject(String source, int fileCount, int chars, String seed) {
        JSONObject o = base("orion_graph_inject");
        put(o, "source", source);
        put(o, "file_count", Math.max(0, fileCount));
        put(o, "chars", Math.max(0, chars));
        put(o, "seed", seed);
        write(o);
    }

    /**
     * Tailles des blocs du prompt Orion (mesure, pas d'optimisation).
     * system = persona assemble + system message tool-loop.
     */
    public static void orionPromptBreakdown(int systemChars, int toolsSchemaChars,
            int missionChars, int contextChars, int historyChars, int totalChars,
            String mode) {
        JSONObject o = base("orion_prompt_breakdown");
        put(o, "system_chars", Math.max(0, systemChars));
        put(o, "tools_schema_chars", Math.max(0, toolsSchemaChars));
        put(o, "mission_chars", Math.max(0, missionChars));
        put(o, "context_chars", Math.max(0, contextChars));
        put(o, "history_chars", Math.max(0, historyChars));
        put(o, "total_chars", Math.max(0, totalChars));
        put(o, "mode", mode);
        write(o);
    }

    /**
     * Ventilation de context_chars (mesure seule).
     * rag | project | targeted (related) | risk | docs .md | fichier joint.
     */
    public static void orionContextBreakdown(int ragChars, int projectChars,
            int targetedChars, int riskChars, int docsMdChars, int attachedChars,
            String mode) {
        JSONObject o = base("orion_context_breakdown");
        put(o, "rag_chars", Math.max(0, ragChars));
        put(o, "project_chars", Math.max(0, projectChars));
        put(o, "targeted_chars", Math.max(0, targetedChars));
        put(o, "risk_chars", Math.max(0, riskChars));
        put(o, "docs_md_chars", Math.max(0, docsMdChars));
        put(o, "attached_chars", Math.max(0, attachedChars));
        put(o, "mode", mode);
        write(o);
    }

    /** Boucle lint après write_file — demande de correction (max 2). */
    public static void orionLintLoop(String filename, int errorCount, int attempt,
            boolean askFix) {
        JSONObject o = base("orion_lint_loop");
        put(o, "filename", filename);
        put(o, "error_count", Math.max(0, errorCount));
        put(o, "attempt", Math.max(0, attempt));
        put(o, "ask_fix", askFix);
        write(o);
    }

    /** Échec d'indexation JavaParser Orion (fichier malformé, etc.). */
    public static void orionIndexError(String filename, String message) {
        JSONObject o = base("orion_index_error");
        put(o, "filename", filename);
        put(o, "message", message);
        write(o);
    }

    private static String truncateForTrace(String reply) {
        if (reply == null) return null;
        return reply.length() > 40 ? reply.substring(0, 40) + "…[redacted]" : reply;
    }

    /** Erreur backend / réseau / modèle. stage = "llm" | "tool" | "tts" | "stt" */
    public static void error(String stage, String message) {
        JSONObject o = base("error");
        put(o, "stage", stage);
        put(o, "message", message);
        write(o);
    }

    /** Étape de la boucle agentique (multi-hop). */
    public static void agenticStep(long requestId, int stepIndex, int toolStepCount,
            boolean allowMoreTools, int sameToolSameArgsCount, int maxToolsPerTurn) {
        JSONObject o = base("agentic_step");
        put(o, "request_id", requestId);
        put(o, "step_index", stepIndex);
        put(o, "tool_step_count", toolStepCount);
        put(o, "allow_more_tools", allowMoreTools);
        put(o, "same_tool_same_args_count", sameToolSameArgsCount);
        put(o, "max_tools_per_turn", maxToolsPerTurn);
        write(o);
    }

    /** Appel d'outil agentique bloqué (doublon, cap, search déjà utilisé). */
    public static void agenticBlocked(long requestId, String tool, String reason,
            int toolStepCount, int sameToolSameArgsCount) {
        JSONObject o = base("agentic_blocked");
        put(o, "request_id", requestId);
        put(o, "tool", tool);
        put(o, "reason", reason);
        put(o, "tool_step_count", toolStepCount);
        put(o, "same_tool_same_args_count", sameToolSameArgsCount);
        write(o);
    }

    /**
     * LE plus important. Photo de l'historique juste AVANT l'envoi au LLM.
     * C'est ce qui permet à DiagReport de voir un tour user orphalin ou un doublon.
     */
    public static void historySnapshot(List<ChatBackend.Turn> history, String label) {
        JSONObject o = base("history");
        put(o, "label", label);
        put(o, "size", history != null ? history.size() : 0);
        JSONArray arr = new JSONArray();
        if (history != null) {
            for (ChatBackend.Turn t : history) {
                JSONObject turn = new JSONObject();
                put(turn, "role", t.fromUser ? "user" : "assistant");
                put(turn, "text", t.text);
                arr.put(turn);
            }
        }
        try { o.put("turns", arr); } catch (Exception ignored) {}
        write(o);
    }

    /** Changement d'état de la conversation (utile même sans PegaseSession). */
    public static void state(String from, String to) {
        JSONObject o = base("state");
        put(o, "from", from);
        put(o, "to", to);
        write(o);
    }

    /** Début d'une suite de mini-tests (marquée stress). */
    public static void scriptSuiteStart(String suiteId, int stepCount, long cooldownMs,
            int memoryBackupTurns) {
        JSONObject o = base("script_suite_start");
        put(o, "suite_id", suiteId);
        put(o, "steps", stepCount);
        put(o, "cooldown_ms", cooldownMs);
        put(o, "memory_cleared", true);
        put(o, "memory_backup_turns", memoryBackupTurns);
        write(o);
    }

    /** Progression d'un scénario : status = start | ok | error | timeout | skipped */
    public static void scriptStep(String scriptId, int index, String query, String status,
            String detail) {
        JSONObject o = base("script_step");
        put(o, "script_id", scriptId);
        put(o, "index", index);
        put(o, "query", query);
        put(o, "status", status);
        if (detail != null && !detail.isEmpty()) {
            put(o, "detail", detail);
        }
        write(o);
    }

    /** Fin de suite — okSteps = réponses reçues, failedSteps = erreurs + timeouts. */
    public static void scriptSuiteEnd(String suiteId, int okSteps, int failedSteps, long durationMs) {
        JSONObject o = base("script_suite_end");
        put(o, "suite_id", suiteId);
        put(o, "ok_steps", okSteps);
        put(o, "failed_steps", failedSteps);
        put(o, "duration_ms", durationMs);
        write(o);
    }

    // ------------------------------------------------------------- interne

    private static JSONObject base(String type) {
        JSONObject o = new JSONObject();
        put(o, "t", System.currentTimeMillis());
        put(o, "type", type);
        if (stress) put(o, "stress", true);
        return o;
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            if (value instanceof String && redact && isTextField(key)) {
                String s = (String) value;
                o.put(key, "sha1:" + Integer.toHexString(s.hashCode()) + ":" + s.length());
                return;
            }
            if (value instanceof String && ((String) value).length() > PREVIEW_CHARS) {
                o.put(key, ((String) value).substring(0, PREVIEW_CHARS) + "…");
                return;
            }
            o.put(key, value);
        } catch (Exception ignored) {}
    }

    private static boolean isTextField(String key) {
        return "text".equals(key) || "reply".equals(key) || "params".equals(key);
    }

    private static void write(JSONObject o) {
        if (!enabled || file == null) return;
        final String line = o.toString();
        final JSONObject snap = o;
        IO.execute(() -> {
            try {
                if (file.length() > MAX_BYTES) return;   // on ne tourne pas : on arrête, c'est un test borné
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
                // Agrégat journalier (survit à clear) — jamais bloquant
                try {
                    DiagDayAggregate.record(snap);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        });
    }
}
