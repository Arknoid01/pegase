package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.pegasuscorp.orbe.R;

/**
 * Hint vocal « aide » — découverte des surfaces mains libres (v3 P1).
 */
public final class VoiceHelpHints {

    private VoiceHelpHints() {}

    public static boolean isHelpRequest(String transcript) {
        if (transcript == null) return false;
        String fold = SpeechInputNormalizer.fold(transcript).trim();
        return fold.equals("aide")
                || fold.equals("help")
                || fold.startsWith("aide ")
                || fold.contains(" que peux tu faire")
                || fold.contains(" qu est ce que tu peux faire")
                || fold.contains(" comment ca marche");
    }

    public static String buildHelpMessage(Context context) {
        return context.getString(R.string.voice_help_message);
    }
}
