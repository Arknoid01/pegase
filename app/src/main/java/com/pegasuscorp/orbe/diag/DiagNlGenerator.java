package com.pegasuscorp.orbe.diag;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Synthèses NL à partir des events / rapport diag
 * (summary, weekly, hesitations, failures).
 * <p>
 * Deux registres : {@link #synthesizeSummary} / {@link #synthesizeWeekly} (technique,
 * analyze / Cursor) et {@link #synthesizeSummarySimple} / {@link #synthesizeWeeklySimple}
 * (bilan auto, langage courant).
 */
public final class DiagNlGenerator {

    private static final long SLOW_MS = 5_000L;
    private static final int MAX_ITEMS = 8;

    private DiagNlGenerator() {}

    static String synthesizeSummary(List<JSONObject> events, JSONObject report) {
        List<JSONObject> real = DiagParser.withoutStress(events);
        if (real.isEmpty()) {
            return "Je n'ai pas encore de traces pour cette session — "
                    + "rien à diagnostiquer pour l'instant.";
        }

        ChannelStats voice = new ChannelStats("voix");
        ChannelStats text = new ChannelStats("texte");
        ChannelStats bureau = new ChannelStats("bureau");
        int toolFails = 0;
        int phantom = 0;
        int slow = 0;
        int hallucinations = 0;
        List<String> halluSamples = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (JSONObject e : real) {
            String type = e.optString("type");
            switch (type) {
                case "user_message": {
                    channelOf(e.optString("source"), voice, text, bureau).messages++;
                    break;
                }
                case "llm_reply": {
                    long ms = e.optLong("latency_ms", -1);
                    if (ms > 0) {
                        latencies.add(ms);
                        if (ms >= SLOW_MS) slow++;
                    }
                    ChannelStats ch = channelOf(e.optString("channel",
                            e.optBoolean("ephemeral") ? "bureau" : "text"), voice, text, bureau);
                    ch.llmReplies++;
                    if (e.optString("text", "").startsWith("[error]")) ch.errors++;
                    break;
                }
                case "tool_end":
                    if (!e.optBoolean("ok", true)) {
                        toolFails++;
                    }
                    break;
                case "bureau_action":
                    bureau.actions++;
                    break;
                case "bureau_edit":
                    bureau.edits++;
                    if (e.optBoolean("fallback", false)) bureau.fallbacks++;
                    if (e.optBoolean("potentialHallucination", false)) {
                        hallucinations++;
                        if (halluSamples.size() < 3) {
                            String speak = preview(e.optString("speak"), 50);
                            halluSamples.add(speak.isEmpty() ? "bureau (passé inventé)" : speak);
                        }
                    }
                    break;
                case "reasoning_card":
                    if (e.optBoolean("potentialHallucination", false)) {
                        hallucinations++;
                        if (halluSamples.size() < 3) {
                            String reason = preview(e.optString("hallucination_reason"), 50);
                            String path = preview(e.optString("cheminement"), 60);
                            halluSamples.add(reason.isEmpty()
                                    ? (path.isEmpty() ? "passé sans source" : path)
                                    : reason);
                        }
                    }
                    break;
                case "phantom_blocked":
                    phantom++;
                    break;
                case "error":
                    channelOf("text", voice, text, bureau).errors++;
                    break;
                default:
                    break;
            }
        }

        Collections.sort(latencies);
        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        int anomalies = 0;
        if (report != null) {
            anomalies = report.optInt("anomalies_total", 0);
            hallucinations = Math.max(hallucinations,
                    report.optInt("hallucinations_detected", 0));
            JSONObject stats = report.optJSONObject("stats");
            if (stats != null) {
                if (p95 < 0) p95 = stats.optLong("llm_latency_p95_ms", -1);
                if (p50 < 0) p50 = stats.optLong("llm_latency_p50_ms", -1);
                toolFails = Math.max(toolFails, stats.optInt("tool_failures", 0));
            }
        }

        StringBuilder sb = new StringBuilder();
        int totalMsg = voice.messages + text.messages + bureau.messages;
        if (toolFails == 0 && phantom == 0 && anomalies == 0 && bureau.fallbacks == 0
                && slow == 0 && hallucinations == 0) {
            sb.append("Tout s'est bien passé sur cette session — ")
                    .append(totalMsg).append(totalMsg > 1 ? " messages" : " message")
                    .append(", 0 échec");
            if (p95 > 0) sb.append(", p95 à ").append(p95).append(" ms");
            sb.append('.');
        } else {
            sb.append("Bilan de la session : ")
                    .append(totalMsg).append(totalMsg > 1 ? " messages" : " message");
            if (toolFails > 0) sb.append(", ").append(toolFails)
                    .append(toolFails > 1 ? " échecs d'outils" : " échec d'outil");
            if (hallucinations > 0) sb.append(", ").append(hallucinations)
                    .append(hallucinations > 1
                            ? " hallucinations potentielles"
                            : " hallucination potentielle");
            if (anomalies > 0) sb.append(", ").append(anomalies)
                    .append(anomalies > 1 ? " anomalies" : " anomalie");
            if (p95 > 0) sb.append(", p95 ").append(p95).append(" ms");
            sb.append('.');
        }

        sb.append("\n\nPar canal :");
        appendChannelLine(sb, voice);
        appendChannelLine(sb, text);
        appendChannelLine(sb, bureau);

        if (hallucinations > 0) {
            sb.append("\n\nHallucinations potentielles : ").append(hallucinations);
            for (String sample : halluSamples) {
                sb.append("\n— ").append(sample);
            }
        }
        if (phantom > 0) {
            sb.append("\nFiltres anti-fantôme : ").append(phantom)
                    .append(phantom > 1 ? " blocages" : " blocage").append('.');
        }
        if (slow > 0) {
            sb.append("\nRéponses lentes (≥5 s) : ").append(slow).append('.');
        }
        if (p50 > 0 && p95 > 0) {
            sb.append("\nLatence LLM : p50 ").append(p50).append(" ms, p95 ")
                    .append(p95).append(" ms.");
        }
        return sb.toString().trim();
    }

    static String synthesizeWeekly(List<DiagParser.DayBucket> days, int requestedDays) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bilan sur ").append(requestedDays).append(" jour")
                .append(requestedDays > 1 ? "s" : "")
                .append(" (").append(days.size()).append(" jour")
                .append(days.size() > 1 ? "s" : "")
                .append(" avec traces) :");

        int totalFails = 0;
        int totalPhantom = 0;
        int totalFallback = 0;
        int totalHallu = 0;
        int totalMsg = 0;
        int daysWithIssues = 0;

        for (DiagParser.DayBucket day : days) {
            DayStats st = statsOf(day.events);
            totalFails += st.toolFails;
            totalPhantom += st.phantom;
            totalFallback += st.bureauFallbacks;
            totalHallu += st.hallucinations;
            totalMsg += st.messages;
            boolean issues = st.hasIssues();
            if (issues) daysWithIssues++;

            sb.append("\n— ").append(day.label).append(" : ");
            if (!issues && st.messages == 0 && st.bureauActions == 0) {
                sb.append("trace vide.");
            } else if (!issues) {
                sb.append(st.messages).append(" msg, RAS");
                if (st.p95 > 0) sb.append(", p95 ").append(st.p95).append(" ms");
                sb.append('.');
            } else {
                List<String> parts = new ArrayList<>();
                parts.add(st.messages + " msg");
                if (st.toolFails > 0) parts.add(st.toolFails + " échec(s) outil");
                if (st.hesitations > 0) parts.add(st.hesitations + " hésitation(s)");
                if (st.hallucinations > 0) {
                    parts.add(st.hallucinations + " hallucination(s)");
                }
                if (st.phantom > 0) parts.add(st.phantom + " fantôme(s)");
                if (st.bureauFallbacks > 0) parts.add(st.bureauFallbacks + " repli(s) bureau");
                if (st.errors > 0) parts.add(st.errors + " erreur(s)");
                if (st.slow > 0) parts.add(st.slow + " réponse(s) lente(s)");
                sb.append(joinFr(parts)).append('.');
            }
        }

        sb.append("\n\nSynthèse : ");
        if (daysWithIssues == 0) {
            sb.append("la semaine est calme — ")
                    .append(totalMsg).append(" messages cumulés, aucun problème marquant.");
        } else {
            sb.append(daysWithIssues).append(" jour")
                    .append(daysWithIssues > 1 ? "s" : "")
                    .append(" avec friction");
            if (totalFails > 0) sb.append(", ").append(totalFails).append(" échec(s) outil");
            if (totalHallu > 0) {
                sb.append(", ").append(totalHallu).append(" hallucination(s)");
            }
            if (totalPhantom > 0) sb.append(", ").append(totalPhantom).append(" fantôme(s)");
            if (totalFallback > 0) sb.append(", ").append(totalFallback).append(" repli(s) bureau");
            sb.append(". Tu veux le détail d'un jour en particulier ?");
        }
        return sb.toString().trim();
    }

    /**
     * Bilan auto (brief / prefetch) — langage courant, groupements, tendance,
     * apps copilote, corrections déjà notées.
     */
    static String synthesizeWeeklySimple(Context ctx, List<DiagParser.DayBucket> days,
            int requestedDays) {
        StringBuilder sb = new StringBuilder();
        sb.append("Petit bilan sur ").append(requestedDays).append(" jour")
                .append(requestedDays > 1 ? "s" : "").append(" :");

        int daysWithIssues = 0;
        int totalMsg = 0;
        Map<String, Integer> issueBuckets = new LinkedHashMap<>();
        Map<String, Integer> failsByApp = new LinkedHashMap<>();
        DayStats latestIssues = null;
        DayStats previousIssues = null;

        for (int i = 0; i < days.size(); i++) {
            DiagParser.DayBucket day = days.get(i);
            DayStats st = statsOf(day.events);
            totalMsg += st.messages;
            collectSimpleIssueBuckets(day.events, issueBuckets, failsByApp);
            if (st.hasIssues()) {
                daysWithIssues++;
                // loadArchiveDays met aujourd'hui en premier → premier = plus récent.
                if (latestIssues == null) {
                    latestIssues = st;
                } else if (previousIssues == null) {
                    previousIssues = st;
                }
            }
            sb.append("\n— ").append(simpleDayLabel(day.label)).append(" : ");
            if (!st.hasIssues() && st.messages == 0 && st.bureauActions == 0
                    && st.copilotIssues == 0) {
                sb.append("rien de notable.");
            } else if (!st.hasIssues()) {
                sb.append(st.messages).append(" message")
                        .append(st.messages > 1 ? "s" : "").append(", tout va bien.");
            } else {
                sb.append(simpleDayFriction(st));
            }
        }

        sb.append("\n\n");
        if (daysWithIssues == 0) {
            sb.append("Rien de grave — ")
                    .append(totalMsg).append(" message")
                    .append(totalMsg > 1 ? "s" : "")
                    .append(" au total, pas de souci marquant.");
        } else {
            sb.append(daysWithIssues).append(" jour")
                    .append(daysWithIssues > 1 ? "s" : "")
                    .append(" un peu compliqué")
                    .append(daysWithIssues > 1 ? "s" : "").append('.');
            String trend = simpleTrend(latestIssues, previousIssues);
            if (!trend.isEmpty()) sb.append(' ').append(trend);
            appendGroupedSimpleIssues(sb, issueBuckets);
            appendFailsByApp(sb, failsByApp);
            appendKnownCorrections(sb, ctx, issueBuckets, failsByApp);
        }
        return sb.toString().trim();
    }

    /** Session du jour — registre simplifié (même vocabulaire que le bilan auto). */
    static String synthesizeSummarySimple(Context ctx, List<JSONObject> events,
            JSONObject report) {
        List<JSONObject> real = DiagParser.withoutStress(events);
        if (real.isEmpty()) {
            return "Pas encore de traces pour aujourd'hui — rien à raconter.";
        }
        DayStats st = statsOf(real);
        Map<String, Integer> issueBuckets = new LinkedHashMap<>();
        Map<String, Integer> failsByApp = new LinkedHashMap<>();
        collectSimpleIssueBuckets(real, issueBuckets, failsByApp);

        StringBuilder sb = new StringBuilder();
        if (!st.hasIssues()) {
            sb.append("Aujourd'hui ça a bien tourné — ")
                    .append(st.messages).append(" message")
                    .append(st.messages > 1 ? "s" : "").append(", pas de souci.");
        } else {
            sb.append("Bilan du jour : ").append(simpleDayFriction(st));
            appendGroupedSimpleIssues(sb, issueBuckets);
            appendFailsByApp(sb, failsByApp);
            appendKnownCorrections(sb, ctx, issueBuckets, failsByApp);
            if (st.slow > 0) {
                sb.append("\nQuelques réponses ont mis du temps (mineur).");
            }
        }
        if (report != null && report.optInt("anomalies_total", 0) > 0
                && issueBuckets.isEmpty()) {
            sb.append("\nQuelques frictions ont été notées automatiquement.");
        }
        return sb.toString().trim();
    }

    private static void collectSimpleIssueBuckets(List<JSONObject> events,
            Map<String, Integer> issueBuckets, Map<String, Integer> failsByApp) {
        if (events == null) return;
        for (JSONObject e : events) {
            String type = e.optString("type");
            if ("copilot_ui".equals(type)) {
                String kind = e.optString("kind", "");
                if ("matcher_miss".equals(kind)) {
                    bump(issueBuckets, "clic_manqué");
                    String pkg = e.optString("pkg", "");
                    if (!pkg.isEmpty()) bump(failsByApp, pkg);
                } else if ("whitelist_block".equals(kind)) {
                    bump(issueBuckets, "app_non_autorisée");
                } else if ("confirm_cancel".equals(kind)) {
                    bump(issueBuckets, "clic_annulé");
                } else if ("a11y_unavailable".equals(kind)
                        || "a11y_disconnected".equals(kind)) {
                    bump(issueBuckets, "accessibilité");
                }
            } else if ("tool_end".equals(type) && !e.optBoolean("ok", true)) {
                bump(issueBuckets, "outil_raté");
            } else if ("tool_failure_ctx".equals(type)) {
                bump(issueBuckets, "outil_raté");
            } else if ("phantom_blocked".equals(type)) {
                bump(issueBuckets, "fausse_annonce");
            } else if (("reasoning_card".equals(type) || "bureau_edit".equals(type))
                    && e.optBoolean("potentialHallucination", false)) {
                bump(issueBuckets, "souvenir_inventé");
            } else if ("bureau_edit".equals(type) && e.optBoolean("fallback", false)) {
                bump(issueBuckets, "repli_bureau");
            }
        }
    }

    private static void appendGroupedSimpleIssues(StringBuilder sb,
            Map<String, Integer> issueBuckets) {
        if (issueBuckets.isEmpty()) return;
        sb.append("\nEn gros :");
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(issueBuckets.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int n = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            if (n++ >= MAX_ITEMS) break;
            sb.append("\n— ").append(simpleIssuePhrase(e.getKey(), e.getValue()));
        }
    }

    private static void appendFailsByApp(StringBuilder sb, Map<String, Integer> failsByApp) {
        if (failsByApp.isEmpty()) return;
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(failsByApp.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map.Entry<String, Integer> top = sorted.get(0);
        if (top.getValue() < 1) return;
        sb.append("\nÇa rate surtout sur ").append(appLabel(top.getKey()));
        if (top.getValue() > 1) {
            sb.append(" (").append(top.getValue()).append(" fois)");
        }
        sb.append('.');
    }

    private static void appendKnownCorrections(StringBuilder sb, Context ctx,
            Map<String, Integer> issueBuckets, Map<String, Integer> failsByApp) {
        if (ctx == null) return;
        List<String> hints = new ArrayList<>();
        for (String k : issueBuckets.keySet()) hints.add(simpleIssueHint(k));
        for (String pkg : failsByApp.keySet()) {
            hints.add(appLabel(pkg));
            hints.add("copilote " + appLabel(pkg));
            hints.add(pkg);
        }
        List<String> known = new ArrayList<>();
        for (String hint : hints) {
            String match = CorrectionsStore.findPendingMatch(ctx, hint);
            if (match != null && !alreadyMentions(known, match)) {
                known.add(match);
            }
        }
        if (known.isEmpty()) return;
        sb.append("\nDéjà noté, pas encore réglé :");
        for (String k : known) {
            if (known.indexOf(k) >= 3) break;
            sb.append("\n— ").append(preview(k, 90));
        }
    }

    private static String simpleTrend(DayStats latest, DayStats previous) {
        if (latest == null || previous == null) return "";
        int a = latest.issueScore();
        int b = previous.issueScore();
        if (a > b + 1) return "Le souci s'aggrave un peu.";
        if (a + 1 < b) return "Ça s'améliore par rapport à avant.";
        return "Le même genre de souci persiste.";
    }

    private static String simpleDayFriction(DayStats st) {
        List<String> parts = new ArrayList<>();
        parts.add(st.messages + " message" + (st.messages > 1 ? "s" : ""));
        if (st.copilotIssues > 0) {
            parts.add(st.copilotIssues + " souci"
                    + (st.copilotIssues > 1 ? "s" : "") + " copilote");
        }
        if (st.toolFails > st.copilotIssues) {
            int other = st.toolFails - st.copilotIssues;
            if (other > 0) {
                parts.add(other + " outil" + (other > 1 ? "s" : "") + " qui a raté");
            }
        } else if (st.toolFails > 0 && st.copilotIssues == 0) {
            parts.add(st.toolFails + " truc" + (st.toolFails > 1 ? "s" : "") + " qui a raté");
        }
        if (st.phantom > 0) {
            parts.add(st.phantom > 1 ? "quelques fausses annonces bloquées"
                    : "une fausse annonce bloquée");
        }
        if (st.hallucinations > 0) {
            parts.add(st.hallucinations > 1
                    ? "des souvenirs inventés"
                    : "un souvenir inventé");
        }
        if (st.bureauFallbacks > 0) parts.add("repli bureau");
        if (st.errors > 0) parts.add("erreurs");
        return joinFr(parts) + ".";
    }

    private static String simpleIssuePhrase(String key, int count) {
        String base;
        switch (key) {
            case "clic_manqué":
                base = count > 1
                        ? count + " fois le même souci : je ne trouvais pas quoi cliquer"
                        : "je ne trouvais pas quoi cliquer";
                break;
            case "app_non_autorisée":
                base = "une appli n'était pas autorisée pour le copilote";
                break;
            case "clic_annulé":
                base = "un clic a été annulé";
                break;
            case "accessibilité":
                base = "le contrôle d'écran n'était pas prêt";
                break;
            case "outil_raté":
                base = count > 1
                        ? count + " fois un outil a raté"
                        : "un outil a raté";
                break;
            case "fausse_annonce":
                base = "j'allais dire que c'était fait sans l'avoir fait (corrigé)";
                break;
            case "souvenir_inventé":
                base = "j'ai inventé un souvenir — mineur, à surveiller";
                break;
            case "repli_bureau":
                base = "le bureau a dû se débrouiller sans le cloud";
                break;
            default:
                base = key;
        }
        return capitalize(base) + ".";
    }

    private static String simpleIssueHint(String key) {
        switch (key) {
            case "clic_manqué": return "copilote clic";
            case "accessibilité": return "accessibilité";
            case "outil_raté": return "outil";
            case "souvenir_inventé": return "hallucination";
            case "fausse_annonce": return "fantôme";
            default: return key.replace('_', ' ');
        }
    }

    private static String simpleDayLabel(String label) {
        if (label == null || label.isEmpty()) return "jour";
        return label;
    }

    static String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "une appli";
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("whatsapp")) return "WhatsApp";
        if (p.contains("chrome")) return "Chrome";
        if (p.contains("instagram")) return "Instagram";
        if (p.contains("telegram")) return "Telegram";
        if (p.contains("youtube")) return "YouTube";
        if (p.contains("spotify")) return "Spotify";
        if (p.contains("facebook") || p.contains("messenger")) return "Messenger";
        int dot = pkg.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < pkg.length()) {
            String last = pkg.substring(dot + 1);
            if (last.length() > 1) {
                return Character.toUpperCase(last.charAt(0)) + last.substring(1);
            }
            return last;
        }
        return pkg;
    }

    private static void bump(Map<String, Integer> map, String key) {
        if (key == null || key.isEmpty()) return;
        map.merge(key, 1, Integer::sum);
    }

    static String synthesizeHesitations(List<JSONObject> events) {
        List<JSONObject> real = DiagParser.withoutStress(events);
        List<String> lines = new ArrayList<>();

        for (JSONObject e : real) {
            String type = e.optString("type");
            if ("tool_hesitation".equals(type)) {
                lines.add(formatHesitationRich(e));
            } else if ("phantom_blocked".equals(type)) {
                String req = preview(e.optString("user_request"), 80);
                lines.add("j'allais affirmer une action sans l'avoir faite"
                        + (req.isEmpty() ? "" : " (demande : « " + req + " »)"));
            } else if ("agentic_blocked".equals(type)) {
                String tool = e.optString("tool", "outil");
                String reason = e.optString("reason", "bloqué");
                lines.add(tool + " : étape agentique bloquée (" + humanReason(reason) + ")");
            } else if ("llm_reply".equals(type) && e.optBoolean("malformed_tool")) {
                String backend = e.optString("backend", "LLM");
                lines.add("JSON d'outil malformé côté " + backend
                        + " — j'ai hésité / n'ai pas pu exécuter proprement");
            } else if (("reasoning_card".equals(type) || "bureau_edit".equals(type))
                    && e.optBoolean("potentialHallucination", false)) {
                String reason = preview(e.optString("hallucination_reason"), 70);
                if (reason.isEmpty()) {
                    reason = preview(e.optString("speak"), 70);
                }
                String path = preview(e.optString("cheminement"), 50);
                lines.add("affirmation sur le passé sans source"
                        + (reason.isEmpty() ? "" : " — " + reason)
                        + (path.isEmpty() ? "" : " [" + path + "]"));
            }
            if (lines.size() >= MAX_ITEMS) break;
        }

        if (lines.isEmpty()) {
            return "Aucune hésitation notable dans les traces récentes — "
                    + "je n'ai pas enregistré de fantôme bloqué ni d'appel d'outil malformé.";
        }
        StringBuilder sb = new StringBuilder("J'ai hésité ou mal interprété :");
        for (String line : lines) {
            sb.append("\n— ").append(line);
            if (!line.endsWith(".")) sb.append('.');
        }
        return sb.toString();
    }

    /**
     * Ex. « j'ai hésité sur notepad — tu parlais d'un projet futur… »
     */
    static String formatHesitationRich(JSONObject e) {
        String tool = e.optString("tool", "outil");
        if (tool.isEmpty() || "unknown".equals(tool)) tool = "un outil";
        String reason = e.optString("reason", "");
        String detail = e.optString("detail", "");
        String userMsg = e.optString("user_msg", "");
        StringBuilder sb = new StringBuilder();
        sb.append("j'ai hésité sur ").append(tool);
        if (!userMsg.isEmpty()) {
            sb.append(" — tu as dit « ").append(preview(userMsg, 80)).append(" »");
            if ("phantom_action".equals(reason)) {
                sb.append(" et j'ai cru pouvoir affirmer que c'était fait");
            } else if ("malformed_tool".equals(reason)) {
                sb.append(" mais le format d'appel était incorrect");
            } else if (!detail.isEmpty()) {
                sb.append(" (").append(preview(detail, 60)).append(')');
            }
        } else if (!detail.isEmpty()) {
            sb.append(" — ").append(preview(detail, 100));
        } else if (!reason.isEmpty()) {
            sb.append(" (").append(humanReason(reason)).append(')');
        }
        return sb.toString();
    }

    static String synthesizeFailures(List<JSONObject> events, JSONObject report) {
        List<JSONObject> real = DiagParser.withoutStress(events);
        List<String> lines = new ArrayList<>();
        Map<String, Integer> failByTool = new LinkedHashMap<>();

        for (JSONObject e : real) {
            String type = e.optString("type");
            if ("tool_failure_ctx".equals(type)) {
                String tool = e.optString("tool", "outil");
                failByTool.merge(tool, 1, Integer::sum);
                if (lines.size() < MAX_ITEMS) {
                    lines.add(formatFailureRich(e));
                }
            } else if ("tool_end".equals(type) && !e.optBoolean("ok", true)) {
                String tool = e.optString("tool", "outil");
                failByTool.merge(tool, 1, Integer::sum);
                String err = preview(e.optString("error"), 100);
                if (lines.size() < MAX_ITEMS) {
                    lines.add(tool + " a échoué"
                            + (err.isEmpty() ? "" : " : " + err));
                }
            } else if ("error".equals(type)) {
                String stage = e.optString("stage", "inconnu");
                String msg = preview(e.optString("message"), 100);
                if (lines.size() < MAX_ITEMS) {
                    lines.add("erreur " + stage
                            + (msg.isEmpty() ? "" : " — " + msg));
                }
            } else if ("llm_reply".equals(type)
                    && e.optString("text", "").startsWith("[error]")) {
                String channel = e.optString("channel",
                        e.optBoolean("ephemeral") ? "bureau" : "chat");
                String err = preview(e.optString("text").replaceFirst("^\\[error]\\s*", ""), 100);
                if (lines.size() < MAX_ITEMS) {
                    lines.add("LLM " + channel + " en erreur"
                            + (err.isEmpty() ? "" : " : " + err));
                }
            } else if ("bureau_edit".equals(type) && e.optBoolean("fallback", false)) {
                if (lines.size() < MAX_ITEMS) {
                    lines.add("bureau : repli local (LLM indisponible)"
                            + (e.optString("speak").isEmpty() ? ""
                            : " — " + preview(e.optString("speak"), 60)));
                }
            }
        }

        if (report != null) {
            JSONArray anomalies = report.optJSONArray("anomalies");
            if (anomalies != null) {
                for (int i = 0; i < anomalies.length() && lines.size() < MAX_ITEMS; i++) {
                    JSONObject a = anomalies.optJSONObject(i);
                    if (a == null) continue;
                    String at = a.optString("type");
                    if ("bureau_llm_fallback".equals(at) || "bureau_llm_error".equals(at)
                            || "unanswered_request".equals(at)
                            || "script_history_polluted".equals(at)
                            || "repeated_action".equals(at)) {
                        String expl = preview(a.optString("explanation"), 120);
                        if (!expl.isEmpty() && !alreadyMentions(lines, expl)) {
                            lines.add(humanAnomaly(at) + " : " + expl);
                        }
                    }
                }
            }
        }

        if (lines.isEmpty()) {
            return "Aucun échec enregistré dans les traces — outils et LLM ont répondu correctement.";
        }

        StringBuilder sb = new StringBuilder("Échecs récents :");
        if (!failByTool.isEmpty()) {
            sb.append(" (par outil : ");
            boolean first = true;
            for (Map.Entry<String, Integer> en : failByTool.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(en.getKey()).append('×').append(en.getValue());
            }
            sb.append(')');
        }
        for (String line : lines) {
            sb.append("\n— ").append(line);
            if (!line.endsWith(".")) sb.append('.');
        }
        return sb.toString();
    }

    static String formatFailureRich(JSONObject e) {
        String tool = e.optString("tool", "outil");
        if (tool.isEmpty()) tool = "outil";
        String reason = e.optString("reason", "");
        String detail = e.optString("detail", "");
        String userMsg = e.optString("user_msg", "");
        StringBuilder sb = new StringBuilder();
        sb.append(tool).append(" a échoué");
        if ("http_400_tool_validation".equals(reason)) {
            sb.append(" — validation outil côté API (HTTP 400)");
        } else if (!reason.isEmpty()) {
            sb.append(" (").append(humanReason(reason)).append(')');
        }
        if (!userMsg.isEmpty()) {
            sb.append(" — demande : « ").append(preview(userMsg, 60)).append(" »");
        }
        if (!detail.isEmpty() && !"http_400_tool_validation".equals(reason)) {
            sb.append(" : ").append(preview(detail, 80));
        } else if (!detail.isEmpty() && userMsg.isEmpty()) {
            sb.append(" : ").append(preview(detail, 80));
        }
        return sb.toString();
    }

    static DayStats statsOf(List<JSONObject> events) {
        DayStats st = new DayStats();
        List<Long> latencies = new ArrayList<>();
        for (JSONObject e : events) {
            switch (e.optString("type")) {
                case "user_message":
                    st.messages++;
                    break;
                case "llm_reply": {
                    long ms = e.optLong("latency_ms", -1);
                    if (ms > 0) {
                        latencies.add(ms);
                        if (ms >= SLOW_MS) st.slow++;
                    }
                    if (e.optString("text", "").startsWith("[error]")) st.errors++;
                    break;
                }
                case "tool_end":
                    if (!e.optBoolean("ok", true)) st.toolFails++;
                    break;
                case "tool_failure_ctx":
                    st.toolFails++;
                    break;
                case "tool_hesitation":
                    st.hesitations++;
                    break;
                case "bureau_action":
                    st.bureauActions++;
                    break;
                case "bureau_edit":
                    if (e.optBoolean("fallback", false)) st.bureauFallbacks++;
                    if (e.optBoolean("potentialHallucination", false)) st.hallucinations++;
                    break;
                case "phantom_blocked":
                    st.phantom++;
                    break;
                case "reasoning_card":
                    if (e.optBoolean("potentialHallucination", false)) st.hallucinations++;
                    break;
                case "error":
                    st.errors++;
                    break;
                case "copilot_ui": {
                    String kind = e.optString("kind", "");
                    if ("matcher_miss".equals(kind) || "whitelist_block".equals(kind)
                            || "a11y_unavailable".equals(kind)
                            || "a11y_disconnected".equals(kind)
                            || "confirm_cancel".equals(kind)) {
                        st.copilotIssues++;
                        st.toolFails++;
                    } else if ("confirm_ask".equals(kind)) {
                        st.hesitations++;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        Collections.sort(latencies);
        st.p95 = percentile(latencies, 0.95);
        return st;
    }

    private static ChannelStats channelOf(String source,
            ChannelStats voice, ChannelStats text, ChannelStats bureau) {
        if (source == null) return text;
        String s = source.toLowerCase(Locale.ROOT);
        if ("voice".equals(s) || "voix".equals(s)) return voice;
        if ("bureau".equals(s)) return bureau;
        return text;
    }

    private static void appendChannelLine(StringBuilder sb, ChannelStats ch) {
        sb.append("\n— ").append(capitalize(ch.label)).append(" : ");
        if (ch.messages == 0 && ch.actions == 0 && ch.edits == 0 && ch.llmReplies == 0) {
            sb.append("inactif.");
            return;
        }
        List<String> parts = new ArrayList<>();
        if (ch.messages > 0) {
            parts.add(ch.messages + (ch.messages > 1 ? " messages" : " message"));
        }
        if (ch.llmReplies > 0) {
            parts.add(ch.llmReplies + (ch.llmReplies > 1 ? " réponses LLM" : " réponse LLM"));
        }
        if (ch.actions > 0) {
            parts.add(ch.actions + (ch.actions > 1 ? " actions" : " action"));
        }
        if (ch.edits > 0) {
            parts.add(ch.edits + (ch.edits > 1 ? " éditions" : " édition"));
        }
        if (ch.fallbacks > 0) {
            parts.add(ch.fallbacks + " repli" + (ch.fallbacks > 1 ? "s" : "") + " local");
        }
        if (ch.errors > 0) {
            parts.add(ch.errors + (ch.errors > 1 ? " erreurs" : " erreur"));
        }
        sb.append(joinFr(parts)).append('.');
    }

    private static String joinFr(List<String> parts) {
        if (parts.isEmpty()) return "rien";
        if (parts.size() == 1) return parts.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " et " : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String preview(String text, int max) {
        if (text == null) return "";
        String t = text.trim().replace('\n', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String humanReason(String reason) {
        if (reason == null || reason.isEmpty()) return "limite atteinte";
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("search")) return "recherche déjà utilisée dans le tour";
        if (r.contains("same") || r.contains("duplicate")) return "même outil / mêmes args";
        if (r.contains("max") || r.contains("cap")) return "plafond d'outils du tour";
        return reason;
    }

    static String humanAnomaly(String type) {
        switch (type) {
            case "bureau_llm_fallback": return "bureau (repli)";
            case "bureau_llm_error": return "bureau (LLM)";
            case "unanswered_request": return "requête sans réponse";
            case "repeated_action": return "action répétée";
            case "script_history_polluted": return "historique pollué";
            case "past_reference_no_source": return "hallucination (passé sans source)";
            case "phantom_action": return "action fantôme";
            case "copilot_matcher_miss": return "copilote (cible introuvable)";
            case "copilot_whitelist_block": return "copilote (whitelist)";
            case "copilot_confirm_stale": return "copilote (confirm sans réponse)";
            case "copilot_a11y_down": return "copilote (accessibilité)";
            default: return type;
        }
    }

    static boolean alreadyMentions(List<String> lines, String expl) {
        String needle = expl.length() > 40 ? expl.substring(0, 40) : expl;
        for (String line : lines) {
            if (line.contains(needle)) return true;
        }
        return false;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.min(sorted.size() - 1, Math.round(p * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    static final class DayStats {
        int messages;
        int toolFails;
        int hesitations;
        int phantom;
        int hallucinations;
        int bureauFallbacks;
        int bureauActions;
        int errors;
        int slow;
        int copilotIssues;
        long p95 = -1;

        boolean hasIssues() {
            return toolFails > 0 || hesitations > 0 || phantom > 0
                    || hallucinations > 0
                    || bureauFallbacks > 0 || errors > 0 || slow > 0
                    || copilotIssues > 0;
        }

        int issueScore() {
            return toolFails + hesitations + phantom + hallucinations
                    + bureauFallbacks + errors + (slow > 0 ? 1 : 0);
        }
    }

    private static final class ChannelStats {
        final String label;
        int messages;
        int llmReplies;
        int actions;
        int edits;
        int fallbacks;
        int errors;

        ChannelStats(String label) {
            this.label = label;
        }
    }
}
