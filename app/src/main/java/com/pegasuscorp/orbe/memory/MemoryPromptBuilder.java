package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.chat.PromptBudget;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.tools.ToolTag;

import java.util.EnumSet;

/**
 * Point d'entrée historique — délègue à {@link ContextBuilder}.
 */
public final class MemoryPromptBuilder {

    private MemoryPromptBuilder() {}

    public static String build(Context context, String userMessage) {
        return ContextBuilder.build(context, userMessage);
    }

    /** Prompt système complet : personnalité + outils filtrés + contexte mémoire (une seule analyse). */
    public static String buildFullSystem(Context context, String userMessage) {
        return buildFullSystem(context, userMessage, false);
    }

    public static String buildFullSystem(Context context, String userMessage, boolean nativeTools) {
        return buildFullSystem(context, userMessage, nativeTools, false);
    }

    /** @param agenticSynthesisOnly étape finale sans nouvel appel d'outil. */
    public static String buildFullSystem(Context context, String userMessage, boolean nativeTools,
            boolean agenticSynthesisOnly) {
        return buildFullSystem(context, userMessage, nativeTools, agenticSynthesisOnly,
                Channel.TEXT);
    }

    /** @param agenticSynthesisOnly étape finale sans nouvel appel d'outil. */
    public static String buildFullSystem(Context context, String userMessage, boolean nativeTools,
            boolean agenticSynthesisOnly, Channel channel) {
        return buildFullSystem(context, userMessage, nativeTools, agenticSynthesisOnly, channel,
                PromptBudget.Level.NORMAL);
    }

    public static String buildFullSystem(Context context, String userMessage, boolean nativeTools,
            boolean agenticSynthesisOnly, Channel channel, PromptBudget.Level budgetLevel) {
        ContextIntent intent = ContextAnalyzer.analyze(context, userMessage);
        Channel ch = channel != null ? channel : Channel.TEXT;
        EnumSet<ToolTag> tools = EnumSet.copyOf(intent.allowedTools);
        if (ch == Channel.COPILOT) {
            tools.add(ToolTag.UI);
        }
        PromptBudget.Level level = budgetLevel != null ? budgetLevel : PromptBudget.Level.NORMAL;
        int personalityMax = PromptBudget.personalityMaxChars(level);
        int screenMax = PromptBudget.screenMaxChars(level);
        String system = com.pegasuscorp.orbe.llm.PegasePrompt.buildSystem(
                context, tools, nativeTools, personalityMax)
                + ContextBuilder.build(context, userMessage, intent, ch, screenMax);
        if (agenticSynthesisOnly) {
            system += "\n\n=== SYNTHÈSE FINALE (sans outil) ===\n"
                    + "Tu as déjà les résultats d'outil ci-dessus.\n"
                    + "Réponds en français oral à l'utilisateur.\n"
                    + "Si search / wikipedia / news / weather / wikidata / brief : "
                    + "partage le fond utile (2 à 6 phrases). "
                    + "INTERDIT de répondre seulement « Ok », « Compris » ou « Voilà ».\n"
                    + "Si le résultat est un CALCUL déterministe : reformule uniquement, "
                    + "ne recalcule JAMAIS de tête, n'invente aucun chiffre.\n"
                    + "Si des extraits web sont fournis, croise-les et signale toute contradiction.\n"
                    + "Si l'outil était une action téléphone (spotify, notepad…) : "
                    + "une courte confirmation suffit.\n"
                    + "Pas d'URL, pas de liste, pas d'emojis, pas de JSON.\n"
                    + "N'appelle AUCUNE fonction — pas de nouvelle recherche, pas d'outil.\n";
        }
        return system;
    }
}
