package com.pegasuscorp.orbe.tools;

/**
 * Résultat typé d'un outil — remplace les préfixes texte (ex. {@code NASA_IMAGE:}).
 * {@link #text} est toujours présent ; {@link #wireText()} sert de fallback universal pour TTS / historique.
 */
public final class ToolResult {

    public enum Kind { TEXT, IMAGE_URL, ERROR }

    public final Kind kind;
    public final String text;
    public final String imageUrl;
    /** Contexte brut pour la synthèse agentique (ex. extraits web) — vide = utiliser {@link #text}. */
    public final String llmContext;

    private ToolResult(Kind kind, String text, String imageUrl, String llmContext) {
        this.kind = kind != null ? kind : Kind.TEXT;
        this.text = text != null ? text : "";
        this.imageUrl = imageUrl;
        this.llmContext = llmContext != null ? llmContext : "";
    }

    public static ToolResult text(String text) {
        return text(text, null);
    }

    /** @param llmContext données brutes pour le LLM ; {@code text} reste la version affichable / TTS. */
    public static ToolResult text(String text, String llmContext) {
        return new ToolResult(Kind.TEXT, text, null, llmContext);
    }

    public static ToolResult imageUrl(String text, String imageUrl) {
        return new ToolResult(Kind.IMAGE_URL, text, imageUrl, null);
    }

    public static ToolResult error(String text) {
        return new ToolResult(Kind.ERROR, text, null, null);
    }

    /** Reconstruit depuis le format filaire legacy (NASA, etc.). */
    public static ToolResult fromWire(String wire) {
        if (wire == null) return text("");
        if (wire.startsWith("NASA_IMAGE:")) {
            String payload = wire.substring("NASA_IMAGE:".length());
            String[] parts = payload.split("::", 2);
            String url = parts.length > 0 ? parts[0].trim() : "";
            String body = parts.length > 1 ? parts[1].trim() : "";
            return imageUrl(body, url);
        }
        return text(wire);
    }

    /**
     * Texte consommé par Activities, TTS et {@link com.pegasuscorp.orbe.chat.ConversationManager#recordToolReply}.
     * Préserve {@code NASA_IMAGE:} tant que les vues ne sont pas migrées.
     */
    public String wireText() {
        if (kind == Kind.IMAGE_URL && imageUrl != null && !imageUrl.isEmpty()) {
            return "NASA_IMAGE:" + imageUrl + "::" + text;
        }
        return text;
    }

    /** Contenu envoyé au LLM lors de la synthèse agentique. */
    public String contextForSynthesis() {
        return !llmContext.isEmpty() ? llmContext : text;
    }
}
