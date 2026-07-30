package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.conversation.InteractionStateStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Construit le contexte injecté dans le prompt : profil sélectif, atlas, souvenirs filtrés.
 * Produit aussi un {@link ContextSnapshot} pour la carte 🔍.
 */
public final class ContextBuilder {

    private static final int MAX_ENTITIES = 2;

    private ContextBuilder() {}

    public static String build(Context context, String userMessage) {
        return buildSnapshot(context, userMessage).promptText;
    }

    public static String build(Context context, String userMessage, ContextIntent intent) {
        return buildSnapshot(context, userMessage, intent).promptText;
    }

    public static ContextSnapshot buildSnapshot(Context context, String userMessage) {
        return buildSnapshot(context, userMessage, ContextAnalyzer.analyze(context, userMessage));
    }

    public static ContextSnapshot buildSnapshot(Context context, String userMessage,
            ContextIntent intent) {
        if (context == null) return ContextSnapshot.empty();
        if (intent == null) intent = ContextAnalyzer.analyze(context, userMessage);

        EntityResolver.Resolution entities = EntityResolver.resolve(context, userMessage);
        MemoryRepository repo = MemoryRepository.getInstance(context);
        UserProfileStore profile = UserProfileStore.getInstance(context);

        List<String> memoriesOut = new ArrayList<>();
        List<String> atlasOut = new ArrayList<>();
        List<String> profileOut = new ArrayList<>();
        List<String> contextsOut = new ArrayList<>();
        String sessionTopic = "";

        StringBuilder sb = new StringBuilder();
        appendDeviceClock(sb);

        EnumSet<ProfileSection> sections = intent.profileSections;
        if (sections != null && !sections.isEmpty()) {
            profile.appendSelectiveSections(sb, sections);
            for (ProfileSection s : sections) {
                profileOut.add(labelForProfileSection(s));
            }
        }

        InteractionStateStore.getInstance(context).appendPromptSection(sb);

        appendAtlas(sb, entities, atlasOut);
        appendLoadedNamedContexts(sb, context, contextsOut);
        sessionTopic = appendSessionContext(sb, repo, intent);
        appendMemories(sb, repo, userMessage, entities, intent, memoriesOut);

        return new ContextSnapshot(sb.toString(), intent.intent,
                memoriesOut, atlasOut, profileOut, contextsOut, sessionTopic);
    }

    public static ContextIntent analyzeIntent(Context context, String userMessage) {
        return ContextAnalyzer.analyze(context, userMessage);
    }

    private static void appendLoadedNamedContexts(StringBuilder sb, Context context,
            List<String> contextsOut) {
        ContextualFileStore store = ContextualFileStore.getInstance(context);
        // Pointeur court seulement — le corps du .md est collé au message user
        // (AttachedContextInjector) pour ne pas le noyer derrière le pavé d'outils.
        String pointer = store.buildPromptPointer();
        if (pointer != null && !pointer.isEmpty()) {
            sb.append(pointer);
        }
        List<String> names = store.getLoadedDisplayNames();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.trim().isEmpty()) contextsOut.add(n.trim());
            }
        }
    }

    /** Date/heure locale — évite que le LLM devine « ce soir » / « aujourd'hui ». */
    private static void appendDeviceClock(StringBuilder sb) {
        Date now = new Date();
        SimpleDateFormat full = new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sb.append("\n--- Horloge appareil (fuseau ")
                .append(TimeZone.getDefault().getID())
                .append(") ---\n");
        sb.append("Maintenant : ").append(full.format(now)).append(".\n");
        sb.append("Date ISO : ").append(iso.format(now)).append(".\n");
        sb.append("Pour « ce soir », « aujourd'hui », « demain », les matchs ou actus du jour, ")
                .append("utilise cette date — ne devine pas une autre date dans les requêtes search.\n");
    }

    private static void appendAtlas(StringBuilder sb, EntityResolver.Resolution entities,
            List<String> atlasOut) {
        List<EntityResolver.EntityMatch> inject = entities.forInjection(MAX_ENTITIES);
        if (inject.isEmpty() && entities.ambiguous.isEmpty()) return;

        sb.append("\n--- Atlas (entités concernées) ---\n");
        for (EntityResolver.EntityMatch match : inject) {
            String block = match.entity.toPromptBlock();
            sb.append("- ").append(block).append("\n");
            String shortLabel = Entity.typeLabelFr(match.entity.type) + " · " + match.entity.name;
            atlasOut.add(clip(shortLabel, 80));
        }
        if (!entities.ambiguous.isEmpty()) {
            sb.append("Ambiguïté possible : ");
            for (int i = 0; i < entities.ambiguous.size(); i++) {
                if (i > 0) sb.append(" ou ");
                sb.append(entities.ambiguous.get(i).name);
            }
            sb.append(". Demande confirmation si nécessaire, ne devine pas.\n");
        }
    }

    private static String appendSessionContext(StringBuilder sb, MemoryRepository repo,
            ContextIntent intent) {
        if ("fresh_data".equals(intent.intent)
                || "music".equals(intent.intent)
                || "device".equals(intent.intent)) {
            return "";
        }
        SessionSummary latest = repo.getLatestSessionSummary();
        if (latest == null || latest.summary == null || latest.summary.isEmpty()) return "";
        sb.append("\n--- Contexte récent ---\n");
        String topic = latest.topic != null ? latest.topic.trim() : "";
        if (!topic.isEmpty()) sb.append("Sujet : ").append(topic).append(". ");
        String summary = latest.summary;
        if (summary.length() > 220) summary = summary.substring(0, 217) + "…";
        sb.append(summary).append("\n");
        return !topic.isEmpty() ? topic : clip(summary, 80);
    }

    private static void appendMemories(StringBuilder sb, MemoryRepository repo,
            String userMessage, EntityResolver.Resolution entities, ContextIntent intent,
            List<String> memoriesOut) {
        if ("fresh_data".equals(intent.intent) || "music".equals(intent.intent)) {
            return;
        }

        float minScore = "general".equals(intent.intent)
                ? MemoryRepository.SEMANTIC_MIN_SCORE
                : MemoryRepository.SEMANTIC_MIN_SCORE + 0.05f;
        int max = "project".equals(intent.intent) || "person".equals(intent.intent) ? 3 : 2;

        List<String> entityTerms = EntityResolver.termsForScoring(entities);
        List<MemoryEntry> memories = repo.getRelevantMemoriesSemantic(
                userMessage, entityTerms, max, minScore);
        if (memories.isEmpty()) return;
        sb.append("\n--- Souvenirs pertinents ---\n");
        for (MemoryEntry m : memories) {
            sb.append("- [").append(m.category).append("] ").append(m.content).append("\n");
            if (m.content != null && !m.content.trim().isEmpty()) {
                memoriesOut.add(clip(m.content.trim(), 100));
            }
        }
    }

    static String labelForProfileSection(ProfileSection s) {
        if (s == null) return "Profil";
        switch (s) {
            case ESSENTIAL: return "Identité";
            case PERSONALITY: return "Personnalité";
            case INTERESTS: return "Intérêts";
            case PROJECTS: return "Projets";
            case INTERACTION: return "Interaction";
            case NOTES: return "Notes";
            case ASSISTANT_PREFS: return "Préférences assistant";
            default: return s.name();
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
