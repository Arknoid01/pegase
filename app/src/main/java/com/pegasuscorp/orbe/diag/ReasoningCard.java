package com.pegasuscorp.orbe.diag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Snapshot du raisonnement d'un tour — affiché sous [🔍] dans Discussion. */
public final class ReasoningCard {

    public static final class ToolStep {
        public final String name;
        public final String paramsPreview;
        public final String resultPreview;
        public final long latencyMs;
        public final boolean ok;

        public ToolStep(String name, String paramsPreview, String resultPreview,
                long latencyMs, boolean ok) {
            this.name = name != null ? name : "";
            this.paramsPreview = paramsPreview != null ? paramsPreview : "";
            this.resultPreview = resultPreview != null ? resultPreview : "";
            this.latencyMs = Math.max(0L, latencyMs);
            this.ok = ok;
        }
    }

    public final String intentDetected;
    public final List<ToolStep> toolsUsed;
    public final int contextChunks;
    public final List<String> memoriesUsed;
    public final List<String> contextsLoaded;
    public final List<String> atlasUsed;
    public final List<String> profileUsed;
    public final String sessionUsed;
    public final List<String> webSources;
    public final String tavilyQuery;
    public final String backend;
    public final long latencyMs;
    public final int promptChars;
    public final boolean ephemeral;
    public final boolean potentialHallucination;
    public final String hallucinationReason;

    public ReasoningCard(String intentDetected, List<ToolStep> toolsUsed,
            int contextChunks, List<String> memoriesUsed, List<String> contextsLoaded,
            List<String> webSources, String tavilyQuery, String backend, long latencyMs,
            int promptChars, boolean ephemeral, boolean potentialHallucination,
            String hallucinationReason) {
        this(intentDetected, toolsUsed, contextChunks, memoriesUsed, contextsLoaded,
                null, null, null, webSources, tavilyQuery, backend, latencyMs,
                promptChars, ephemeral, potentialHallucination, hallucinationReason);
    }

    public ReasoningCard(String intentDetected, List<ToolStep> toolsUsed,
            int contextChunks, List<String> memoriesUsed, List<String> contextsLoaded,
            List<String> atlasUsed, List<String> profileUsed, String sessionUsed,
            List<String> webSources, String tavilyQuery, String backend, long latencyMs,
            int promptChars, boolean ephemeral, boolean potentialHallucination,
            String hallucinationReason) {
        this.intentDetected = intentDetected != null ? intentDetected : "Conversation";
        this.toolsUsed = toolsUsed != null
                ? Collections.unmodifiableList(new ArrayList<>(toolsUsed))
                : Collections.emptyList();
        this.contextChunks = Math.max(0, contextChunks);
        this.memoriesUsed = memoriesUsed != null
                ? Collections.unmodifiableList(new ArrayList<>(memoriesUsed))
                : Collections.emptyList();
        this.contextsLoaded = contextsLoaded != null
                ? Collections.unmodifiableList(new ArrayList<>(contextsLoaded))
                : Collections.emptyList();
        this.atlasUsed = atlasUsed != null
                ? Collections.unmodifiableList(new ArrayList<>(atlasUsed))
                : Collections.emptyList();
        this.profileUsed = profileUsed != null
                ? Collections.unmodifiableList(new ArrayList<>(profileUsed))
                : Collections.emptyList();
        this.sessionUsed = sessionUsed != null ? sessionUsed : "";
        this.webSources = webSources != null
                ? Collections.unmodifiableList(new ArrayList<>(webSources))
                : Collections.emptyList();
        this.tavilyQuery = tavilyQuery != null ? tavilyQuery : "";
        this.backend = backend != null ? backend : "";
        this.latencyMs = Math.max(0L, latencyMs);
        this.promptChars = Math.max(0, promptChars);
        this.ephemeral = ephemeral;
        this.potentialHallucination = potentialHallucination;
        this.hallucinationReason = hallucinationReason != null ? hallucinationReason : "";
    }

