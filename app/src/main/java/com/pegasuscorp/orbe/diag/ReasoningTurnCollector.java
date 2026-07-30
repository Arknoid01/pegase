package com.pegasuscorp.orbe.diag;

import com.pegasuscorp.orbe.memory.ContextSnapshot;

import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Accumule les faits d'un tour jusqu'à {@link #build(String, String, long, int)}. */
public final class ReasoningTurnCollector {

    private static final Pattern URL_HOST = Pattern.compile(
            "https?://([^/\\s\"']+)", Pattern.CASE_INSENSITIVE);
    /** Domaines dans le format Tavily « Title (example.com) ». */
    private static final Pattern PAREN_HOST = Pattern.compile(
            "\\(([a-z0-9][-a-z0-9]*\\.[a-z0-9][-a-z0-9.]*)\\)", Pattern.CASE_INSENSITIVE);

    private final String rawIntent;
    private final List<ReasoningCard.ToolStep> tools = new ArrayList<>();
    private final List<String> memoriesUsed = new ArrayList<>();
    private final List<String> contextsLoaded = new ArrayList<>();
    private final List<String> atlasUsed = new ArrayList<>();
    private final List<String> profileUsed = new ArrayList<>();
    private String sessionUsed = "";
    private final Set<String> webSources = new LinkedHashSet<>();
    private String tavilyQuery = "";
    private int contextChunks;
    private boolean ephemeral;
    private boolean llmSynthesisUsed;
    private String synthesisBackend = "";
    private long synthesisLatencyMs;
    private int synthesisPromptChars;

    /** Params du dernier tool_start en attente de tool_end. */
    private String pendingToolId;
    private String pendingParamsPreview;
    private long pendingStartedAt;

    public ReasoningTurnCollector(String rawIntent) {
        this.rawIntent = rawIntent != null ? rawIntent : "general";
    }

    public void setContextChunks(int chunks) {
        this.contextChunks = Math.max(0, chunks);
    }

    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }

    /** Remplit souvenirs / Atlas / profil / contextes depuis le vrai inject. */
    public void applySnapshot(ContextSnapshot snap) {
        memoriesUsed.clear();
        contextsLoaded.clear();
        atlasUsed.clear();
        profileUsed.clear();
        sessionUsed = "";
        if (snap == null) return;
        for (String m : snap.memories) {
            if (m != null && !m.trim().isEmpty()) memoriesUsed.add(clip(m.trim(), 100));
        }
        for (String a : snap.atlasEntities) {
            if (a != null && !a.trim().isEmpty()) atlasUsed.add(clip(a.trim(), 80));
        }
        for (String p : snap.profileSections) {
            if (p != null && !p.trim().isEmpty()) profileUsed.add(p.trim());
        }
        for (String c : snap.namedContexts) {
            if (c != null && !c.trim().isEmpty()) contextsLoaded.add(c.trim());
        }
        if (snap.sessionTopic != null) sessionUsed = clip(snap.sessionTopic.trim(), 100);
        contextChunks = Math.max(contextChunks,
                memoriesUsed.size() + atlasUsed.size() + contextsLoaded.size());
    }

    /** Sujet du tour en cours (carte 🔍) — remplace le résumé de session archivé injecté. */
    public void setSessionUsed(String topic) {
        if (topic != null && !topic.trim().isEmpty()) {
            sessionUsed = clip(topic.trim(), 100);
        }
    }

    /** Synthèse LLM du tour (agentique ou réponse directe) — pas les tool_calls intermédiaires. */
    public void markLlmSynthesis(String backend, long latencyMs, int promptChars) {
        llmSynthesisUsed = true;
        synthesisBackend = backend != null ? backend : "";
        synthesisLatencyMs = Math.max(0L, latencyMs);
        synthesisPromptChars = Math.max(0, promptChars);
    }

    public boolean llmSynthesisUsed() {
        return llmSynthesisUsed;
    }

    public void setMemories(List<String> memories) {
        memoriesUsed.clear();
        if (memories != null) {
            for (String m : memories) {
                if (m != null && !m.trim().isEmpty()) {
                    memoriesUsed.add(clip(m.trim(), 80));
                }
            }
        }
    }

    public void setContextsLoaded(List<String> names) {
        contextsLoaded.clear();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.trim().isEmpty()) {
                    contextsLoaded.add(n.trim());
                }
            }
        }
    }

    public void noteToolStart(String toolId, JSONObject params) {
        pendingToolId = toolId != null ? toolId.trim() : "";
        pendingParamsPreview = previewParams(pendingToolId, params);
        pendingStartedAt = System.currentTimeMillis();
        if ("search".equalsIgnoreCase(pendingToolId) && params != null) {
            String q = params.optString("query", "").trim();
            if (!q.isEmpty()) tavilyQuery = q;
        }
    }

    public void noteToolEnd(String toolId, boolean ok, long latencyMs, String resultOrError) {
        String id = toolId != null ? toolId.trim() : pendingToolId;
        if (id == null || id.isEmpty()) id = "unknown";
        String params = id.equals(pendingToolId) ? pendingParamsPreview : "";
        long lat = latencyMs;
        if (lat <= 0 && pendingStartedAt > 0 && id.equals(pendingToolId)) {
            lat = System.currentTimeMillis() - pendingStartedAt;
        }
        String result = resultOrError != null ? clip(resultOrError.replace('\n', ' '), 160) : "";
        tools.add(new ReasoningCard.ToolStep(id, params, result, lat, ok));
        if (ok && resultOrError != null) {
            extractWebHosts(resultOrError);
        }
        pendingToolId = null;
        pendingParamsPreview = null;
        pendingStartedAt = 0;
    }

    public ReasoningCard build(String reply, String backend, long latencyMs, int promptChars) {
        String cardBackend;
        long cardLatency;
        int cardPromptChars;
        if (llmSynthesisUsed) {
            cardBackend = !synthesisBackend.isEmpty() ? synthesisBackend
                    : (backend != null ? backend : "");
            cardLatency = synthesisLatencyMs > 0L ? synthesisLatencyMs : latencyMs;
            cardPromptChars = synthesisPromptChars > 0 ? synthesisPromptChars : promptChars;
        } else if (!tools.isEmpty()) {
            cardBackend = "local";
            cardLatency = 0L;
            cardPromptChars = 0;
        } else {
            cardBackend = backend != null ? backend : "";
            cardLatency = latencyMs;
            cardPromptChars = promptChars;
        }
        boolean reliable = hasReliableToolSource() || contextChunks > 0
                || !memoriesUsed.isEmpty() || !atlasUsed.isEmpty();
        boolean hallu = HallucinationDetector.isPotentialHallucination(
                reply, contextChunks, reliable);
        String reason = hallu
                ? HallucinationDetector.reason(reply, contextChunks, false)
                : null;
        String intent = ReasoningCard.labelForIntent(rawIntent, tools);
        return new ReasoningCard(
                intent,
                new ArrayList<>(tools),
                contextChunks,
                new ArrayList<>(memoriesUsed),
                new ArrayList<>(contextsLoaded),
                new ArrayList<>(atlasUsed),
                new ArrayList<>(profileUsed),
                sessionUsed,
                new ArrayList<>(webSources),
                tavilyQuery,
                cardBackend,
                cardLatency,
                cardPromptChars,
                ephemeral,
                hallu,
                reason != null ? reason : "");
    }

    private boolean hasReliableToolSource() {
        for (ReasoningCard.ToolStep s : tools) {
            if (!s.ok) continue;
            String id = s.name.toLowerCase(Locale.ROOT);
            if ("calculator".equals(id) || "search".equals(id)
                    || "wikipedia".equals(id) || "wikidata".equals(id)
                    || "weather".equals(id) || "news".equals(id)
                    || "brief".equals(id) || "nasa".equals(id)
                    || "spotify".equals(id) || "youtube".equals(id)
                    || "diag".equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void extractWebHosts(String text) {
        if (text == null || text.isEmpty()) return;
        Matcher m = URL_HOST.matcher(text);
        while (m.find() && webSources.size() < 8) {
            String host = m.group(1);
            if (host == null) continue;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (!host.isEmpty()) webSources.add(host);
        }
        Matcher p = PAREN_HOST.matcher(text);
        while (p.find() && webSources.size() < 8) {
            String host = p.group(1);
            if (host == null) continue;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (!host.isEmpty() && host.contains(".")) webSources.add(host);
        }
        for (String line : text.split("\n")) {
            String l = line.trim();
            int idx = l.toLowerCase(Locale.ROOT).indexOf("site");
            if (idx >= 0 && l.contains(":")) {
                String after = l.substring(l.indexOf(':') + 1).trim();
                if (!after.isEmpty() && after.length() < 80 && !after.contains(" ")) {
                    webSources.add(after.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private static String previewParams(String toolId, JSONObject params) {
        if (params == null || params.length() == 0) return "";
        try {
            if ("calculator".equalsIgnoreCase(toolId)) {
                return params.optString("expression", "");
            }
            if ("search".equalsIgnoreCase(toolId)
                    || "wikipedia".equalsIgnoreCase(toolId)
                    || "wikidata".equalsIgnoreCase(toolId)) {
                return params.optString("query", "");
            }
            String raw = params.toString();
            return clip(raw, 100);
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** Host depuis URL — utilitaire tests. */
    static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            if (h == null) return "";
            h = h.toLowerCase(Locale.ROOT);
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }
}
