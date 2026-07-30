package com.pegasuscorp.orbe.diag;

import android.content.Context;

import com.pegasuscorp.orbe.memory.MemoryEditResult;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commandes vocales sur {@code corrections.md}.
 */
public final class CorrectionsEditor {

    public interface Callback {
        void onResult(MemoryEditResult result);
    }

    private static final Pattern MARK_DONE = Pattern.compile(
            "(?i)(?:marque|coche|passe|note)\\s+(.+?)\\s+comme\\s+"
                    + "(?:termin[eé]e?s?|fait|faits|corrig[eé]e?s?|r[eé]solu(?:e|s)?)");

    private final Context appContext;

    public CorrectionsEditor(Context context) {
        appContext = context.getApplicationContext();
    }

    public static boolean looksLikeCorrectionsCommand(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String fold = fold(text);
        if (looksLikeListPending(fold)) return true;
        if (looksLikeCountPending(fold)) return true;
        return MARK_DONE.matcher(text.trim()).find();
    }

    public void process(String userText, Callback callback) {
        MemoryEditResult result = tryEdit(userText);
        if (result == null) {
            callback.onResult(MemoryEditResult.notMemoryEdit());
            return;
        }
        callback.onResult(result);
    }

    private MemoryEditResult tryEdit(String text) {
        String trimmed = text.trim();
        String fold = fold(trimmed);

        if (looksLikeCountPending(fold)) {
            String spoken = CorrectionsStore.speakPendingCount(appContext);
            return MemoryEditResult.applied("corrections", spoken);
        }
        if (looksLikeListPending(fold)) {
            String spoken = CorrectionsStore.speakPendingList(appContext);
            return MemoryEditResult.applied("corrections", spoken);
        }
        Matcher m = MARK_DONE.matcher(trimmed);
        if (m.find()) {
            String target = m.group(1).trim();
            String spoken = CorrectionsStore.markDone(appContext, target);
            return MemoryEditResult.applied("corrections", spoken);
        }
        return null;
    }

    static boolean looksLikeListPending(String fold) {
        if (fold == null) return false;
        if (fold.contains("qu est ce qui reste") && fold.contains("corrig")) return true;
        if (fold.contains("quest ce qui reste") && fold.contains("corrig")) return true;
        if (fold.contains("que reste") && fold.contains("corrig")) return true;
        if (fold.contains("reste a corriger") || fold.contains("reste a corriger")) return true;
        if (fold.contains("corrections en attente") || fold.contains("correction en attente")) {
            return !fold.contains("combien");
        }
        if (fold.contains("liste") && fold.contains("correction")) return true;
        return fold.contains("quoi corriger") || fold.contains("problemes a corriger");
    }

    static boolean looksLikeCountPending(String fold) {
        if (fold == null) return false;
        boolean combien = fold.contains("combien");
        boolean corr = fold.contains("correction") || fold.contains("corrig");
        boolean attente = fold.contains("attente") || fold.contains("reste");
        return combien && corr && attente;
    }

    private static String fold(String text) {
        String n = SpeechInputNormalizer.fold(text);
        return n.replace('\'', ' ').replace('’', ' ')
                .toLowerCase(Locale.ROOT);
    }
}
