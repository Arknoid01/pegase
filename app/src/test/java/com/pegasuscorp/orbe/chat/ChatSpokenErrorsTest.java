package com.pegasuscorp.orbe.chat;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChatSpokenErrorsTest {

    @Test
    public void isRateLimit_detectsGroq429Messages() {
        assertTrue(ChatSpokenErrors.isRateLimit("Rate limit Groq — réessai dans 6000 ms"));
        assertTrue(ChatSpokenErrors.isRateLimit("HTTP 429 : {\"error\":\"rate_limit_exceeded\"}"));
        assertFalse(ChatSpokenErrors.isRateLimit("Timeout modèle"));
    }

    @Test
    public void toUserMessage_rateLimit_isClearAndStable() {
        String out = ChatSpokenErrors.toUserMessage("HTTP 429 : too many requests");
        assertEquals(ChatSpokenErrors.RATE_LIMIT_USER_MESSAGE, out);
        assertTrue(out.contains("Groq"));
        assertTrue(out.contains("minute"));
    }

    @Test
    public void toUserMessage_rateLimit_namesProvider() {
        String out = ChatSpokenErrors.toUserMessage("Cerebras",
                "Rate limit cerebras — réessai dans 6000 ms");
        assertTrue(out.contains("Cerebras"));
        assertTrue(out.toLowerCase().contains("satur"));
    }

    @Test
    public void toUserMessage_alreadyFriendly_isIdempotent() {
        String friendly = ChatSpokenErrors.RATE_LIMIT_USER_MESSAGE;
        assertEquals(friendly, ChatSpokenErrors.toUserMessage(friendly));
    }

    @Test
    public void toUserMessage_allModelsFailed_isClear() {
        assertEquals(ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE,
                ChatSpokenErrors.toUserMessage("Tous les modèles ont échoué."));
        assertTrue(ChatSpokenErrors.isAllModelsFailed("Tous les modèles ont échoué."));
        String out = ChatSpokenErrors.toUserMessage("Tous les modèles ont échoué.");
        assertTrue(out.contains("Groq"));
        assertTrue(out.toLowerCase().contains("réessaie") || out.toLowerCase().contains("reessaie"));
    }

    @Test
    public void toUserMessage_401_namesProvider() {
        String out = ChatSpokenErrors.toUserMessage("OpenRouter",
                "OpenRouter HTTP 401 : {\"error\":\"User not found\"}");
        assertEquals("Clé OpenRouter invalide. Vérifie dans Réglages, section Clés API.", out);
    }

    @Test
    public void toUserMessage_401_infersProviderFromMessage() {
        String out = ChatSpokenErrors.toUserMessage(
                "Cerebras HTTP 401 : Wrong API Key");
        assertTrue(out.contains("Cerebras"));
        assertTrue(out.toLowerCase().contains("invalide"));
    }

    @Test
    public void resolveProviderLabel_fromIds() {
        assertEquals("Groq", ChatSpokenErrors.resolveProviderLabel("groq", null));
        assertEquals("Cerebras", ChatSpokenErrors.resolveProviderLabel("cerebras", null));
        assertEquals("OpenRouter", ChatSpokenErrors.resolveProviderLabel("openrouter", null));
    }

    @Test
    public void isToolChoiceConflict_detectsGroqNone() {
        assertTrue(ChatSpokenErrors.isToolChoiceConflict(
                "HTTP 400 : Tool choice is none, but model called a tool"));
        assertFalse(ChatSpokenErrors.isToolChoiceConflict("HTTP 503 Service Unavailable"));
    }

    @Test
    public void isToolChoiceConflict_detectsNotInRequestTools() {
        assertTrue(ChatSpokenErrors.isToolChoiceConflict(
                "Groq HTTP 400 : Tool search was not in request.tools"));
        assertTrue(ChatSpokenErrors.isToolChoiceConflict(
                "tool 'search' which was not in request.tools"));
    }

    @Test
    public void parseMissingToolName_extractsSearch() {
        assertEquals("search", ChatSpokenErrors.parseMissingToolName(
                "Groq HTTP 400 : Tool search was not in request.tools"));
        assertEquals("search", ChatSpokenErrors.parseMissingToolName(
                "Failed: tool 'search' which was not in request.tools"));
        assertNull(ChatSpokenErrors.parseMissingToolName(
                "Tool choice is none, but model called a tool"));
    }

    @Test
    public void formatReport_empty() {
        assertTrue(ProviderKeyProbe.formatReport(null).contains("Aucun"));
    }
}
