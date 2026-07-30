package com.pegasuscorp.orbe.conversation;

import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.llm.PegasePrompt;
import com.pegasuscorp.orbe.voice.VoiceManager;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Livraison vocale humaine : micro-silences, réflexion à voix haute (parfois).
 */
public final class ResponseDelivery {

    private static final float THINK_ALOUD_CHANCE = 0.18f;
    private static final int SHORT_DELAY_MS = 300;
    private static final int LONG_DELAY_MS = 700;
    private static final int LONG_RESPONSE_WORDS = 18;

    private static final String[] THINKING_NORMAL = {
            "Voyons voir...", "Hmm...", "Attends...", "Laisse-moi voir..."
    };
    private static final String[] THINKING_JOUEUR = {
            "Hmm...", "Voyons voir...", "Attends deux secondes..."
    };
    private static final String[] THINKING_REFLEXION = {
            "Hmm...", "Bonne question...", "Voyons voir..."
    };

    private final Handler main = new Handler(Looper.getMainLooper());

    public void speak(VoiceManager voiceManager, String rawText,
                      InteractionMood mood, Runnable onComplete) {
        String text = PegasePrompt.sanitizeForSpeech(rawText);
        if (text.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        long delay = wordCount(text) >= LONG_RESPONSE_WORDS ? LONG_DELAY_MS : SHORT_DELAY_MS;
        Runnable speakAnswer = () -> main.postDelayed(
                () -> voiceManager.speak(text, onComplete), delay);

        if (shouldThinkAloud(text)) {
            String filler = pickThinkingPhrase(mood);
            voiceManager.speak(filler, speakAnswer);
        } else {
            speakAnswer.run();
        }
    }

    private static boolean shouldThinkAloud(String text) {
        int words = wordCount(text);
        if (words <= 4) return false;
        if (isTinyReply(text)) return false;
        return ThreadLocalRandom.current().nextFloat() < THINK_ALOUD_CHANCE;
    }

    private static boolean isTinyReply(String text) {
        String t = text.toLowerCase().trim();
        return t.equals("oui") || t.equals("ouais") || t.equals("ah") || t.equals("ok")
                || t.equals("non") || t.equals("bien") || t.equals("d'accord")
                || t.equals("exact") || t.equals("voilà") || t.equals("voila");
    }

    private static String pickThinkingPhrase(InteractionMood mood) {
        String[] pool = THINKING_NORMAL;
        if (mood == InteractionMood.JOUEUR) pool = THINKING_JOUEUR;
        else if (mood == InteractionMood.REFLEXION || mood == InteractionMood.CONCENTRE) {
            pool = THINKING_REFLEXION;
        }
        return pool[ThreadLocalRandom.current().nextInt(pool.length)];
    }

    private static int wordCount(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }
}
