package com.pegasuscorp.orbe.conversation;

/** Humeur d'interaction (pas une émotion réelle). */
public enum InteractionMood {
    NORMAL,
    JOUEUR,
    CONCENTRE,
    REFLEXION,
    CONTENT;

    public static InteractionMood fromString(String value) {
        if (value == null) return NORMAL;
        try {
            return InteractionMood.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return NORMAL;
        }
    }
}
