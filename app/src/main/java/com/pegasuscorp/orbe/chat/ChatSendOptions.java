package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.memory.ContextIntent;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.intentions.PegaseModeStore;

import android.content.Context;

import java.util.EnumSet;

/** Options d'envoi LLM — function calling natif, canal, intent et boucle agentique. */
public final class ChatSendOptions {

    public final boolean nativeTools;
    public final EnumSet<ToolTag> allowedTools;
    /** Étape intermédiaire de la boucle agentique (pas un nouveau tour user). */
    public final boolean agenticStep;
    /** Autorise encore des tool_calls pendant une étape agentique. */
    public final boolean allowMoreTools;
    /** Canal d'entrée — VOICE garde des réponses plus courtes. */
    public final Channel channel;
    /** Intention ContextAnalyzer (creative, philosophical, …) — budget tokens TEXT. */
    public final String intentName;
    /** Surcharge optionnelle de {@link #replyMaxTokens()}. */
    private final Integer maxTokensOverride;

    private ChatSendOptions(boolean nativeTools, EnumSet<ToolTag> allowedTools,
            boolean agenticStep, boolean allowMoreTools, Channel channel) {
        this(nativeTools, allowedTools, agenticStep, allowMoreTools, channel, null, null);
    }

    private ChatSendOptions(boolean nativeTools, EnumSet<ToolTag> allowedTools,
            boolean agenticStep, boolean allowMoreTools, Channel channel,
            Integer maxTokensOverride, String intentName) {
        this.nativeTools = nativeTools;
        this.agenticStep = agenticStep;
        this.allowMoreTools = allowMoreTools;
        this.channel = channel != null ? channel : Channel.TEXT;
        this.allowedTools = allowedTools != null && !allowedTools.isEmpty()
                ? EnumSet.copyOf(allowedTools)
                : EnumSet.allOf(ToolTag.class);
        this.maxTokensOverride = maxTokensOverride;
        this.intentName = intentName;
    }

    public static ChatSendOptions legacy() {
        return legacy(Channel.TEXT);
    }

    public static ChatSendOptions legacy(Channel channel) {
        return new ChatSendOptions(false, EnumSet.allOf(ToolTag.class), false, false, channel);
    }

    public static ChatSendOptions nativeTools(EnumSet<ToolTag> allowedTools) {
        return nativeTools(allowedTools, Channel.TEXT);
    }

    public static ChatSendOptions nativeTools(EnumSet<ToolTag> allowedTools, Channel channel) {
        return new ChatSendOptions(true, allowedTools, false, false, channel);
    }

    public static ChatSendOptions forVoice(EnumSet<ToolTag> tools) {
        return new ChatSendOptions(true, tools, false, false, Channel.VOICE);
    }

    public static ChatSendOptions forText(EnumSet<ToolTag> tools) {
        return new ChatSendOptions(true, tools, false, false, Channel.TEXT);
    }

    public static ChatSendOptions agenticStep(EnumSet<ToolTag> allowedTools, boolean allowMoreTools) {
        return agenticStep(allowedTools, allowMoreTools, Channel.TEXT);
    }

    public static ChatSendOptions agenticStep(EnumSet<ToolTag> allowedTools,
            boolean allowMoreTools, Channel channel) {
        return new ChatSendOptions(false, allowedTools, true, allowMoreTools, channel);
    }

    /** Désactive le FC natif en conservant outils autorisés, canal et intent. */
    public ChatSendOptions withoutNativeTools() {
        return new ChatSendOptions(false, allowedTools, agenticStep, allowMoreTools, channel,
                maxTokensOverride, intentName);
    }

    /** Élargit / remplace le set d'outils FC (ex. retry après tool not in request.tools). */
    public ChatSendOptions withAllowedTools(EnumSet<ToolTag> tools) {
        return new ChatSendOptions(nativeTools, tools, agenticStep, allowMoreTools, channel,
                maxTokensOverride, intentName);
    }

    /** Surcharge le budget tokens (ex. analyse diag = 800). */
    public ChatSendOptions withMaxTokens(int maxTokens) {
        return new ChatSendOptions(nativeTools, allowedTools, agenticStep, allowMoreTools, channel,
                Math.max(1, maxTokens), intentName);
    }

    /** Attache l'intention ContextAnalyzer pour le budget TEXT. */
    public ChatSendOptions withIntent(ContextIntent intent) {
        String name = intent != null ? intent.intent : null;
        return new ChatSendOptions(nativeTools, allowedTools, agenticStep, allowMoreTools, channel,
                maxTokensOverride, name);
    }

    public ChatSendOptions withIntentName(String intent) {
        return new ChatSendOptions(nativeTools, allowedTools, agenticStep, allowMoreTools, channel,
                maxTokensOverride, intent);
    }

    /**
     * Ajuste le plafond voix selon mode conduite/travail et intention —
     * sans effet si {@link #withMaxTokens} déjà appelé.
     */
    public ChatSendOptions withVoiceTokenBudget(Context context) {
        if (channel != Channel.VOICE || maxTokensOverride != null) return this;
        if (context == null) return this;
        if (PegaseModeStore.isDrive(context)) return withMaxTokens(90);
        if (PegaseModeStore.isWork(context)) return withMaxTokens(120);
        String intent = intentName;
        if ("productivity".equals(intent) || "device".equals(intent) || "diag".equals(intent)) {
            return withMaxTokens(140);
        }
        if ("fresh_data".equals(intent) || "music".equals(intent)) {
            return withMaxTokens(220);
        }
        if ("general".equals(intent) || "creative".equals(intent) || "story".equals(intent)
                || "philosophical".equals(intent)) {
            return withMaxTokens(250);
        }
        return this;
    }

    /** Budget réponse — utilise {@link #intentName} si présent. */
    public int replyMaxTokens() {
        return replyMaxTokens(intentName);
    }

    /** Budget réponse libre selon canal / FC / intention. */
    public int replyMaxTokens(ContextIntent intent) {
        return replyMaxTokens(intent != null ? intent.intent : intentName);
    }

    public int replyMaxTokens(String intent) {
        if (maxTokensOverride != null) return maxTokensOverride;

        // Voix — TTS temps réel, garder court
        if (channel == Channel.VOICE) return 180;

        // Bureau — plans longs (workspace planning)
        if (channel == Channel.BUREAU) return 1500;

        // Orion — génération de code
        if (channel == Channel.ORION) return 2048;

        // TEXT — budget selon intention (prioritaire pour ne pas tronquer une histoire)
        int byIntent = tokensForIntent(intent);
        if (byIntent > 0) return byIntent;

        // FC natif TEXT — place pour tool_calls + réponse un peu plus longue
        if (nativeTools) return 700;

        return 500; // défaut conversation texte
    }

    /**
     * @return budget &gt; 0 si l'intent fixe un plafond, sinon 0
     */
    static int tokensForIntent(String intent) {
        if (intent == null || intent.isEmpty()) return 0;
        switch (intent) {
            case "creative":
            case "philosophical":
            case "story":
                return 1500;
            case "technical":
            case "explanation":
                return 900;
            case "general":
                return 800;
            case "productivity":
            case "music":
            case "brief":
            case "diag":
            case "memory":
                return 300;
            case "fresh_data":
                // search / météo / actus : assez pour une vraie synthèse orale
                return 700;
            default:
                return 0;
        }
    }
}
