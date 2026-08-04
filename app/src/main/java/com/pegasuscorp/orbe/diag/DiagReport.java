package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lit trace.jsonl, détecte les anomalies connues, écrit un rapport JSON exploitable.
 *
 * Les détecteurs correspondent aux bugs réels ou plausibles de l'architecture actuelle.
 * Chaque anomalie remonte avec son horodatage et son contexte : tu sauras quoi corriger
 * ET tu auras un cas de test à écrire.
 */
public final class DiagReport {

    private DiagReport() {}

    /** @return le fichier rapport écrit dans filesDir/diag/ */
    public static File generate(Context ctx) throws Exception {
        List<JSONObject> all = readEvents(Trace.file());
        List<JSONObject> real = withoutStress(all);
        List<JSONObject> stressEvents = onlyStress(all);

        JSONObject report = new JSONObject();
        report.put("generated_at", System.currentTimeMillis());
        report.put("events_total", all.size());
        report.put("events_real", real.size());
        report.put("events_stress", stressEvents.size());
        report.put("period", period(real));
        report.put("stats", stats(real));

        JSONArray anomalies = buildAnomalies(real);
        report.put("anomalies", anomalies);
        report.put("anomalies_total", anomalies.length());

        JSONObject hallu = countHallucinations(real, anomalies);
        report.put("hallucinations_detected", hallu.optInt("total", 0));
        report.put("hallucinations_by_type", hallu.optJSONObject("by_type"));

        if (!stressEvents.isEmpty()) {
            JSONObject stress = new JSONObject();
            stress.put("events_total", stressEvents.size());
            stress.put("period", period(stressEvents));
            stress.put("stats", stats(stressEvents));
            JSONArray stressAnomalies = buildAnomalies(stressEvents);
            stress.put("anomalies", stressAnomalies);
            stress.put("anomalies_total", stressAnomalies.length());
            report.put("stress", stress);
        }

        File out = new File(Trace.file().getParentFile(), "orbe-diag-report.json");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Génère puis ouvre le partage Android (mail, Drive, Files...). */
    public static void generateAndShare(Context ctx) throws Exception {
        File report = generate(ctx);
        shareReportFile(ctx, report);
    }

    /**
     * Rapport après mini-tests : anomalies comptées sur la session stress uniquement.
     */
    public static DiagScriptResult generateScriptResult(Context ctx, int stepsTotal, int stepsOk,
            int stepsError, int stepsTimeout, int stepsSkipped, long durationMs) throws Exception {
        File report = generate(ctx);
        JSONObject json = new JSONObject(new String(
                java.nio.file.Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
        int eventsStress = json.optInt("events_stress", 0);
        int anomaliesStress = 0;
        long p95 = -1;
        JSONObject stress = json.optJSONObject("stress");
        if (stress != null) {
            anomaliesStress = stress.optInt("anomalies_total", 0);
            JSONObject stats = stress.optJSONObject("stats");
            if (stats != null) {
                p95 = stats.optLong("llm_latency_p95_ms", -1);
            }
        }
        return new DiagScriptResult(stepsTotal, stepsOk, stepsError, stepsTimeout, stepsSkipped,
                anomaliesStress, eventsStress, p95, durationMs, report);
    }

    private static void shareReportFile(Context ctx, File report) throws Exception {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", report);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(share, "Rapport de diagnostic Orbe")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ------------------------------------------------------------ détecteurs

    /**
     * LE bug du jour : le même outil relancé avec les mêmes paramètres alors que
     * l'utilisateur a dit AUTRE CHOSE entre-temps. Signature d'une boucle.
     */
    private static List<JSONObject> detectRepeatedAction(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        String lastTool = null, lastParams = null, lastUserMsg = null;
        String userSinceLastTool = null;

        for (JSONObject e : events) {
            String type = e.optString("type");
            if ("user_message".equals(type)) {
                userSinceLastTool = e.optString("text");
                continue;
            }
            if (!"tool_start".equals(type)) continue;

            String tool = e.optString("tool");
            String params = e.optString("params");
            boolean sameAction = tool.equals(lastTool) && params.equals(lastParams);
            boolean userChangedRequest = userSinceLastTool != null
                    && !userSinceLastTool.equals(lastUserMsg);

            if (sameAction && userChangedRequest) {
                out.add(anomaly("repeated_action", "high", e.optLong("t"),
                        "Outil « " + tool + " » ré-exécuté à l'identique alors que la demande "
                                + "utilisateur avait changé — boucle de répétition.",
                        map("tool", tool, "params", params,
                                "previous_user", lastUserMsg, "new_user", userSinceLastTool)));
            }
            lastTool = tool;
            lastParams = params;
            lastUserMsg = userSinceLastTool;
        }
        return out;
    }

    /** Historique envoyé au LLM qui se termine par un tour user = demande vue comme "en attente". */
    private static List<JSONObject> detectDanglingUserTurn(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!"history".equals(e.optString("type"))) continue;
            String label = e.optString("label");
            if ("before_send".equals(label) || "before_agentic_step".equals(label)) continue;
            JSONArray turns = e.optJSONArray("turns");
            if (turns == null || turns.length() == 0) continue;
            JSONObject last = turns.optJSONObject(turns.length() - 1);
            if (last != null && "user".equals(last.optString("role"))) {
                out.add(anomaly("dangling_user_turn", "high", e.optLong("t"),
                        "L'historique se termine par un tour utilisateur : le LLM va traiter "
                                + "cette demande comme non répondue et la ré-exécuter.",
                        map("label", e.optString("label"),
                                "last_user_text", last.optString("text"),
                                "history_size", String.valueOf(turns.length()))));
            }
        }
        return out;
    }

    /** Deux tours user consécutifs dans l'historique = un tour assistant a été perdu ou écrasé. */
    private static List<JSONObject> detectDuplicateUserTurn(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!"history".equals(e.optString("type"))) continue;
            JSONArray turns = e.optJSONArray("turns");
            if (turns == null) continue;
            for (int i = 1; i < turns.length(); i++) {
                JSONObject a = turns.optJSONObject(i - 1);
                JSONObject b = turns.optJSONObject(i);
                if (a == null || b == null) continue;
                if ("user".equals(a.optString("role")) && "user".equals(b.optString("role"))) {
                    out.add(anomaly("consecutive_user_turns", "high", e.optLong("t"),
                            "Deux tours utilisateur consécutifs : une réponse assistant a été "
                                    + "écrasée ou jamais enregistrée.",
                            map("first", a.optString("text"), "second", b.optString("text"))));
                    break;
                }
            }
        }
        return out;
    }

