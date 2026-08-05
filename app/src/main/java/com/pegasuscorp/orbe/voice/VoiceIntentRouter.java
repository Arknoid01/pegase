package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.pegasuscorp.orbe.diag.CorrectionsEditor;
import com.pegasuscorp.orbe.notepad.NotepadEditor;
import com.pegasuscorp.orbe.voice.handlers.BureauIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.CopilotIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.DiagIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.IntentHandler;
import com.pegasuscorp.orbe.voice.handlers.KnowledgeIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.LifeIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.MediaIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.OrionIntentHandler;
import com.pegasuscorp.orbe.voice.handlers.SystemIntentHandler;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Détecte les intentions vocales fréquentes avec score de confiance et confirmation si besoin.
 */
public final class VoiceIntentRouter {

    public static final class RoutedIntent {
        public final String forLlm;
        public final String directToolJson;
        public final String intentHint;
        public final double confidence;
        public final boolean needsConfirmation;
        public final boolean teachOnly;
        public final String teachUtterance;
        public final List<DisambiguationOption> disambiguationOptions;

        public RoutedIntent(String forLlm, String directToolJson, String intentHint,
                            double confidence, boolean needsConfirmation) {
            this(forLlm, directToolJson, intentHint, confidence, needsConfirmation,
                    false, null, null);
        }

        public RoutedIntent(String forLlm, String directToolJson, String intentHint,
                            double confidence, boolean needsConfirmation,
                            boolean teachOnly, String teachUtterance,
                            List<DisambiguationOption> disambiguationOptions) {
            this.forLlm = forLlm;
            this.directToolJson = directToolJson;
            this.intentHint = intentHint;
            this.confidence = confidence;
            this.needsConfirmation = needsConfirmation;
            this.teachOnly = teachOnly;
            this.teachUtterance = teachUtterance;
            this.disambiguationOptions = disambiguationOptions;
        }

        public boolean needsDisambiguation() {
            return disambiguationOptions != null && disambiguationOptions.size() >= 2;
        }

        public static RoutedIntent llmOnly(String text) {
            return new RoutedIntent(text, null, null, 0, false);
        }

        public static RoutedIntent withHint(String text, String hint) {
            return new RoutedIntent(text + "\n[Intention vocale : " + hint + "]", null, hint, 0.4, false);
        }

        public static RoutedIntent teach(String teachUtterance, String actionPhrase,
                String toolJson, String intentHint, String question) {
            return new RoutedIntent(
                    actionPhrase, toolJson, intentHint, 1.0, true,
                    true, teachUtterance, null);
        }
    }

    public static final class DisambiguationOption {
        public final String label;
        public final String toolJson;
        public final String intentHint;

        public DisambiguationOption(String label, String toolJson, String intentHint) {
            this.label = label;
            this.toolJson = toolJson;
            this.intentHint = intentHint;
        }
    }

    private static final IntentHandler[] HANDLERS = {
            new CopilotIntentHandler(),
            new LifeIntentHandler(),
            new DiagIntentHandler(),
            new OrionIntentHandler(),
            new KnowledgeIntentHandler(),
            new MediaIntentHandler(),
            new SystemIntentHandler(),
            new BureauIntentHandler(),
    };

    private VoiceIntentRouter() {}

