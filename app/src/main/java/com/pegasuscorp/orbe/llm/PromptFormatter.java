package com.pegasuscorp.orbe.llm;

import java.util.List;

/**
 * Formatage prompt ChatML simplifié (compatible Qwen / modèles instruct GGUF).
 */
final class PromptFormatter {

    private static final String IM_END = "<|" + "im_end|>\n";

    private PromptFormatter() {}

    static String format(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (ChatMessage.ROLE_SYSTEM.equals(msg.role)) {
                sb.append("<|im_start|>system\n").append(msg.content).append("\n").append(IM_END);
            } else if (ChatMessage.ROLE_USER.equals(msg.role)) {
                sb.append("<|im_start|>user\n").append(msg.content).append("\n").append(IM_END);
            } else if (ChatMessage.ROLE_ASSISTANT.equals(msg.role)) {
                sb.append("<|im_start|>assistant\n").append(msg.content).append("\n").append(IM_END);
            }
        }
        // "/no_think" désactive le mode raisonnement interne de Qwen3
        // (qui génère des milliers de tokens <think>...</think> avant de répondre,
        // ce qui bloque indéfiniment avec une limite de tokens courte).
        sb.append("<|im_start|>assistant\n/no_think\n");
        return sb.toString();
    }
}
