package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.conversation.InteractionStateStore;
import com.pegasuscorp.orbe.copilot.CopilotAppHints;
import com.pegasuscorp.orbe.copilot.CopilotAppHintsStore;
import com.pegasuscorp.orbe.copilot.CopilotScreenContext;
import com.pegasuscorp.orbe.intentions.location.LocationSituationReader;
import com.pegasuscorp.orbe.session.Channel;

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
    /** Soft/hard pour clip souvenir (frontière de phrase). */
    static final int MEMORY_SOFT_MAX = 160;
    static final int MEMORY_HARD_MAX = 320;
    /** Plafond écran copilote (chars extrait) — le reste du bloc est fixe. */
    private static final int DEFAULT_SCREEN_CHARS = 2_000;

    private ContextBuilder() {}

    public static String build(Context context, String userMessage) {
        return buildSnapshot(context, userMessage).promptText;
    }

    public static String build(Context context, String userMessage, ContextIntent intent) {
        return build(context, userMessage, intent, Channel.TEXT);
    }

    public static String build(Context context, String userMessage, ContextIntent intent,
            Channel channel) {
        return buildSnapshot(context, userMessage, intent, channel).promptText;
    }

    public static String build(Context context, String userMessage, ContextIntent intent,
            Channel channel, int screenMaxChars) {
        return buildSnapshot(context, userMessage, intent, channel, screenMaxChars).promptText;
    }

    public static ContextSnapshot buildSnapshot(Context context, String userMessage) {
        return buildSnapshot(context, userMessage, ContextAnalyzer.analyze(context, userMessage));
    }

    public static ContextSnapshot buildSnapshot(Context context, String userMessage,
            ContextIntent intent) {
        return buildSnapshot(context, userMessage, intent, Channel.TEXT);
    }

    public static ContextSnapshot buildSnapshot(Context context, String userMessage,
            ContextIntent intent, Channel channel) {
        return buildSnapshot(context, userMessage, intent, channel, DEFAULT_SCREEN_CHARS);
    }

    public static ContextSnapshot buildSnapshot(Context context, String userMessage,
            ContextIntent intent, Channel channel, int screenMaxChars) {
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
        String screenLabel = "";

        String clock = buildDeviceClock();
        String profileBlock = buildProfileBlock(profile, intent, profileOut);
        String interaction = buildInteractionBlock(context);
        String atlas = buildAtlasBlock(entities, atlasOut);
        String namedPtr = buildNamedContextsPointer(context, contextsOut);
        String session = "";
        if (!skipSession(intent)) {
            sessionTopic = "";
            String[] sessionParts = buildSessionBlock(repo, intent);
            session = sessionParts[0];
            sessionTopic = sessionParts[1];
        }
        String memories = "";
        if (!skipMemories(intent, channel)) {
            memories = buildMemoriesBlock(context, repo, userMessage, entities, intent,
                    memoriesOut);
        }
        String screen = "";
        if (channel == Channel.COPILOT) {
            int cap = screenMaxChars > 0 ? screenMaxChars : DEFAULT_SCREEN_CHARS;
            String[] screenParts = buildCopilotScreen(context, cap);
            screen = screenParts[0];
            screenLabel = screenParts[1];
        }

        // Budget chars : contextBudget historique ≈ échelle tokens → ×6 chars, plancher 1200.
        int charBudget = Math.max(1_200, intent.contextBudget * 6);
        String assembled = assembleWithinBudget(charBudget,
                clock, atlas, memories, session, namedPtr, profileBlock, interaction, screen);

        return new ContextSnapshot(assembled, intent.intent,
                memoriesOut, atlasOut, profileOut, contextsOut, sessionTopic, screenLabel);
    }

    /**
     * Ordre de conservation : horloge → atlas → souvenirs → session → pointeur contextes
     * → profil → interaction → écran.
     */
    static String assembleWithinBudget(int charBudget, String clock, String atlas,
            String memories, String session, String namedPtr, String profile,
            String interaction, String screen) {
        String[] blocks = {
                nullToEmpty(clock),
                nullToEmpty(atlas),
                nullToEmpty(memories),
                nullToEmpty(session),
                nullToEmpty(namedPtr),
                nullToEmpty(profile),
                nullToEmpty(interaction),
                nullToEmpty(screen)
        };
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            if (block.isEmpty()) continue;
            if (sb.length() + block.length() <= charBudget) {
                sb.append(block);
                continue;
            }
            int remain = charBudget - sb.length();
            if (remain < 40) break;
            sb.append(TextClipper.clipAtSentence(block, Math.min(80, remain / 2), remain));
            break;
        }
        return sb.toString();
    }

    public static ContextIntent analyzeIntent(Context context, String userMessage) {
        return ContextAnalyzer.analyze(context, userMessage);
    }

    private static boolean skipMemories(ContextIntent intent, Channel channel) {
        if ("fresh_data".equals(intent.intent) || "music".equals(intent.intent)) {
            return true;
        }
        // Clic UI / device : l'écran copilote suffit.
        if ("device".equals(intent.intent) && channel == Channel.COPILOT) {
            return true;
        }
        return false;
    }

    private static boolean skipSession(ContextIntent intent) {
        return "fresh_data".equals(intent.intent)
                || "music".equals(intent.intent)
                || "device".equals(intent.intent);
    }

    private static String buildDeviceClock() {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    private static String buildProfileBlock(UserProfileStore profile, ContextIntent intent,
            List<String> profileOut) {
        EnumSet<ProfileSection> sections = intent.profileSections;
        if (sections == null || sections.isEmpty()) {
            sections = EnumSet.of(ProfileSection.ESSENTIAL);
        }
        // NOTES uniquement pour intent memory.
        if (!"memory".equals(intent.intent)) {
            sections = EnumSet.copyOf(sections);
            sections.remove(ProfileSection.NOTES);
        }
        StringBuilder sb = new StringBuilder();
        profile.appendSelectiveSections(sb, sections);
        for (ProfileSection s : sections) {
            profileOut.add(labelForProfileSection(s));
        }
        return sb.toString();
    }

    private static String buildInteractionBlock(Context context) {
        StringBuilder sb = new StringBuilder();
        InteractionStateStore.getInstance(context).appendPromptSection(sb);
        return sb.toString();
    }

    private static String buildNamedContextsPointer(Context context, List<String> contextsOut) {
        ContextualFileStore store = ContextualFileStore.getInstance(context);
        String pointer = store.buildPromptPointer();
        List<String> names = store.getLoadedDisplayNames();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.trim().isEmpty()) contextsOut.add(n.trim());
            }
        }
        return pointer != null ? pointer : "";
    }

    private static String buildAtlasBlock(EntityResolver.Resolution entities,
            List<String> atlasOut) {
        List<EntityResolver.EntityMatch> inject = entities.forInjection(MAX_ENTITIES);
        if (inject.isEmpty() && entities.ambiguous.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    /** @return [block, topic] */
    private static String[] buildSessionBlock(MemoryRepository repo, ContextIntent intent) {
        SessionSummary latest = repo.getLatestSessionSummary();
        if (latest == null || latest.summary == null || latest.summary.isEmpty()) {
            return new String[]{"", ""};
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Contexte récent ---\n");
        String topic = latest.topic != null ? latest.topic.trim() : "";
        if (!topic.isEmpty()) sb.append("Sujet : ").append(topic).append(". ");
        String summary = TextClipper.clipAtSentence(latest.summary, 120, 220);
        sb.append(summary).append("\n");
        appendSessionDecisions(sb, latest);
        appendSessionPending(sb, latest);
        String topicOut = !topic.isEmpty() ? topic : clip(summary, 80);
        return new String[]{sb.toString(), topicOut};
    }

    private static void appendSessionDecisions(StringBuilder sb, SessionSummary latest) {
        if (latest.decisions.isEmpty()) return;
        sb.append("Décisions : ");
        int limit = Math.min(3, latest.decisions.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" ; ");
            sb.append(clip(latest.decisions.get(i), 100));
        }
        sb.append("\n");
    }

    private static void appendSessionPending(StringBuilder sb, SessionSummary latest) {
        if (latest.pendingTopics.isEmpty()) return;
        sb.append("En attente : ");
        int limit = Math.min(3, latest.pendingTopics.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" ; ");
            sb.append(clip(latest.pendingTopics.get(i), 100));
        }
        sb.append("\n");
    }

    private static String buildMemoriesBlock(Context context, MemoryRepository repo,
            String userMessage, EntityResolver.Resolution entities, ContextIntent intent,
            List<String> memoriesOut) {
        float minScore = MemoryRepository.SEMANTIC_MIN_SCORE + 0.05f;
        if ("general".equals(intent.intent)) {
            minScore = MemoryRepository.SEMANTIC_MIN_SCORE + 0.08f;
        }
        int max = 1;
        if ("project".equals(intent.intent) || "person".equals(intent.intent)
                || "memory".equals(intent.intent)) {
            max = 2;
        }

        List<String> entityTerms = EntityResolver.termsForScoring(entities);
        String placeTerm = LocationSituationReader.currentPlaceSearchTerm(context);
        if (placeTerm != null && !placeTerm.isEmpty()) {
            entityTerms = new ArrayList<>(entityTerms);
            entityTerms.add(placeTerm);
        }
        List<String> seedEntityIds = MemoryLinker.seedEntityIds(entities, 3);
        List<MemoryEntry> memories = repo.getRelevantMemoriesSemantic(
                userMessage, entityTerms, seedEntityIds, max, minScore);
        if (memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Souvenirs pertinents ---\n");
        for (MemoryEntry m : memories) {
            String content = TextClipper.clipAtSentence(
                    m.content != null ? m.content : "", MEMORY_SOFT_MAX, MEMORY_HARD_MAX);
            if (content.isEmpty()) continue;
            sb.append("- [").append(m.category).append("] ").append(content).append("\n");
            memoriesOut.add(clip(content, 100));
        }
        return sb.toString();
    }

    /** @return [block, packageLabel] */
    private static String[] buildCopilotScreen(Context context, int maxTextChars) {
        CopilotScreenContext.Snapshot snap = CopilotScreenContext.readFresh(context);
        if (snap == null) return new String[]{"", ""};
        String text = snap.text;
        if (text.length() > maxTextChars) {
            text = TextClipper.clipAtSentence(text, Math.max(80, maxTextChars / 2), maxTextChars);
        }
        CopilotScreenContext.Snapshot clipped =
                new CopilotScreenContext.Snapshot(snap.packageName, text, snap.ageMs);
        CopilotAppHints hints = CopilotAppHintsStore.get(context, snap.packageName);
        return new String[]{
                CopilotScreenContext.buildPromptBlock(clipped, hints),
                snap.packageName
        };
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

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