    public static RoutedIntent analyze(Context context, String normalizedTranscript) {
        if (normalizedTranscript == null || normalizedTranscript.trim().isEmpty()) {
            return RoutedIntent.llmOnly("");
        }
        String text = normalizedTranscript.trim();
        String fold = SpeechInputNormalizer.fold(text);

        if (context != null) {
            VoiceIntentLearnStore store = VoiceIntentLearnStore.getInstance(context);
            List<VoiceIntentLearnStore.LearnMatch> candidates = store.matchCandidates(text);
            if (!candidates.isEmpty()) {
                if (store.needsDisambiguation(candidates)) {
                    return disambiguationIntent(context, text, candidates);
                }
                VoiceIntentLearnStore.LearnMatch learned = candidates.get(0);
                if (learned.group.toolJson != null && !learned.group.toolJson.isEmpty()) {
                    store.match(text);
                    boolean confirm = learned.variant.confirmations < 3
                            || VoiceConfirmation.needsConfirmation(context, learned.score,
                            learned.group.intentHint, text);
                    return new RoutedIntent(
                            text,
                            learned.group.toolJson,
                            learned.group.intentHint,
                            learned.score,
                            confirm);
                }
            }
        }

        if (NotepadEditor.looksLikeNotepadEdit(text)) {
            return RoutedIntent.llmOnly(text);
        }
        if (com.pegasuscorp.orbe.copilot.CopilotHintsEditor.looksLikeHintsEdit(text)) {
            return RoutedIntent.llmOnly(text);
        }
        if (CorrectionsEditor.looksLikeCorrectionsCommand(text)) {
            return RoutedIntent.llmOnly(text);
        }

        for (IntentHandler h : HANDLERS) {
            RoutedIntent hit = h.tryHandle(context, text, fold);
            if (hit != null) return hit;
        }

        RoutedIntent vague = routeVaguePhrase(context, text);
        if (vague != null) return vague;

        return RoutedIntent.llmOnly(text);
    }

    private static RoutedIntent routeVaguePhrase(Context context, String text) {
        if (!VoicePhraseClarity.isVague(text)) return null;
        String hint = VoicePhraseClarity.guessIntentHint(text);
        try {
            switch (hint) {
                case "spotify":
                    return VoiceIntentSupport.routed(context, text,
                            VoiceIntentSupport.toolJson("spotify", new JSONObject().put("action", "play")),
                            "spotify", 0.62);
                case "météo":
                    return VoiceIntentSupport.routed(context, text,
                            VoiceIntentSupport.toolJson("weather", new JSONObject().put("days", 1)),
                            "météo", 0.6);
                case "actualités":
                    return VoiceIntentSupport.routed(context, text,
                            VoiceIntentSupport.toolJson("news", new JSONObject()),
                            "actualités", 0.58);
                case "sports":
                    return RoutedIntent.withHint(text,
                            "sport — précise l'équipe ou le match");
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Compat sans Context (tests). */
    public static RoutedIntent analyze(String normalizedTranscript) {
        return analyze(null, normalizedTranscript);
    }

    public static String extractTeamPublic(String text, String fold) {
        return KnowledgeIntentHandler.extractTeam(text, fold);
    }

    public static String searchSportsJson(String team, String type, String userText) {
        return KnowledgeIntentHandler.searchSportsJson(team, type, userText);
    }

    private static RoutedIntent disambiguationIntent(Context context, String text,
            List<VoiceIntentLearnStore.LearnMatch> candidates) {
        List<DisambiguationOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            VoiceIntentLearnStore.LearnMatch m = candidates.get(i);
            String label = m.group.label;
            if (label == null || label.isEmpty()) {
                label = m.group.intentHint.isEmpty() ? "option " + (i + 1) : m.group.intentHint;
            }
            options.add(new DisambiguationOption(label, m.group.toolJson, m.group.intentHint));
        }
        return new RoutedIntent(text, null, null, candidates.get(0).score, false,
                false, null, options);
    }

    /** Résout une action pour le mode professeur (simple ou composite). */
    public static RoutedIntent resolveTeachAction(Context context, String actionPhrase) {
        if (actionPhrase == null || actionPhrase.trim().isEmpty()) {
            return RoutedIntent.llmOnly("");
        }
        List<String> parts = VoiceTeacherParser.actionParts(actionPhrase);
        if (parts.size() <= 1) {
            return analyze(context, actionPhrase.trim());
        }
        try {
            List<String> stepJsons = new ArrayList<>();
            String lastHint = "";
            for (String part : parts) {
                RoutedIntent step = analyze(context, part.trim());
                if (step.directToolJson == null) {
                    return RoutedIntent.llmOnly(actionPhrase);
                }
                stepJsons.add(step.directToolJson);
                if (step.intentHint != null && !step.intentHint.isEmpty()) {
                    lastHint = step.intentHint;
                }
            }
            String composite = LearnedToolPayload.buildComposite(stepJsons, actionPhrase.trim());
            return new RoutedIntent(actionPhrase, composite, lastHint, 0.95, true);
        } catch (Exception e) {
            return RoutedIntent.llmOnly(actionPhrase);
        }
    }
}
