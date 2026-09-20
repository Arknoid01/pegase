package com.pegasuscorp.orbe.tools.memory;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.memory.SessionSummary;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Retrouve ce dont on a parlé : résumés de sessions passées et derniers échanges. */
public final class ConversationHistoryTool implements Tool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    @Override
    public String id() {
        return "conversation_history";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.HISTORY;
    }

    @Override
    public String description() {
        return "conversation_history(query:str, limit?:int) — Cherche dans les conversations "
                + "passées (« on a parlé de… », « tu te souviens de… ») : résumés de sessions "
                + "et derniers échanges. Distinct de memory (faits durables) et de notepad.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String query = params.optString("query", "").trim();
        if (query.isEmpty()) {
            cb.onError("Sur quoi dois-je chercher dans nos conversations ?");
            return;
        }
        int limit = Math.max(1, Math.min(MAX_LIMIT, params.optInt("limit", DEFAULT_LIMIT)));
        MemoryRepository repo = MemoryRepository.getInstance(ctx);
        List<String> hits = search(query, repo.getAllSessionSummaries(),
                repo.getRecentTurns(), limit);
        cb.onSuccess(ToolResult.text(hits.isEmpty()
                ? "Rien trouvé dans nos conversations sur « " + query + " »."
                : String.join("\n", hits)));
    }

    /** Recherche par mots-clés (sans accents, insensible à la casse) ; les plus récents d'abord. */
    static List<String> search(String query, List<SessionSummary> summaries,
            List<ChatBackend.Turn> turns, int limit) {
        List<String> words = new ArrayList<>();
        for (String w : SpeechInputNormalizer.fold(query).split("\\s+")) {
            if (w.length() >= 3) words.add(w);
        }
        List<String> out = new ArrayList<>();
        for (int i = summaries.size() - 1; i >= 0 && out.size() < limit; i--) {
            SessionSummary s = summaries.get(i);
            StringBuilder text = new StringBuilder(s.topic).append(' ').append(s.summary);
            for (String f : s.importantFacts) text.append(' ').append(f);
            for (String d : s.decisions) text.append(' ').append(d);
            if (matches(text.toString(), words)) {
                String when = s.endedAt != null && !s.endedAt.isEmpty() ? " (" + s.endedAt + ")" : "";
                out.add("Session" + when + " — " + clip(s.topic + " : " + s.summary, 400));
            }
        }
        for (int i = turns.size() - 1; i >= 0 && out.size() < limit; i--) {
            ChatBackend.Turn t = turns.get(i);
            if (t.system) continue;
            if (matches(t.text, words)) {
                out.add((t.fromUser ? "Toi : " : "Moi : ") + clip(t.text, 300));
            }
        }
        return out;
    }

    private static boolean matches(String text, List<String> words) {
        if (words.isEmpty()) return false;
        String folded = SpeechInputNormalizer.fold(text);
        for (String w : words) {
            if (folded.contains(w)) return true;
        }
        return false;
    }

    private static String clip(String s, int max) {
        String t = s != null ? s.replace('\n', ' ').trim() : "";
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
