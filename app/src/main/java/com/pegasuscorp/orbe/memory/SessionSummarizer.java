package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.chat.ProviderTraceSink;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.llm.ModelStore;

import org.json.JSONObject;

import java.util.List;

/**
 * Génère un résumé de session à la fin d'une discussion.
 * Cloud : LLM dédié. Local : résumé déterministe (évite d'attendre le GGUF).
 */
public final class SessionSummarizer {

    private SessionSummarizer() {}

    public static void summarizeAndSave(Context context, List<ChatBackend.Turn> sessionTurns) {
        if (sessionTurns == null || sessionTurns.isEmpty()) return;

        if (ModelStore.useLocalLlm(context)) {
            MemoryRepository.getInstance(context)
                    .addSessionSummary(fallbackSummary(context, sessionTurns));
            return;
        }

        String userName = UserProfileStore.getInstance(context).getUserName();
        StringBuilder transcript = new StringBuilder();
        for (ChatBackend.Turn t : sessionTurns) {
            transcript.append(t.fromUser ? userName + ": " : "Pégase: ")
                    .append(t.text).append("\n");
        }

        String prompt =
                "Résume cette conversation entre " + userName + " et Pégase. "
                + "Réponds UNIQUEMENT en JSON valide, sans markdown, avec les clés : "
                + "topic, summary, important_facts (tableau), decisions (tableau), "
                + "pending_topics (tableau). En français.\n\n"
                + transcript;

        ChatBackend backend = ChatBackendFactory.create(context);
        final int promptChars = prompt.length();
        backend.send(java.util.Collections.emptyList(), prompt, new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                consumeProviderTrace(backend);
                Trace.llmReply(text != null ? text : "", backend.traceBackendLabel(),
                        0L, false, false, false, promptChars, true, "session_summary");
                SessionSummary summary = parseSummary(text);
                if (summary == null) {
                    summary = fallbackSummary(context, sessionTurns);
                }
                MemoryRepository.getInstance(context).addSessionSummary(summary);
            }

            @Override
            public void onError(String error) {
                discardProviderTrace(backend);
                Trace.error("session_summary", error != null ? error : "unknown");
                MemoryRepository.getInstance(context)
                        .addSessionSummary(fallbackSummary(context, sessionTurns));
            }
        });
    }

    private static void consumeProviderTrace(ChatBackend backend) {
        if (backend instanceof ProviderTraceSink) {
            ((ProviderTraceSink) backend).consumePendingProviderTrace();
        }
    }

    private static void discardProviderTrace(ChatBackend backend) {
        if (backend instanceof ProviderTraceSink) {
            ((ProviderTraceSink) backend).discardPendingProviderTrace();
        }
    }

    private static SessionSummary parseSummary(String text) {
        if (text == null) return null;
        String json = text.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return SessionSummary.fromJson(new JSONObject(json.substring(start, end + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    static SessionSummary fallbackSummary(Context context, List<ChatBackend.Turn> turns) {
        String userName = UserProfileStore.getInstance(context).getUserName();
        SessionSummary s = new SessionSummary();
        for (ChatBackend.Turn t : turns) {
            if (t.fromUser && !t.text.isEmpty()) {
                s.topic = t.text.length() > 60 ? t.text.substring(0, 60) + "…" : t.text;
                break;
            }
        }
        if (s.topic.isEmpty()) s.topic = "Discussion";
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, turns.size() - 6); i < turns.size(); i++) {
            ChatBackend.Turn t = turns.get(i);
            sb.append(t.fromUser ? userName : "Pégase").append(": ")
                    .append(t.text).append(" ");
        }
        s.summary = sb.toString().trim();
        if (s.summary.isEmpty()) s.summary = "Courte discussion avec Pégase.";
        LocalSessionExtractor.enrich(s, turns);
        return s;
    }
}
