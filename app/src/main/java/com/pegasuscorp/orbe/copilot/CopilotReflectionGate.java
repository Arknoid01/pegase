package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.regex.Pattern;

/**
 * Heuristique locale — déclenche une passe de planification cachée uniquement
 * pour les tours copilote complexes avec contexte écran frais (P3 v3).
 */
public final class CopilotReflectionGate {

    private static final Pattern ACTION = Pattern.compile(
            "\\b(clique|ouvr|tradui|compar|resum|expliqu|aide|cherche|trouv|lis|copie|"
                    + "selectionn|appui|tape|defini|active|desactive)\\w*");
    private static final Pattern MULTI_STEP = Pattern.compile(
            "\\b(ensuite|puis|d abord|avant de|et apres|et puis)\\b");
    private static final Pattern SCREEN_REF = Pattern.compile(
            "\\b(cette page|cet ecran|a l ecran|sur l ecran|ce texte|cette video|"
                    + "ce que je vois|l ecran)\\b");
    private static final Pattern COMPLEX_Q = Pattern.compile(
            "\\b(que faire|comment|dois je|est ce que|pourquoi|qu est ce)\\b");
    private static final Pattern SKIP_SHORT = Pattern.compile(
            "\\b(salut|bonjour|merci|ok|oui|non|annule|retiens|memorise|souviens)\\b");

    private CopilotReflectionGate() {}

    public static boolean needsReflection(Context ctx, String userText) {
        if (ctx == null || TextUtils.isEmpty(userText)) return false;
        if (CopilotScreenContext.readFresh(ctx) == null) return false;

        String fold = fold(userText);
        if (fold.length() < 8) return false;
        if (SKIP_SHORT.matcher(fold).find() && fold.length() < 48) return false;

        boolean action = ACTION.matcher(fold).find();
        boolean multi = MULTI_STEP.matcher(fold).find();
        boolean screenQuestion = SCREEN_REF.matcher(fold).find()
                && COMPLEX_Q.matcher(fold).find();
        boolean decision = fold.contains("que faire") || fold.contains("dois je");
        return action || multi || screenQuestion || decision;
    }

    static String fold(String text) {
        return SpeechInputNormalizer.fold(text).replace('\'', ' ').trim();
    }
}
