package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.pcshell.PcShellCommandMatcher;
import com.pegasuscorp.orbe.pcshell.PcShellCommandStore;
import com.pegasuscorp.orbe.pcshell.SavedCommand;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intentions vocales Terminal PC — ouverture UI, exécution de commandes enregistrées (match flou).
 */
public final class PcShellIntentHandler implements IntentHandler {

    private static final Pattern EXECUTE_LABEL = Pattern.compile(
            "(?i)(?:execute|exécute|executer|exécuter|lance|lancer|demarre|démarre)\\s+(?:la\\s+commande\\s+)?(.+)$");
    private static final Pattern LIST_COMMANDS = Pattern.compile(
            "(?i)(?:liste|list|quelles|quels|montre|affiche).*(?:commandes?|raccourcis?).*(?:terminal|pc)");

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (!looksLikePcShell(fold)) return null;

        if (looksLikeOpenTerminal(fold)) {
            return routeOpen(context, text);
        }
        if (LIST_COMMANDS.matcher(fold).find() || fold.contains("commandes terminal")
                || fold.contains("commandes du terminal") || fold.contains("commandes pc")) {
            return routeList(context, text);
        }
        RoutedIntent execute = routeExecute(context, text, fold);
        if (execute != null) return execute;

        return null;
    }

    static boolean looksLikePcShell(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (fold.contains("terminal pc")
                || fold.contains("terminal pegase")
                || fold.contains("terminal pégase")
                || fold.contains("shell pc")
                || fold.contains("pc shell")
                || LIST_COMMANDS.matcher(fold).find()) {
            return true;
        }
        if (fold.contains("terminal") && (fold.contains("ouvre") || fold.contains("execute")
                || fold.contains("exécute") || fold.contains("lance") || fold.contains("commande"))) {
            return true;
        }
        return fold.startsWith("execute ") || fold.startsWith("exécute ")
                || fold.startsWith("executer ") || fold.startsWith("exécuter ")
                || fold.startsWith("lance ") || fold.startsWith("lancer ");
    }

    static boolean looksLikeOpenTerminal(String fold) {
        return fold.contains("ouvre") && fold.contains("terminal")
                || fold.equals("terminal pc")
                || fold.startsWith("terminal pc ")
                || fold.contains("ouvre le terminal");
    }

    static RoutedIntent routeOpen(Context context, String text) {
        try {
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("pc_shell", new JSONObject().put("action", "open")),
                    "terminal pc", 0.95);
        } catch (Exception e) {
            return null;
        }
    }

    static RoutedIntent routeList(Context context, String text) {
        try {
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("pc_shell", new JSONObject().put("action", "list")),
                    "terminal pc", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static RoutedIntent routeExecute(Context context, String text, String fold) {
        String spokenLabel = extractExecuteLabel(text, fold);
        if (spokenLabel == null || spokenLabel.isEmpty()) return null;

        List<SavedCommand> cmds = PcShellCommandStore.getInstance(context).listAll();
        if (cmds.isEmpty()) {
            return RoutedIntent.withHint(text,
                    "terminal PC — aucune commande enregistrée ; ajoute-en une dans l'app");
        }

        PcShellCommandMatcher.Match match = PcShellCommandMatcher.bestMatch(cmds, spokenLabel);
        if (match == null) {
            try {
                return VoiceIntentSupport.routed(context, text,
                        VoiceIntentSupport.toolJson("pc_shell", new JSONObject()
                                .put("action", "nomatch")
                                .put("spokenLabel", spokenLabel)),
                        "terminal pc", 0.92);
            } catch (Exception e) {
                return null;
            }
        }

        try {
            JSONObject p = new JSONObject()
                    .put("action", "execute")
                    .put("commandId", match.command.id)
                    .put("label", match.command.label)
                    .put("requiresSudo", PcShellCommandMatcher.requiresSudo(match.command));
            String json = VoiceIntentSupport.toolJson("pc_shell", p);
            boolean confirm = PcShellCommandMatcher.requiresSudo(match.command)
                    || match.score < PcShellCommandMatcher.HIGH_CONFIDENCE;
            double confidence = Math.min(0.98, match.score);
            return new RoutedIntent(text, json, "terminal pc", confidence, confirm);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractExecuteLabel(String text, String fold) {
        if (text == null) return null;
        Matcher m = EXECUTE_LABEL.matcher(text.trim());
        if (m.find()) {
            return cleanLabel(m.group(1));
        }
        if (fold != null && (fold.startsWith("execute ") || fold.startsWith("exécute ")
                || fold.startsWith("lance ") || fold.startsWith("lancer "))) {
            int idx = text.indexOf(' ');
            if (idx > 0 && idx + 1 < text.length()) {
                return cleanLabel(text.substring(idx + 1));
            }
        }
        return null;
    }

    static String cleanLabel(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[?.!]+$", "").trim();
        s = s.replaceAll("(?i)\\s+(?:sur|dans)\\s+(?:le|mon|ton)?\\s*pc\\s*$", "");
        s = s.replaceAll("(?i)\\s+(?:sur|dans)\\s+(?:le|mon)?\\s*terminal\\s*$", "");
        return s.trim();
    }
}
