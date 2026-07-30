package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.routing.UserExamplesStore;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Déduit l'intention et le budget de contexte sans appeler le LLM. */
public final class ContextAnalyzer {

    private static final EnumSet<ToolTag> BASE_TOOLS = EnumSet.of(
            ToolTag.NOTEPAD, ToolTag.MEMORY, ToolTag.NAMED_CONTEXT,
            ToolTag.DEVICE);

    private ContextAnalyzer() {}

    public static ContextIntent analyze(Context context, String userMessage) {
        EntityResolver.Resolution entities = EntityResolver.resolve(context, userMessage);
        String fold = SpeechInputNormalizer.fold(userMessage).replace('\'', ' ');

        UserExamplesStore.Match exampleMatch = null;
        if (context != null) {
            try {
                exampleMatch = UserExamplesStore.getInstance(context)
                        .findMatch(userMessage, UserExamplesStore.DEFAULT_MIN_SCORE);
            } catch (Exception ignored) {
            }
        }

        String intent;
        EnumSet<ToolTag> allowedTools;
        boolean requiresTool;
        boolean requiresFresh;

        if (exampleMatch != null) {
            Trace.routingMatch(userMessage, exampleMatch.tool, exampleMatch.score,
                    "user_example", exampleMatch.exact);
            if ("none".equals(exampleMatch.tool)) {
                intent = "general";
                allowedTools = EnumSet.copyOf(BASE_TOOLS);
                requiresTool = false;
                requiresFresh = false;
            } else {
                intent = intentForExampleTool(exampleMatch.tool);
                allowedTools = toolsForExampleTool(exampleMatch.tool, fold);
                requiresTool = true;
                requiresFresh = "weather".equals(exampleMatch.tool)
                        || "search".equals(exampleMatch.tool);
            }
        } else {
            intent = detectIntent(fold, entities);
            requiresTool = IntentDetector.looksLikeTool(fold);
            requiresFresh = IntentDetector.looksLikeFreshData(fold);
            allowedTools = toolsFor(intent, fold);
        }

        EnumSet<ProfileSection> sections = sectionsFor(intent, entities);
        int budget = budgetFor(intent, entities);

        List<String> entityIds = new ArrayList<>();
        for (EntityResolver.EntityMatch m : entities.forInjection(3)) {
            entityIds.add(m.entity.id);
        }

        return new ContextIntent(intent, entityIds, requiresTool, requiresFresh, budget, sections,
                allowedTools);
    }

    /** Intent ContextAnalyzer pour un id d'outil UserExamples. */
    static String intentForExampleTool(String tool) {
        if (tool == null || "none".equals(tool)) return "general";
        ToolTag tag = tagForExampleTool(tool);
        if (tag == null) return "general";
        switch (tag) {
            case DIAG:
                return "diag";
            case BRIEF:
            case NOTEPAD:
            case TIMER:
            case ALARM:
            case CALENDAR:
            case AGENDA:
            case CREATE_FILE:
                return "productivity";
            case WEATHER:
            case SEARCH:
            case NEWS:
            case NASA:
            case WEB_SEARCH:
            case WIKIPEDIA:
            case WIKIDATA:
                return "fresh_data";
            case ORION_MANAGER:
            case ORION_CODE:
            case GIT_COMMIT:
                return "orion";
            case SPOTIFY:
            case YOUTUBE:
                return "music";
            case MEMORY:
            case NAMED_CONTEXT:
                return "memory";
            case DEVICE:
            case FLASHLIGHT:
            case VOLUME:
            case CONNECTIVITY:
            case NOTIFICATIONS:
            case SETTINGS:
            case CLIPBOARD:
            case OPEN_APP:
            case OPEN_INTERFACE:
                return "device";
            case CALL:
            case SMS:
            case EMAIL:
            case CONTACTS:
            case SHARE:
            case NAVIGATION:
                return "productivity";
            case CALCULATOR:
            case FILES:
            case COMPOSITE:
            default:
                return "general";
        }
    }

    static EnumSet<ToolTag> toolsForExampleTool(String tool, String fold) {
        EnumSet<ToolTag> t = EnumSet.copyOf(BASE_TOOLS);
        ToolTag tag = tagForExampleTool(tool);
        if (tag != null) t.add(tag);
        // Enrichissement léger selon la phrase
        if (fold != null) {
            if (fold.contains("meteo") || fold.contains("quel temps")) t.add(ToolTag.WEATHER);
        }
        return t;
    }

