package com.pegasuscorp.orbe.session;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;
import com.pegasuscorp.orbe.voice.VoiceConfirmation;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confirmation ou choix outil en attente — réponse via discussion / voix (pas de popup).
 */
public final class PendingToolConfirm {

    private static final Pattern NUMBER = Pattern.compile(
            "(?i)(?:^|\\b)(?:num[eé]ro\\s*)?([1-9]\\d?)\\b");

    private static String question;
    private static Runnable onConfirm;
    private static Runnable onCancel;
    private static String[] choiceLabels;
    private static IntConsumer onChosen;

    private PendingToolConfirm() {}

    /** Oui / Non. */
    public static synchronized void set(String q, Runnable confirm, Runnable cancel) {
        clear();
        question = q;
        onConfirm = confirm;
        onCancel = cancel;
    }

    /** Choix numéroté (1…N). */
    public static synchronized void setChoice(String q, String[] labels,
            IntConsumer chosen, Runnable cancel) {
        clear();
        question = q;
        choiceLabels = labels != null ? labels.clone() : null;
        onChosen = chosen;
        onCancel = cancel;
    }

    public static synchronized boolean hasPending() {
        return onConfirm != null || onChosen != null;
    }

    public static synchronized boolean isChoice() {
        return onChosen != null && choiceLabels != null && choiceLabels.length > 0;
    }

    public static synchronized String question() {
        return question;
    }

    public static synchronized String[] labels() {
        return choiceLabels != null ? choiceLabels.clone() : null;
    }

    /**
     * Interprète la réponse utilisateur.
     * @return true si consommé (ne pas envoyer au LLM)
     */
    public static synchronized boolean tryResolve(String userText) {
        if (!hasPending()) return false;
        if (userText == null || userText.trim().isEmpty()) return false;

        if (VoiceConfirmation.isCancel(userText) || VoiceConfirmation.isNo(userText)) {
            reject();
            return true;
        }

        if (isChoice()) {
            Integer idx = parseChoiceIndex(userText, choiceLabels.length);
            if (idx == null) idx = parseChoiceByLabel(userText);
            if (idx != null) {
                IntConsumer c = onChosen;
                clear();
                if (c != null) c.accept(idx);
                return true;
            }
            return false;
        }

        if (VoiceConfirmation.isYesForPending(userText) || VoiceConfirmation.isYes(userText)) {
            accept();
            return true;
        }
        return false;
    }

    /** Index 0-based, ou null si non reconnu. */
    public static Integer parseChoiceIndex(String userText, int count) {
        if (userText == null || count <= 0) return null;
        String fold = SpeechInputNormalizer.fold(userText).replace('\'', ' ').trim();
        if (fold.isEmpty()) return null;

        // "premier" / "deuxième" …
        if (fold.contains("premier") || fold.equals("un") || fold.equals("1er")) {
            return 0;
        }
        if (count >= 2 && (fold.contains("deuxieme") || fold.contains("second")
                || fold.equals("deux"))) {
            return 1;
        }
        if (count >= 3 && (fold.contains("troisieme") || fold.equals("trois"))) {
            return 2;
        }

        Matcher m = NUMBER.matcher(fold);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= count) return n - 1;
            } catch (NumberFormatException ignored) {
            }
        }
        // Match partiel sur un label
        for (int i = 0; i < count; i++) {
            // labels may not be available in static parse — handled in tryResolve with labels
        }
        return null;
    }

    /** Match label fragment (GPU name etc.). */
    public static synchronized Integer parseChoiceByLabel(String userText) {
        if (choiceLabels == null || userText == null) return null;
        String fold = SpeechInputNormalizer.fold(userText).replace('\'', ' ').trim();
        if (fold.length() < 2) return null;
        Integer best = null;
        for (int i = 0; i < choiceLabels.length; i++) {
            String lab = SpeechInputNormalizer.fold(choiceLabels[i])
                    .replace('\'', ' ').toLowerCase(Locale.ROOT);
            if (lab.contains(fold) || fold.contains(lab.replaceAll("^\\d+\\.\\s*", ""))) {
                if (best != null) return null; // ambigu
                best = i;
            }
        }
        return best;
    }

    public static synchronized void accept() {
        Runnable r = onConfirm;
        clear();
        if (r != null) r.run();
    }

    public static synchronized void reject() {
        Runnable r = onCancel;
        clear();
        if (r != null) r.run();
    }

    public static synchronized void clear() {
        question = null;
        onConfirm = null;
        onCancel = null;
        choiceLabels = null;
        onChosen = null;
    }

    /** Texte à afficher / parler pour un choix. */
    public static String formatChoicePrompt(String title, String[] labels) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) {
            sb.append(title.trim()).append('\n');
        }
        if (labels != null) {
            for (int i = 0; i < labels.length; i++) {
                String lab = labels[i] != null ? labels[i].trim() : "";
                // Évite double numérotation si déjà "1. …"
                if (!lab.matches("^\\d+[.)].*")) {
                    sb.append(i + 1).append(". ");
                }
                sb.append(lab).append('\n');
            }
        }
        sb.append("Réponds avec le numéro, ou annule.");
        return sb.toString().trim();
    }
}