    /**
     * Ligne unique du parcours : Demande → outil ✅/❌ → réponse.
     * C'est ce qui répond à « a-t-elle bien utilisé l'outil ? ».
     */
    public String formatCheminement() {
        StringBuilder sb = new StringBuilder("📍 Cheminement\n   Demande");
        if (toolsUsed.isEmpty()) {
            sb.append(" → LLM seul (aucun outil)");
            if (potentialHallucination) {
                sb.append(" ⚠️");
            }
        } else {
            boolean anyFail = false;
            for (ToolStep step : toolsUsed) {
                sb.append(" → ").append(step.name);
                if (step.ok) {
                    sb.append(" ✅");
                } else {
                    sb.append(" ❌");
                    anyFail = true;
                }
                if (step.latencyMs > 0) {
                    sb.append(" (").append(step.latencyMs).append("ms)");
                }
            }
            sb.append(" → réponse");
            if (anyFail) {
                sb.append(" ⚠️ outil en échec");
            } else {
                sb.append(" (outil utilisé)");
            }
        }
        return sb.toString();
    }

    /** Texte expand UI (sobre, lisible). */
    public String formatPanel() {
        StringBuilder sb = new StringBuilder();
        if (potentialHallucination) {
            sb.append("⚠️ Fiabilité incertaine\n");
            if (!hallucinationReason.isEmpty()) {
                sb.append(hallucinationReason).append('\n');
            }
            sb.append('\n');
        } else {
            sb.append("🧠 Raisonnement\n\n");
        }

        sb.append(formatCheminement()).append("\n\n");

        int n = 1;
        sb.append(n++).append("️⃣ Intention : ").append(intentDetected).append('\n');

        if (!toolsUsed.isEmpty()) {
            for (ToolStep step : toolsUsed) {
                sb.append(n++).append("️⃣ Outil : ").append(step.name);
                sb.append(step.ok ? " ✅ utilisé" : " ❌ non exécuté / échec");
                if (step.latencyMs > 0) {
                    sb.append(" · ").append(step.latencyMs).append("ms");
                }
                sb.append('\n');
                if (!step.paramsPreview.isEmpty()) {
                    sb.append("   params : ").append(clip(step.paramsPreview, 120)).append('\n');
                }
                if (!step.resultPreview.isEmpty()) {
                    sb.append("   résultat : ").append(clip(step.resultPreview, 140)).append('\n');
                }
            }
        } else {
            sb.append(n++).append("️⃣ Outil : aucun\n");
            sb.append("   (réponse pure LLM — pas de calculator / search / wiki…)\n");
        }

        sb.append(n++).append("️⃣ Contexte\n");
        sb.append("   ").append(memoriesUsed.size()).append(" souvenir")
                .append(memoriesUsed.size() == 1 ? "" : "s")
                .append(" · ").append(contextsLoaded.size()).append(" fichier")
                .append(contextsLoaded.size() == 1 ? "" : "s")
                .append(" · ").append(contextChunks).append(" chunk")
                .append(contextChunks == 1 ? "" : "s")
                .append(" RAG\n");

        sb.append(formatUsedForReply());

        if (!webSources.isEmpty() || !tavilyQuery.isEmpty()) {
            if (!tavilyQuery.isEmpty()) {
                sb.append("   Requête : ").append(clip(tavilyQuery, 80)).append('\n');
            }
            sb.append("   Sources web : ");
            if (webSources.isEmpty()) {
                sb.append("0\n");
            } else {
                sb.append('\n');
                for (String s : webSources) {
                    sb.append("   · ").append(s).append('\n');
                }
            }
        } else {
            sb.append("   0 source web\n");
        }

        sb.append(n).append("️⃣ LLM\n");
        sb.append("   ");
        if (!backend.isEmpty()) {
            sb.append(shortBackend(backend)).append(" · ");
        }
        sb.append(latencyMs).append("ms");
        if (promptChars > 0) {
            sb.append(" · ").append(promptChars).append(" chars");
        }
        if (ephemeral) {
            sb.append(" · éphémère");
        }
        return sb.toString().trim();
    }

