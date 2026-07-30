package com.pegasuscorp.orbe.session;

import com.pegasuscorp.orbe.chat.AgenticChain;
import com.pegasuscorp.orbe.chat.NativeToolCall;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.TreeMap;

/** Règles de la boucle agentique — limites par tour et anti-doublon. */
public final class AgenticTurnPolicy {

    /** Nombre max d'exécutions d'outils dans un même tour utilisateur. */
    public static final int MAX_TOOLS_PER_TURN = 2;

    public enum BlockReason {
        NONE,
        CAP_REACHED,
        DUPLICATE_TOOL_ARGS,
        SEARCH_ALREADY_USED,
        TOOL_ALREADY_USED
    }

    private AgenticTurnPolicy() {}

    public static boolean allowMoreToolCalls(AgenticChain chain) {
        if (chain == null || chain.isEmpty()) return false;
        if (chain.toolStepCount() >= MAX_TOOLS_PER_TURN) return false;
        if (chain.usedSearch()) return false;
        if (chain.usedTool("calculator")) return false;
        // Brief = agrégat cache complet : pas de 2e outil ni de brief({}) vide après
        if (chain.usedTool("brief")) return false;
        // Orion manager : un appel suffit (start/stop/status) — évite orion_manager({}) en boucle
        if (chain.usedTool("orion_manager")) return false;
        // Wiki / recherche : une source suffit pour le tour
        if (chain.usedTool("wikipedia") || chain.usedTool("wikidata")
                || chain.usedTool("search")) return false;
        return true;
    }

    public static BlockReason blockReason(AgenticChain chain, NativeToolCall call) {
        return evaluate(chain, call).blockReason;
    }

    public static String fingerprint(String toolName, JSONObject args) {
        String name = toolName != null ? toolName.trim().toLowerCase(Locale.ROOT) : "";
        return name + "|" + normalizedArgs(args);
    }

    static Evaluation evaluate(AgenticChain chain, NativeToolCall candidate) {
        int executed = chain != null ? chain.toolStepCount() : 0;
        int sameToolSameArgs = 0;
        if (chain != null && candidate != null) {
            String fp = fingerprint(candidate.name, candidate.arguments);
            for (AgenticChain.Step step : chain.steps()) {
                if (fingerprint(step.toolCall.name, step.toolCall.arguments).equals(fp)) {
                    sameToolSameArgs++;
                }
            }
        }
        if (candidate == null) {
            return new Evaluation(BlockReason.NONE, executed, sameToolSameArgs);
        }
        if (executed >= MAX_TOOLS_PER_TURN) {
            return new Evaluation(BlockReason.CAP_REACHED, executed, sameToolSameArgs);
        }
        if (sameToolSameArgs > 0) {
            return new Evaluation(BlockReason.DUPLICATE_TOOL_ARGS, executed, sameToolSameArgs);
        }
        String toolId = candidate.name != null ? candidate.name.trim() : "";
        if (chain != null && !toolId.isEmpty() && chain.usedTool(toolId)) {
            return new Evaluation(BlockReason.TOOL_ALREADY_USED, executed, sameToolSameArgs);
        }
        if ("search".equalsIgnoreCase(toolId) && chain != null && chain.usedSearch()) {
            return new Evaluation(BlockReason.SEARCH_ALREADY_USED, executed, sameToolSameArgs);
        }
        return new Evaluation(BlockReason.NONE, executed, sameToolSameArgs);
    }

    private static String normalizedArgs(JSONObject args) {
        if (args == null || args.length() == 0) return "{}";
        TreeMap<String, Object> sorted = new TreeMap<>();
        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            sorted.put(key, args.opt(key));
        }
        return new JSONObject(sorted).toString();
    }

    static final class Evaluation {
        final BlockReason blockReason;
        final int toolStepCount;
        final int sameToolSameArgsCount;

        Evaluation(BlockReason blockReason, int toolStepCount, int sameToolSameArgsCount) {
            this.blockReason = blockReason;
            this.toolStepCount = toolStepCount;
            this.sameToolSameArgsCount = sameToolSameArgsCount;
        }
    }
}