    /** Résout le tag via le registre — couvre tous les outils sans liste manuelle. */
    private static ToolTag tagForExampleTool(String tool) {
        if (tool == null || "none".equals(tool)) return null;
        Tool found = new ToolRegistry().findById(tool);
        return found != null ? found.tag() : null;
    }

    /** Outils à inclure dans le prompt — conservateur : ambigu → tout. */
    public static EnumSet<ToolTag> toolsFor(String intent, String fold) {
        EnumSet<ToolTag> t = EnumSet.copyOf(BASE_TOOLS);

        if ("fresh_data".equals(intent) || fold.contains("meteo")) {
            t.add(ToolTag.WEATHER);
        }
        // Actualité → Tavily ; faits stables → Wikipedia / Wikidata
        if (IntentDetector.needsFreshData(fold)
                || "fresh_data".equals(intent)
                || fold.contains("match") || fold.contains("f1")) {
            t.add(ToolTag.SEARCH);
        } else if (IntentDetector.looksLikeEncyclopedic(fold)) {
            t.add(ToolTag.WIKIPEDIA);
            t.add(ToolTag.WIKIDATA);
        }
        if ("fresh_data".equals(intent) || fold.contains("actualite") || fold.contains("news")) {
            t.add(ToolTag.NEWS);
        }
        if ("music".equals(intent) || fold.contains("spotify")) {
            t.add(ToolTag.SPOTIFY);
        }
        if (fold.contains("youtube") || fold.contains("video")) {
            t.add(ToolTag.YOUTUBE);
        }
        if (fold.contains("notif") || fold.contains("message") || fold.contains("recu")) {
            t.add(ToolTag.NOTIFICATIONS);
        }
        if (IntentDetector.looksLikeAgenda(fold)) {
            t.add(ToolTag.AGENDA);
            t.add(ToolTag.CALENDAR);
        }
        if (fold.contains("alarme") || fold.contains("reveil") || fold.contains("minuteur")) {
            // Agenda prioritaire si réunion/rdv dans la même phrase
            if (!IntentDetector.looksLikeAgenda(fold)) {
                t.add(ToolTag.ALARM);
                t.add(ToolTag.TIMER);
            } else if (fold.contains("alarme") || fold.contains("reveil")) {
                t.add(ToolTag.ALARM);
            }
            if (fold.contains("minuteur")) t.add(ToolTag.TIMER);
        }
        if (fold.contains("wifi") || fold.contains("bluetooth") || fold.contains("connexion")) {
            t.add(ToolTag.CONNECTIVITY);
        }
        if (fold.contains("lampe") || fold.contains("torche") || fold.contains("flash")) {
            t.add(ToolTag.FLASHLIGHT);
        }
        if (fold.contains("itineraire") || fold.contains("maps") || fold.contains("waze")
                || fold.contains("aller a") || fold.contains("comment aller")) {
            t.add(ToolTag.NAVIGATION);
        }
        if (fold.contains("appelle") || fold.contains("telephone") || fold.contains("contact")) {
            t.add(ToolTag.CALL);
        }
        if (fold.contains("sms") || fold.contains("envoie un message")) {
            t.add(ToolTag.SMS);
        }
        if (fold.contains("mail") || fold.contains("email") || fold.contains("e-mail")
                || fold.contains("courriel")) {
            t.add(ToolTag.EMAIL);
        }
        if (fold.contains("partage") || fold.contains("share")) {
            t.add(ToolTag.SHARE);
        }
        if (fold.contains("volume") || fold.contains("silence") || fold.contains("muet")
                || fold.contains("son plus") || fold.contains("son moins")
                || fold.contains("monte le son") || fold.contains("baisse le son")) {
            t.add(ToolTag.VOLUME);
        }
        if (fold.contains("mode avion") || fold.contains("hotspot") || fold.contains("partage de connexion")
                || fold.contains("luminosite") || fold.contains("luminosité")
                || fold.contains("reglage son") || fold.contains("reglages son")
                || (fold.contains("panneau") && (fold.contains("son") || fold.contains("affich")))) {
            t.add(ToolTag.SETTINGS);
        }
        if (fold.contains("presse papier") || fold.contains("presse-papiers")
                || fold.contains("presse papiers") || fold.contains("clipboard")
                || fold.contains("copier ") || fold.contains("colle ")
                || fold.contains("dans le presse")) {
            t.add(ToolTag.CLIPBOARD);
        }
        if (fold.contains("contact") || fold.contains("repertoire")
                || fold.contains("annuaire")) {
            t.add(ToolTag.CONTACTS);
        }
        if (IntentDetector.looksLikeFiles(fold)) {
            t.add(ToolTag.FILES);
        }
        if (IntentDetector.looksLikeCalc(fold)) {
            t.add(ToolTag.CALCULATOR);
        }
        if (fold.contains("nasa") || fold.contains("astronomie") || fold.contains("photo du jour")) {
            t.add(ToolTag.NASA);
        }
        if (fold.contains("comme d habitude") || fold.contains("routine")) {
            t.add(ToolTag.COMPOSITE);
        }
        if (IntentDetector.looksLikeDiag(fold) || "diag".equals(intent)) {
            t.add(ToolTag.DIAG);
        }
        if (IntentDetector.looksLikeBrief(fold) || "brief".equals(intent)) {
            t.add(ToolTag.BRIEF);
        }
        if (IntentDetector.looksLikeOrion(fold) || "orion".equals(intent)) {
            t.add(ToolTag.ORION_MANAGER);
        }
        if (IntentDetector.looksLikeOrionCode(fold) || "orion_code".equals(intent)
                || IntentDetector.looksLikeOrionFiles(fold)
                || IntentDetector.looksLikeOrionProject(fold)) {
            t.add(ToolTag.ORION_CODE);
            t.add(ToolTag.ORION_MANAGER);
        }
        if (IntentDetector.looksLikeGitCommit(fold) || IntentDetector.looksLikeOrionFiles(fold)
                || IntentDetector.looksLikeOrionProject(fold)) {
            t.add(ToolTag.GIT_COMMIT);
            t.add(ToolTag.ORION_CODE);
        }

        // Intention ambiguë ou demande outil non classée → tout envoyer (mieux qu'un outil manquant).
        if (t.equals(BASE_TOOLS) && ("general".equals(intent)
                || IntentDetector.looksLikeTool(fold))) {
            t.addAll(EnumSet.allOf(ToolTag.class));
        }
        return t;
    }

