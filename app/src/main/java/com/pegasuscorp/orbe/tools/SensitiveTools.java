package com.pegasuscorp.orbe.tools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Outils dont les arguments et résultats contiennent des données de tiers ou très personnelles
 * (messages, notifications, contacts, presse-papiers, usage, photos, fichiers, sortie de commandes
 * PC, serveurs externes). Leur contenu ne doit pas être écrit en clair dans la trace de
 * diagnostic, ni dans l'historique persisté / synchronisé, ni dans la mémoire durable.
 */
public final class SensitiveTools {

    private static final Set<String> IDS = new HashSet<>(Arrays.asList(
            "notifications", "sms", "contacts", "clipboard", "call", "app_usage", "read_image",
            "screen_capture", "files", "pc_shell", "web_read", "messageries", "mails"));

    private SensitiveTools() {}

    public static boolean isSensitive(String toolId) {
        if (toolId == null) return false;
        String id = toolId.trim().toLowerCase(Locale.ROOT);
        return id.startsWith("mcp_") || IDS.contains(id);
    }
}
