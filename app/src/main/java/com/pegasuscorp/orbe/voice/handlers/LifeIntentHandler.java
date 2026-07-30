package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.voice.SituationRoutineStore;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LifeIntentHandler implements IntentHandler {

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (looksLikeAddLifePattern(fold)) {
            String json = lifePatternAddJson(text);
            if (json != null) {
                return VoiceIntentSupport.routed(context, text, json, "rythme de vie", 0.95);
            }
        }

        if (looksLikeAddMorningRoutine(fold, text)) {
            String json = briefAddJson(text);
            if (json != null) {
                return VoiceIntentSupport.routed(context, text, json, "routine matin", 0.95);
            }
        }

        if (looksLikeSituationRoutine(fold)) {
            RoutedIntent situation = routeSituationRoutine(context, text, fold);
            if (situation != null) return situation;
        }

        if (looksLikeMorningBrief(fold)) {
            String json = briefJson();
            if (json != null) {
                return VoiceIntentSupport.routed(context, text, json, "brief du matin", 0.95);
            }
        }

        if (looksLikeBriefDetailFollowUp(fold)) {
            String json = briefDetailJson();
            if (json != null) {
                return VoiceIntentSupport.routed(context, text, json, "brief detail", 0.94);
            }
        }

        if (looksLikeAgendaQuery(fold)) {
            RoutedIntent query = routeAgendaQuery(context, text, fold);
            if (query != null) return query;
        }

        if (looksLikeAgenda(fold)) {
            RoutedIntent agenda = routeAgenda(context, text, fold);
            if (agenda != null) return agenda;
        }

        if (looksLikeMemoryList(fold)) {
            try {
                return VoiceIntentSupport.routed(context, text,
                        VoiceIntentSupport.toolJson("memory", new JSONObject().put("action", "list")),
                        "mémoire", 0.88);
            } catch (Exception ignored) {}
        }

        return null;
    }

    static boolean looksLikeMorningBrief(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return f.contains("brief du matin") || f.contains("brief matin")
                || f.contains("resume ma journee") || f.contains("resumer ma journee")
                || f.contains("resume la journee")
                || f.contains("qu est ce que j ai aujourd")
                || f.contains("quoi aujourd hui")
                || (f.contains("aujourd hui") && (f.contains("programme")
                || f.contains("qu ai je")));
    }

    static boolean looksLikeAddLifePattern(String fold) {
        if (fold == null) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        boolean hasTime = f.matches(".*\\d{1,2}\\s*[h:]\\s*\\d{0,2}.*");
        if (!hasTime) return false;
        return (f.contains("rythme") || f.contains("ma vie") || f.contains("habitude"))
                && (f.contains("ajoute") || f.contains("note") || f.contains("enregistre"));
    }

    static String lifePatternAddJson(String utterance) {
        try {
            JSONObject p = new JSONObject();
            p.put("action", "add");
            p.put("utterance", utterance);
            return VoiceIntentSupport.toolJson("life_pattern", p);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeAddMorningRoutine(String fold, String text) {
        if (fold == null) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        if (!(f.contains("ajoute") && f.contains("routine"))) return false;
        return f.contains("matin") || f.contains("ma routine")
                || (text != null && (text.contains(":") || text.contains("：")));
    }

    static String briefJson() {
        try {
            JSONObject p = new JSONObject();
            p.put("action", "brief");
            return VoiceIntentSupport.toolJson("brief", p);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeBriefDetailFollowUp(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        return com.pegasuscorp.orbe.tools.knowledge.BriefTool.looksLikeBriefDetailFollowUp(f);
    }

    static String briefDetailJson() {
        try {
            JSONObject p = new JSONObject();
            p.put("action", "detail");
            return VoiceIntentSupport.toolJson("brief", p);
        } catch (Exception e) {
            return null;
        }
    }

    static String briefAddJson(String utterance) {
        try {
            JSONObject p = new JSONObject();
            p.put("action", "add");
            p.put("utterance", utterance);
            return VoiceIntentSupport.toolJson("brief", p);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeSituationRoutine(String fold) {
        String f = fold.replace('\'', ' ');
        // « ajoute à ma routine… » = BriefTool add, pas la routine situationnelle
        if (f.contains("ajoute") || f.contains("ajout ")) return false;
        return f.contains("comme d habitude") || f.contains("comme dab")
                || f.contains("ma routine") || f.contains("on fait comme")
                || f.contains("routine du matin") || f.contains("routine du soir")
                || f.contains("comme tous les jours") || f.contains("comme chaque jour")
                || f.contains("comme habituellement");
    }

    static RoutedIntent routeSituationRoutine(Context context, String text, String fold) {
        SituationRoutineStore.Slot explicit = SituationRoutineStore.parseExplicitSlot(fold);
        SituationRoutineStore.Slot slot = explicit != null
                ? explicit : SituationRoutineStore.currentSlot();
        String json = SituationRoutineStore.resolveRoutine(context, slot);
        if (json == null || json.isEmpty()) {
            return RoutedIntent.withHint(text, "routine — configure ta routine dans Outils");
        }
        return VoiceIntentSupport.routed(context, text, json, SituationRoutineStore.labelForSlot(slot), 0.9);
    }

    static boolean looksLikeAgenda(String fold) {
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeAgenda(fold);
    }

    static boolean looksLikeAgendaQuery(String fold) {
        return com.pegasuscorp.orbe.memory.IntentDetector.looksLikeAgendaQuery(fold);
    }

    static RoutedIntent routeAgendaQuery(Context context, String text, String fold) {
        try {
            String action = "today";
            if (fold.contains("demain")) action = "tomorrow";
            else if (fold.contains("semaine") || fold.contains("cette semaine")) action = "week";
            else if (fold.contains("liste") || fold.contains("planning")) action = "list";
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("agenda", new JSONObject().put("action", action)),
                    "agenda", 0.93);
        } catch (Exception e) {
            return null;
        }
    }

    static RoutedIntent routeAgenda(Context context, String text, String fold) {
        try {
            String title = text.trim();
            // Retirer les verbes d'action pour le titre
            title = title.replaceAll("(?i)^(ajoute|mets|met|crée|cree|programme)\\s+"
                    + "(au |dans l'|dans le |un |une )?(agenda|calendrier)\\s*", "");
            title = title.replaceAll("(?i)^(ajoute|mets|met)\\s+(un |une )?", "");
            // Extraire start approximatif depuis la fin
            String start = "";
            Matcher dm = Pattern.compile(
                    "(?i)(demain|aujourd'?hui|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche"
                            + "|dans\\s+\\d+\\s*(?:h|heure|heures|min|minutes?)"
                            + "|\\d{4}-\\d{2}-\\d{2})"
                            + "(?:\\s+(?:à\\s*)?\\d{1,2}\\s*[h:]\\s*\\d{0,2})?")
                    .matcher(text);
            if (dm.find()) {
                start = dm.group().trim();
                title = (text.substring(0, dm.start()) + text.substring(dm.end())).trim();
            }
            title = title.replaceAll("(?i)\\s+(à|a)\\s*\\d{1,2}\\s*h.*$", "").trim();
            if (title.isEmpty()) title = "Événement";
            // Capitaliser
            title = title.substring(0, 1).toUpperCase(Locale.FRENCH) + title.substring(1);
            if (start.isEmpty()) start = "demain 9:00";
            JSONObject p = new JSONObject()
                    .put("title", title)
                    .put("start", start)
                    .put("reminder", 15);
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("agenda", p), "agenda", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeMemoryList(String fold) {
        return fold.contains("qu est ce que tu retiens")
                || fold.contains("que retiens tu")
                || fold.contains("ma memoire") || fold.contains("ma mémoire")
                || fold.contains("mes souvenirs");
    }
}