    private static String detectIntent(String fold, EntityResolver.Resolution entities) {
        return IntentDetector.detectIntent(fold, entities);
    }

    private static EnumSet<ProfileSection> sectionsFor(String intent,
            EntityResolver.Resolution entities) {
        switch (intent) {
            case "music":
                return EnumSet.of(ProfileSection.ESSENTIAL);
            case "project":
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.PROJECTS);
            case "person":
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.INTERACTION);
            case "memory":
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.NOTES);
            case "productivity":
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.INTERACTION);
            case "diag":
                return EnumSet.of(ProfileSection.ESSENTIAL);
            case "creative":
            case "story":
            case "philosophical":
            case "technical":
            case "explanation":
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.INTERACTION);
            case "orion":
            case "orion_code":
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.PROJECTS);
            case "place":
            case "device":
            case "fresh_data":
                return EnumSet.of(ProfileSection.ESSENTIAL);
            default:
                return EnumSet.of(ProfileSection.ESSENTIAL, ProfileSection.INTERACTION);
        }
    }

    private static int budgetFor(String intent, EntityResolver.Resolution entities) {
        int budget = 250;
        if ("general".equals(intent) || "project".equals(intent)) budget = 350;
        if ("creative".equals(intent) || "story".equals(intent)
                || "philosophical".equals(intent) || "technical".equals(intent)
                || "explanation".equals(intent)) {
            budget = 400;
        }
        if ("music".equals(intent) || "fresh_data".equals(intent) || "diag".equals(intent)
                || "orion".equals(intent) || "orion_code".equals(intent)
                || "brief".equals(intent) || "productivity".equals(intent)) {
            budget = 200;
        }
        budget += Math.min(120, entities.forInjection(2).size() * 60);
        return budget;
    }
}
