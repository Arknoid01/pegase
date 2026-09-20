package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.diag.Trace;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Compaction du contexte d'une boucle agentique longue : quand les échanges d'outils dépassent le
 * budget du prompt, les résultats des étapes anciennes sont abrégés (jamais les appels ni
 * les étapes récentes), du moins agressif au plus agressif, jusqu'à tenir dans le budget.
 * Déterministe, sans appel LLM.
 */
final class AgenticContextCompactor {

    /** Plafonds de résultat pour les étapes anciennes, du plus large au plus serré. */
    private static final int[] RESULT_CAPS = {1_200, 400, 120};
    private static final int KEEP_FULL_STEPS = 2;
    static final String ABBREVIATED = "\n[… résultat ancien abrégé pour libérer du contexte]";

    private AgenticContextCompactor() {}

    /** Budget total du prompt en caractères pour ce niveau (Groq est le plus serré). */
    static int maxPromptChars(PromptBudget.Level level) {
        return level == PromptBudget.Level.NORMAL ? 80_000
                : PromptBudget.GROQ_MAX_PROMPT_TOKENS * 4;
    }

    /**
     * Messages des étapes (appel assistant, résultat outil, consigne de synthèse) tenant si
     * possible dans {@code budgetChars}. Sans dépassement, rien n'est abrégé.
     */
    static List<JSONObject> stepMessages(List<AgenticChain.Step> steps, int budgetChars)
            throws Exception {
        List<JSONObject> full = render(steps, 0, 0);
        if (size(full) <= budgetChars || steps.size() <= KEEP_FULL_STEPS) return full;

        for (int cap : RESULT_CAPS) {
            List<JSONObject> out = render(steps, steps.size() - KEEP_FULL_STEPS, cap);
            if (size(out) <= budgetChars) {
                Trace.error("agentic", "compaction contexte : anciens résultats plafonnés à "
                        + cap + " car.");
                return out;
            }
        }
        // dernier recours : ne garder en entier que la dernière étape
        Trace.error("agentic", "compaction contexte : dernière étape seule conservée");
        return render(steps, steps.size() - 1, RESULT_CAPS[RESULT_CAPS.length - 1]);
    }

    /**
     * @param compactBefore les étapes d'indice &lt; compactBefore sont abrégées à {@code cap}
     *        caractères (0 = aucune)
     */
    private static List<JSONObject> render(List<AgenticChain.Step> steps, int compactBefore,
            int cap) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            AgenticChain.Step step = steps.get(i);
            boolean compact = i < compactBefore && cap > 0;
            out.add(OpenAiCompatibleChatBackend.assistantToolCallMessage(
                    step.assistantReply, step.toolCall));
            String content = step.toolResultContent;
            if (compact && content.length() > cap) {
                content = content.substring(0, cap) + ABBREVIATED;
            }
            out.add(OpenAiCompatibleChatBackend.toolResultMessage(step.toolCall, content));
            // pas de « succès » à confirmer pour un appel refusé ; ni pour une étape ancienne
            if (step.rejected || compact) continue;
            String toolName = step.toolCall != null ? step.toolCall.name : "outil";
            String result = step.toolDisplayText != null && !step.toolDisplayText.isEmpty()
                    ? step.toolDisplayText : step.toolResultContent;
            out.add(OpenAiCompatibleChatBackend.message("system",
                    ToolSuccessHint.build(toolName, result)));
        }
        return out;
    }

    private static int size(List<JSONObject> messages) {
        int n = 0;
        for (JSONObject m : messages) n += m.toString().length();
        return n;
    }
}