    /** Le LLM a tenté un outil mais le JSON était invalide (looksLikeToolAttempt). */
    private static List<JSONObject> detectMalformedTool(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!"llm_reply".equals(e.optString("type"))) continue;
            if (!e.optBoolean("malformed_tool")) continue;
            out.add(anomaly("malformed_tool_json", "medium", e.optLong("t"),
                    "Le modèle a produit un appel d'outil non parsable — argument fort pour "
                            + "passer au function calling natif.",
                    map("backend", e.optString("backend"), "raw", e.optString("text"))));
        }
        return out;
    }

    /** Message utilisateur sans aucune réponse ni outil dans les 60 s : requête perdue. */
    private static List<JSONObject> detectUnanswered(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            JSONObject e = events.get(i);
            if (!"user_message".equals(e.optString("type"))) continue;
            long t = e.optLong("t");
            boolean answered = false;
            for (int j = i + 1; j < events.size(); j++) {
                JSONObject n = events.get(j);
                if (n.optLong("t") - t > 60_000) break;
                String ty = n.optString("type");
                if ("llm_reply".equals(ty) || "tool_end".equals(ty) || "error".equals(ty)
                        || "bureau_edit".equals(ty) || "bureau_action".equals(ty)) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                out.add(anomaly("unanswered_request", "medium", t,
                        "Message utilisateur sans réponse ni erreur dans les 60 s — "
                                + "requête perdue (annulation par requestId ? activité détruite ?).",
                        map("text", e.optString("text"), "source", e.optString("source"))));
            }
        }
        return out;
    }

    /** Formulations par lesquelles le modèle prétend avoir agi. */
    private static final String[] CLAIMS = {
            "c'est noté", "c'est fait", "j'ai ajouté", "j'ai noté", "je l'ai ajouté",
            "je l'ai noté", "c'est enregistré", "c'est ajouté", "j'ai enregistré",
            "j'ai mis", "je l'ai mis", "c'est bien noté", "voilà, c'est"
    };

    /**
     * L'assistant AFFIRME avoir agi, mais aucun outil n'a tourné.
     * Le modèle a produit de la prose au lieu du JSON d'outil : il ment sans le savoir.
     */
    private static List<JSONObject> detectPhantomAction(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            JSONObject e = events.get(i);
            if (!"llm_reply".equals(e.optString("type"))) continue;
            if (e.optBoolean("ephemeral")) continue;
            if (e.optBoolean("is_tool_call")) continue;
            if (e.optBoolean("malformed_tool")) continue;

            String text = e.optString("text", "").toLowerCase(java.util.Locale.ROOT);
            String claim = null;
            for (String c : CLAIMS) {
                if (text.contains(c)) { claim = c; break; }
            }
            if (claim == null) continue;

            boolean toolRan = false;
            for (int j = i + 1; j < events.size(); j++) {
                String ty = events.get(j).optString("type");
                if ("user_message".equals(ty)) break;
                if ("tool_end".equals(ty) || "tool_start".equals(ty)) { toolRan = true; break; }
            }
            if (toolRan) continue;

            String askedFor = "";
            for (int j = i - 1; j >= 0; j--) {
                if ("user_message".equals(events.get(j).optString("type"))) {
                    askedFor = events.get(j).optString("text");
                    break;
                }
            }

            out.add(anomaly("phantom_action", "high", e.optLong("t"),
                    "L'assistant affirme avoir agi (« " + claim + " ») alors qu'aucun outil "
                            + "n'a été exécuté. Le modèle a répondu en prose au lieu d'émettre "
                            + "le JSON d'outil — l'action n'a jamais eu lieu.",
                    map("claim", claim, "reply", e.optString("text"),
                            "user_request", askedFor, "backend", e.optString("backend"))));
        }
        return out;
    }

    /**
     * Pendant une suite mini_diag_v2+, le 1er before_send doit partir d'un historique vide
     * (seulement le tour user courant).
     */
    static List<JSONObject> detectScriptHistoryPollution(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        boolean inSuite = false;
        boolean checkedFirstSend = false;
        for (JSONObject e : events) {
            String type = e.optString("type");
            if ("script_suite_start".equals(type)) {
                inSuite = e.optBoolean("memory_cleared", false)
                        || "mini_diag_v2".equals(e.optString("suite_id"));
                checkedFirstSend = false;
                continue;
            }
            if ("script_suite_end".equals(type)) {
                inSuite = false;
                continue;
            }
            if (!inSuite || checkedFirstSend) continue;
            if (!"history".equals(type) || !"before_send".equals(e.optString("label"))) continue;
            checkedFirstSend = true;
            int size = e.optInt("size", 0);
            if (size > DiagScripts.FIRST_SEND_MAX_HISTORY) {
                out.add(anomaly("script_history_polluted", "high", e.optLong("t"),
                        "Le mini-test n'a pas démarré sur une mémoire vide : l'historique "
                                + "persistant fuit encore dans le prompt.",
                        map("history_size", String.valueOf(size),
                                "expected_max", String.valueOf(DiagScripts.FIRST_SEND_MAX_HISTORY))));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- stats

    private static JSONArray buildAnomalies(List<JSONObject> events) {
        JSONArray anomalies = new JSONArray();
        for (JSONObject a : detectRepeatedAction(events)) anomalies.put(a);
        for (JSONObject a : detectDanglingUserTurn(events)) anomalies.put(a);
        for (JSONObject a : detectDuplicateUserTurn(events)) anomalies.put(a);
        for (JSONObject a : detectMalformedTool(events)) anomalies.put(a);
        for (JSONObject a : detectUnanswered(events)) anomalies.put(a);
        for (JSONObject a : detectPhantomAction(events)) anomalies.put(a);
        for (JSONObject a : detectScriptHistoryPollution(events)) anomalies.put(a);
        for (JSONObject a : detectBureauLlmFallback(events)) anomalies.put(a);
        for (JSONObject a : detectBureauLlmError(events)) anomalies.put(a);
        for (JSONObject a : detectPastReferenceHallucination(events)) anomalies.put(a);
        for (JSONObject a : detectCopilotMatcherMiss(events)) anomalies.put(a);
        for (JSONObject a : detectCopilotWhitelistBlock(events)) anomalies.put(a);
        for (JSONObject a : detectCopilotConfirmStale(events)) anomalies.put(a);
        for (JSONObject a : detectCopilotA11yDown(events)) anomalies.put(a);
        return anomalies;
    }

    /** Cible UI introuvable (events {@code copilot_ui} kind=matcher_miss). */
    static List<JSONObject> detectCopilotMatcherMiss(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        if (events == null) return out;
        for (JSONObject e : events) {
            if (!isCopilotKind(e, "matcher_miss")) continue;
            String pkg = e.optString("pkg", "");
            String target = e.optString("target", "");
            out.add(anomaly("copilot_matcher_miss", "medium", e.optLong("t"),
                    "Copilote : élément introuvable"
                            + (target.isEmpty() ? "" : " (« " + target + " »)")
                            + (pkg.isEmpty() ? "" : " sur " + pkg) + ".",
                    map("pkg", pkg, "target", target,
                            "reason", e.optString("reason"),
                            "detail", e.optString("detail"))));
        }
        return out;
    }

    /** App hors whitelist copilote. */
    static List<JSONObject> detectCopilotWhitelistBlock(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        if (events == null) return out;
        for (JSONObject e : events) {
            if (!isCopilotKind(e, "whitelist_block")) continue;
            String pkg = e.optString("pkg", "");
            out.add(anomaly("copilot_whitelist_block", "low", e.optLong("t"),
                    "Copilote : app non autorisée"
                            + (pkg.isEmpty() ? "." : " (" + pkg + ")."),
                    map("pkg", pkg, "reason", e.optString("reason"))));
        }
        return out;
    }

    /**
     * Confirmation Oui/Non sans réponse dans ~2 min
     * (confirm_ask sans confirm_ok / confirm_cancel appariés).
     */
    static List<JSONObject> detectCopilotConfirmStale(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        if (events == null || events.isEmpty()) return out;
        final long windowMs = 120_000L;
        List<JSONObject> pending = new ArrayList<>();
        long endT = events.get(events.size() - 1).optLong("t", System.currentTimeMillis());
        for (JSONObject e : events) {
            if (!"copilot_ui".equals(e.optString("type"))) continue;
            String kind = e.optString("kind", "");
            if ("confirm_ask".equals(kind)) {
                pending.add(e);
            } else if ("confirm_ok".equals(kind) || "confirm_cancel".equals(kind)) {
                if (!pending.isEmpty()) pending.remove(0);
            }
        }
        for (JSONObject ask : pending) {
            long t = ask.optLong("t");
            if (endT - t < windowMs) continue;
            out.add(anomaly("copilot_confirm_stale", "medium", t,
                    "Copilote : confirmation Oui/Non sans réponse"
                            + (ask.optString("target").isEmpty() ? "."
                            : " (« " + ask.optString("target") + " »)."),
                    map("pkg", ask.optString("pkg"),
                            "target", ask.optString("target"),
                            "detail", ask.optString("detail"))));
        }
        return out;
    }

    /** Service d'accessibilité off / déconnecté. */
    static List<JSONObject> detectCopilotA11yDown(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        if (events == null) return out;
        for (JSONObject e : events) {
            if (!"copilot_ui".equals(e.optString("type"))) continue;
            String kind = e.optString("kind", "");
            if (!"a11y_unavailable".equals(kind) && !"a11y_disconnected".equals(kind)) {
                continue;
            }
            out.add(anomaly("copilot_a11y_down", "high", e.optLong("t"),
                    "Copilote : accessibilité indisponible ("
                            + e.optString("reason", kind) + ").",
                    map("kind", kind, "reason", e.optString("reason"),
                            "detail", e.optString("detail"))));
        }
        return out;
    }

    private static boolean isCopilotKind(JSONObject e, String kind) {
        return e != null
                && "copilot_ui".equals(e.optString("type"))
                && kind.equals(e.optString("kind"));
    }

    /**
     * Affirmations sur le passé sans source — events {@code reasoning_card}
     * ou {@code bureau_edit} avec {@code potentialHallucination=true}.
     */
    static List<JSONObject> detectPastReferenceHallucination(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            String type = e.optString("type");
            if ("reasoning_card".equals(type) && e.optBoolean("potentialHallucination", false)) {
                String reason = e.optString("hallucination_reason", "");
                if (reason.isEmpty()) {
                    reason = "Affirmation sur le passé sans source RAG / outil fiable.";
                }
                out.add(anomaly("past_reference_no_source", "high", e.optLong("t"),
                        reason,
                        map("intent", e.optString("intent"),
                                "backend", e.optString("backend"),
                                "context_chunks", String.valueOf(e.optInt("context_chunks", 0)),
                                "cheminement", e.optString("cheminement"),
                                "tools_count", String.valueOf(e.optInt("tools_count", 0)))));
            } else if ("bureau_edit".equals(type)
                    && e.optBoolean("potentialHallucination", false)) {
                out.add(anomaly("past_reference_no_source", "high", e.optLong("t"),
                        "Réponse bureau avec référence au passé inventée (0 chunk RAG).",
                        map("speak", e.optString("speak"),
                                "fallback", String.valueOf(e.optBoolean("fallback", false)))));
            }
        }
        return out;
    }

    /**
     * Compte hallucinations (passé sans source + fantômes / phantom_action).
     */
    static JSONObject countHallucinations(List<JSONObject> events, JSONArray anomalies)
            throws Exception {
        int past = 0;
        int phantom = 0;
        if (events != null) {
            for (JSONObject e : events) {
                String type = e.optString("type");
                if ("reasoning_card".equals(type) && e.optBoolean("potentialHallucination", false)) {
                    past++;
                } else if ("bureau_edit".equals(type)
                        && e.optBoolean("potentialHallucination", false)) {
                    past++;
                } else if ("phantom_blocked".equals(type)) {
                    phantom++;
                }
            }
        }
        if (anomalies != null) {
            for (int i = 0; i < anomalies.length(); i++) {
                JSONObject a = anomalies.optJSONObject(i);
                if (a == null) continue;
                String t = a.optString("type");
                if ("phantom_action".equals(t)) phantom++;
            }
        }
        JSONObject byType = new JSONObject();
        byType.put("past_reference_no_source", past);
        byType.put("phantom_action", phantom);
        JSONObject out = new JSONObject();
        out.put("total", past + phantom);
        out.put("by_type", byType);
        return out;
    }

    /** LLM bureau indisponible — repli local utilisé (Pégase n'a pas vraiment édité via le modèle). */
    static List<JSONObject> detectBureauLlmFallback(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!"bureau_edit".equals(e.optString("type"))) continue;
            if (!e.optBoolean("fallback", false)) continue;
            out.add(anomaly("bureau_llm_fallback", "medium", e.optLong("t"),
                    "Le bureau a utilisé le repli local — le LLM était indisponible ou en erreur. "
                            + "Vérifier clé API, réseau ou timeout (10 s).",
                    map("markdown_chars", String.valueOf(e.optInt("markdown_chars", 0)),
                            "speak", e.optString("speak"))));
        }
        return out;
    }

    /** Erreur LLM sur appel éphémère bureau (timeout, HTTP…). */
    private static List<JSONObject> detectBureauLlmError(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!"llm_reply".equals(e.optString("type"))) continue;
            if (!e.optBoolean("ephemeral", false)) continue;
            if (!"bureau".equals(e.optString("channel"))) continue;
            String text = e.optString("text", "");
            if (!text.startsWith("[error]")) continue;
            out.add(anomaly("bureau_llm_error", "medium", e.optLong("t"),
                    "Échec LLM bureau (Markdown ou canvas) — latence ou backend.",
                    map("backend", e.optString("backend"),
                            "error", text,
                            "latency_ms", String.valueOf(e.optLong("latency_ms", -1)),
                            "prompt_chars", String.valueOf(e.optInt("prompt_chars", 0)))));
        }
        return out;
    }

    private static boolean isStress(JSONObject e) {
        return e.optBoolean("stress", false);
    }

    private static List<JSONObject> withoutStress(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (!isStress(e)) out.add(e);
        }
        return out;
    }

    private static List<JSONObject> onlyStress(List<JSONObject> events) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) {
            if (isStress(e)) out.add(e);
        }
        return out;
    }

    private static JSONObject stats(List<JSONObject> events) throws Exception {
        Map<String, Integer> toolUse = new HashMap<>();
        Map<String, Integer> errorsByStage = new HashMap<>();
        List<Long> latencies = new ArrayList<>();
        int userMessages = 0, toolFailures = 0;
        int bureauActions = 0, bureauEdits = 0, bureauFallbacks = 0, ephemeralLlm = 0;
        int halluPast = 0;

        for (JSONObject e : events) {
            switch (e.optString("type")) {
                case "user_message": userMessages++; break;
                case "bureau_action": bureauActions++; break;
                case "bureau_edit":
                    bureauEdits++;
                    if (e.optBoolean("fallback", false)) bureauFallbacks++;
                    if (e.optBoolean("potentialHallucination", false)) halluPast++;
                    break;
                case "reasoning_card":
                    if (e.optBoolean("potentialHallucination", false)) halluPast++;
                    break;
                case "tool_start":
                    toolUse.merge(e.optString("tool"), 1, Integer::sum);
                    break;
                case "tool_end":
                    if (!e.optBoolean("ok", true)) toolFailures++;
                    break;
                case "llm_reply":
                    long ms = e.optLong("latency_ms", -1);
                    if (ms > 0) latencies.add(ms);
                    if (e.optBoolean("ephemeral", false)) ephemeralLlm++;
                    break;
                case "error":
                    errorsByStage.merge(e.optString("stage"), 1, Integer::sum);
                    break;
            }
        }

        Collections.sort(latencies);
        JSONObject s = new JSONObject();
        s.put("user_messages", userMessages);
        s.put("bureau_actions", bureauActions);
        s.put("bureau_edits", bureauEdits);
        s.put("bureau_fallbacks", bureauFallbacks);
        s.put("ephemeral_llm_calls", ephemeralLlm);
        s.put("tool_failures", toolFailures);
        s.put("hallucinations_past_reference", halluPast);
        s.put("tool_usage", new JSONObject(toolUse));
        s.put("errors_by_stage", new JSONObject(errorsByStage));
        s.put("llm_latency_p50_ms", percentile(latencies, 0.50));
        s.put("llm_latency_p95_ms", percentile(latencies, 0.95));
        return s;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.min(sorted.size() - 1, Math.round(p * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    private static JSONObject period(List<JSONObject> events) throws Exception {
        JSONObject p = new JSONObject();
        if (events.isEmpty()) return p;
        p.put("from", events.get(0).optLong("t"));
        p.put("to", events.get(events.size() - 1).optLong("t"));
        return p;
    }

    // -------------------------------------------------------------- helpers

    private static JSONObject anomaly(String type, String severity, long at,
                                      String explanation, Map<String, String> context) {
        JSONObject a = new JSONObject();
        try {
            a.put("type", type);
            a.put("severity", severity);
            a.put("at", at);
            a.put("explanation", explanation);
            a.put("context", new JSONObject(context));
        } catch (Exception ignored) {}
        return a;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    private static List<JSONObject> readEvents(File f) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (Exception ignored) {}
            }
        }
        return out;
    }
}