    /** Section lisible : ce qui a servi pour cette réponse. */
    public String formatUsedForReply() {
        boolean any = !memoriesUsed.isEmpty() || !atlasUsed.isEmpty()
                || !profileUsed.isEmpty() || !contextsLoaded.isEmpty()
                || (sessionUsed != null && !sessionUsed.isEmpty());
        StringBuilder sb = new StringBuilder();
        sb.append("\n🧠 Utilisé pour cette réponse\n");
        if (!any) {
            sb.append("   (aucun souvenir / Atlas / profil injecté ce tour)\n\n");
            return sb.toString();
        }
        for (String m : memoriesUsed) {
            sb.append("   · Souvenir : ").append(clip(m, 90)).append('\n');
        }
        for (String a : atlasUsed) {
            sb.append("   · Atlas : ").append(clip(a, 90)).append('\n');
        }
        if (!profileUsed.isEmpty()) {
            sb.append("   · Profil : ");
            for (int i = 0; i < profileUsed.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(profileUsed.get(i));
            }
            sb.append('\n');
        }
        for (String c : contextsLoaded) {
            sb.append("   · Contexte : ").append(clip(c, 90)).append('\n');
        }
        if (sessionUsed != null && !sessionUsed.isEmpty()) {
            sb.append("   · Session : ").append(clip(sessionUsed, 90)).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("intent", intentDetected);
            o.put("context_chunks", contextChunks);
            o.put("backend", backend);
            o.put("latency_ms", latencyMs);
            o.put("prompt_chars", promptChars);
            o.put("ephemeral", ephemeral);
            o.put("tools_count", toolsUsed.size());
            o.put("tools_ok", countToolsOk());
            o.put("cheminement", formatCheminement().replace('\n', ' ').trim());
            o.put("potentialHallucination", potentialHallucination);
            if (!hallucinationReason.isEmpty()) {
                o.put("hallucination_reason", hallucinationReason);
            }
            if (!tavilyQuery.isEmpty()) o.put("tavily_query", tavilyQuery);
            JSONArray tools = new JSONArray();
            for (ToolStep s : toolsUsed) {
                JSONObject t = new JSONObject();
                t.put("name", s.name);
                t.put("ok", s.ok);
                t.put("latency_ms", s.latencyMs);
                tools.put(t);
            }
            o.put("tools", tools);
            o.put("memories_count", memoriesUsed.size());
            o.put("atlas_count", atlasUsed.size());
            o.put("profile_sections", new JSONArray(profileUsed));
            if (!sessionUsed.isEmpty()) o.put("session_used", sessionUsed);
            o.put("web_sources", new JSONArray(webSources));
        } catch (Exception ignored) {
        }
        return o;
    }

    private int countToolsOk() {
        int n = 0;
        for (ToolStep s : toolsUsed) {
            if (s.ok) n++;
        }
        return n;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        if (t.length() <= max) return t;
        return t.substring(0, max - 1) + "…";
    }

    private static String shortBackend(String backend) {
        if (backend == null) return "";
        int slash = backend.lastIndexOf('/');
        if (slash >= 0 && slash < backend.length() - 1) {
            return backend.substring(slash + 1);
        }
        return backend;
    }

    /** Libellé intention pour l'UI. */
    public static String labelForIntent(String intent, List<ToolStep> tools) {
        if (tools != null) {
            for (ToolStep s : tools) {
                String id = s.name == null ? "" : s.name.toLowerCase(Locale.ROOT);
                if ("calculator".equals(id)) return "Calcul";
                if ("search".equals(id)) return "Recherche web";
                if ("wikipedia".equals(id) || "wikidata".equals(id)) return "Encyclopédie";
                if ("brief".equals(id)) return "Brief";
                if ("notepad".equals(id) || "memory".equals(id)) return "Mémoire";
                if ("diag".equals(id)) return "Diagnostic";
                if ("orion_manager".equals(id) || "orion_code".equals(id)) return "Orion";
                if ("weather".equals(id)) return "Météo";
                if ("news".equals(id)) return "Actualités";
            }
            if (!tools.isEmpty()) return "Outil";
        }
        if (intent == null) return "Conversation";
        switch (intent) {
            case "calc":
                return "Calcul";
            case "fresh_data":
                return "Recherche web";
            case "music":
                return "Musique";
            case "diag":
                return "Diagnostic";
            case "brief":
                return "Brief";
            case "orion":
            case "orion_code":
                return "Orion";
            default:
                return "Conversation";
        }
    }
}
