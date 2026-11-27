package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contexte actif déterministe (règles, aucun modèle) : le dernier objet dont l'utilisateur
 * a parlé, pour que « elle », « la » ou « celui-là » retrouvent leur cible.
 */
public final class ActiveContext {

    private static final Pattern OBJET = Pattern.compile(
            "\\bj'(?:ai|en ai)\\s+(?:acheté|eu|pris|reçu|trouvé)?\\s*(?:une?|des|mon|ma|mes)?\\s*([\\wéèàç-]+)",
            Pattern.CASE_INSENSITIVE);

    private ActiveContext() {}

    /** Retourne une ligne « [Contexte actif] … » ou une chaîne vide si rien n'est trouvé. */
    public static String fromHistory(List<ChatBackend.Turn> history) {
        if (history == null || history.isEmpty()) return "";
        String objet = null;
        for (ChatBackend.Turn t : history) {
            if (t == null || !t.fromUser || t.text == null) continue;
            Matcher m = OBJET.matcher(t.text);
            if (m.find()) objet = m.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        return objet == null ? "" : "[Contexte actif] dernier objet évoqué : " + objet + ".";
    }
}
