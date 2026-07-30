package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.memory.IntentDetector;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnowledgeIntentHandler implements IntentHandler {

    private static final Pattern WEATHER_CITY = Pattern.compile(
            "(?i)(?:meteo|météo|temps|temperature|température)\\s+(?:à|a|pour|de|d')\\s*([\\p{L}\\-' ]{2,})");
    private static final Pattern NEWS_TOPIC = Pattern.compile(
            "(?i)(?:actus?|actualites?|nouvelles|infos?)\\s+(?:sur|de|du|des)?\\s*([\\p{L}\\d'\\- ]{2,})");

    private static final Pattern TEAM_AFTER = Pattern.compile(
            "(?i)(?:match|score|resultat|résultat|club|equipe|équipe)\\s+(?:du|de|d'|des)?\\s*([\\p{L}\\d'\\- ]{2,})");
    private static final Pattern TEAM_BEFORE = Pattern.compile(
            "(?i)(?:du|de|d')\\s*([\\p{L}\\d'\\- ]{2,})\\s+(?:match|score|resultat|résultat)");

    private static final String[] KNOWN_TEAMS = {
            "psg", "paris saint germain", "marseille", "om", "lyon", "ol",
            "monaco", "lille", "lens", "rennes", "nice", "france",
            "ferrari", "red bull", "mclaren", "mercedes", "alpine",
            "formule 1", "f1"
    };

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (looksLikeWeather(fold, text)) {
            int days = extractWeatherDays(fold);
            String city = extractWeatherCity(text);
            String json = weatherJson(days, city);
            if (json != null) {
                double conf = scoreWeather(fold, text, days);
                return VoiceIntentSupport.routed(context, text, json, "météo", conf);
            }
        }

        // Original analyze() order: calc / flashlight / navigation before sports+news+search.
        // Defer so SystemIntentHandler (after Media) can claim them.
        if (com.pegasuscorp.orbe.tools.device.MathCalcTrigger.matches(text)
                || SystemIntentHandler.looksLikeFlashlight(fold)
                || SystemIntentHandler.looksLikeNavigation(fold)) {
            return null;
        }

        if (looksLikeSports(fold)) {
            if (looksLikeF1(fold)) {
                String json = f1ToolJson(fold);
                if (json != null) {
                    double conf = looksLikeF1Debrief(fold) ? 0.92 : 0.88;
                    return VoiceIntentSupport.routed(context, text, json, "f1", conf);
                }
            }
            String team = extractTeam(text, fold);
            if (team != null && hasSportsIntent(fold)) {
                String type = fold.contains("prochain") || fold.contains("prochaine")
                        || fold.contains("suivant") ? "next" : "last";
                String json = searchSportsJson(team, type, text);
                if (json != null) {
                    double conf = scoreSports(fold, team);
                    return VoiceIntentSupport.routed(context, text, json, "recherche sport", conf);
                }
            }
            if (hasSportsIntent(fold) || hasKnownTeam(fold) != null) {
                return RoutedIntent.withHint(text, "sport — précise l'équipe ou le club");
            }
        }

        if (looksLikeNews(fold)) {
            if (context != null && !ApiKeyStore.hasNewsApiKey(context)) {
                return RoutedIntent.withHint(text, "actualités — ajoute ta clé NewsAPI dans les réglages");
            }
            String topic = extractNewsTopic(text);
            String json = newsJson(topic);
            return VoiceIntentSupport.routed(context, text, json, "actualités", scoreNews(fold));
        }

        if (looksLikeSearch(fold)) {
            String query = extractSearchQuery(text);
            if (query != null && query.length() >= 3) {
                boolean fresh = IntentDetector.needsFreshData(fold);
                // Faits encyclopédiques même avec « cherche » → wiki (économie Tavily)
                if (!fresh && (IntentDetector.looksLikeEncyclopedic(fold)
                        || IntentDetector.looksLikeWikidata(fold)
                        || looksLikeEncyclopedicQuery(query))) {
                    try {
                        String tool = IntentDetector.looksLikeWikidata(fold)
                                ? "wikidata" : "wikipedia";
                        JSONObject p = new JSONObject().put("query", query);
                        return VoiceIntentSupport.routed(context, text,
                                VoiceIntentSupport.toolJson(tool, p), tool, 0.85);
                    } catch (Exception ignored) {}
                }
                if (context != null && !ApiKeyStore.hasTavilyKey(context)) {
                    try {
                        JSONObject p = new JSONObject().put("query", query);
                        return VoiceIntentSupport.routed(context, text,
                                VoiceIntentSupport.toolJson("web_search", p),
                                "recherche web", 0.72);
                    } catch (Exception ignored) {}
                    return RoutedIntent.withHint(text,
                            "recherche — ajoute ta clé Tavily dans les réglages");
                }
                try {
                    JSONObject p = new JSONObject().put("query", query);
                    double conf = scoreSearch(query);
                    return VoiceIntentSupport.routed(context, text,
                            VoiceIntentSupport.toolJson("search", p), "recherche", conf);
                } catch (Exception ignored) {}
            }
            return RoutedIntent.withHint(text, "recherche web — commence par « cherche » ou « trouve »");
        }

        if (looksLikeInfoQuestion(fold, text)) {
            String query = extractInfoQuery(text);
            if (query != null && query.length() >= 4) {
                boolean fresh = IntentDetector.needsFreshData(fold);
                if (!fresh) {
                    try {
                        String tool = IntentDetector.looksLikeWikidata(fold)
                                ? "wikidata" : "wikipedia";
                        JSONObject p = new JSONObject()
                                .put("query", query)
                                .put("question", text.trim());
                        return VoiceIntentSupport.routed(context, text,
                                VoiceIntentSupport.toolJson(tool, p), tool,
                                scoreInfoQuestion(fold));
                    } catch (Exception ignored) {}
                } else if (context != null && ApiKeyStore.hasTavilyKey(context)) {
                    try {
                        JSONObject p = new JSONObject()
                                .put("query", query)
                                .put("question", text.trim());
                        return VoiceIntentSupport.routed(context, text,
                                VoiceIntentSupport.toolJson("search", p),
                                "recherche web", scoreInfoQuestion(fold));
                    } catch (Exception ignored) {}
                }
            }
        }

        return null;
    }

    static double scoreWeather(String fold, String text, int days) {
        double score = 0.55;
        if (fold.contains("quelle meteo") || fold.contains("quel temps")) score += 0.25;
        if (fold.contains("demain") || fold.contains("aujourd") || fold.contains("semaine")) score += 0.1;
        if (text.contains("?")) score += 0.08;
        if (fold.contains("parapluie") || fold.contains("va pleuvoir")) score += 0.12;
        if (days > 1) score += 0.05;
        return Math.min(0.98, score);
    }

    static double scoreSports(String fold, String team) {
        double score = 0.6;
        if (team != null && team.length() > 2) score += 0.15;
        if (fold.contains("match") || fold.contains("score") || fold.contains("resultat")) score += 0.12;
        if (fold.contains("dernier") || fold.contains("prochain")) score += 0.08;
        if (fold.contains("psg") || fold.contains("om") || fold.contains("ferrari")) score += 0.05;
        return Math.min(0.95, score);
    }

    static double scoreNews(String fold) {
        if (fold.contains("quoi de neuf")) return 0.92;
        if (fold.contains("les actus") || fold.contains("nouvelles du jour")) return 0.88;
        return 0.78;
    }

    static double scoreSearch(String query) {
        if (query.length() >= 12) return 0.88;
        if (query.length() >= 6) return 0.78;
        return 0.65;
    }

    /** Heuristique légère sur la requête seule (sans « c'est quoi »). */
    static boolean looksLikeEncyclopedicQuery(String query) {
        if (query == null) return false;
        String f = SpeechInputNormalizer.fold(query).replace('\'', ' ');
        return f.contains("definition")
                || f.contains("coefficient")
                || f.contains("theorie")
                || f.contains("histoire de")
                || f.contains("inventeur");
    }

    static double scoreInfoQuestion(String fold) {
        if (fold.contains("combien") || fold.contains("prix")) return 0.82;
        if (fold.contains("c est quoi") || fold.contains("qu est ce")) return 0.8;
        return 0.74;
    }

    static boolean looksLikeInfoQuestion(String fold, String text) {
        if (looksLikeWeather(fold, text) || looksLikeSports(fold) || looksLikeNews(fold)) {
            return false;
        }
        return fold.contains("combien coute")
                || fold.contains("combien ca coute")
                || fold.contains("quel est le prix")
                || fold.contains("quel est le cout")
                || fold.contains("c est quoi")
                || fold.contains("qu est ce que")
                || fold.contains("quelle est la")
                || fold.contains("quel est le")
                || fold.contains("qui est ")
                || fold.contains("tu sais ")
                || fold.contains("info sur")
                || fold.contains("informations sur")
                || fold.contains("renseigne moi")
                || fold.contains("dis moi combien")
                || (text.contains("?") && (fold.contains("actuel")
                || fold.contains("aujourd")
                || fold.contains("maintenant")
                || fold.contains("dernier")
                || fold.contains("derniere")));
    }

    static String extractInfoQuery(String text) {
        String t = text.trim().replaceAll("[?.!]+$", "").trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{
                "dis moi ", "est ce que tu sais ", "tu sais ",
                "peux tu me dire ", "peux-tu me dire ",
                "renseigne moi sur ", "info sur ", "informations sur ",
                "c est quoi ", "qu est ce que "}) {
            if (lower.startsWith(prefix)) {
                t = t.substring(prefix.length()).trim();
                lower = t.toLowerCase(Locale.ROOT);
            }
        }
        return t.length() >= 4 ? t : null;
    }

    static boolean looksLikeWeather(String fold, String text) {
        boolean hasWeatherWord = fold.contains("meteo")
                || fold.contains("temperature")
                || fold.contains("il pleut")
                || fold.contains("va pleuvoir")
                || fold.contains("temps qu il fait")
                || fold.contains("quel temps")
                || fold.contains("quelle meteo")
                || fold.contains("quelle temperature")
                || fold.contains("parapluie")
                || fold.contains("manteau demain")
                || fold.contains("faut il prendre")
                || fold.contains("besoin d un parapluie");
        if (!hasWeatherWord) return false;

        return fold.contains("demain")
                || fold.contains("aujourd")
                || fold.contains("apres demain")
                || fold.contains("semaine")
                || fold.contains("7 jour")
                || fold.contains("quel temps")
                || fold.contains("quelle meteo")
                || fold.contains("quelle temperature")
                || fold.contains("est ce qu il")
                || fold.contains("il va pleuvoir")
                || fold.contains("il fait")
                || text.contains("?");
    }

    static int extractWeatherDays(String fold) {
        if (fold.contains("semaine") || fold.contains("7 jour")) return 7;
        if (fold.contains("apres demain") || fold.contains("apres-demain")) return 3;
        if (fold.contains("demain")) return 2;
        return 1;
    }

    static boolean looksLikeSports(String fold) {
        return hasSportsIntent(fold) || hasKnownTeam(fold) != null;
    }

    static boolean hasSportsIntent(String fold) {
        return fold.contains("match")
                || fold.contains("score")
                || fold.contains("resultat")
                || fold.contains("foot")
                || fold.contains("ligue 1")
                || fold.contains("grand prix")
                || fold.contains("formule 1")
                || fold.contains("dernier match")
                || fold.contains("prochain match")
                || fold.contains("dernier score")
                || fold.contains("prochain gp")
                || fold.contains("debrief")
                || fold.contains("podium");
    }

    static boolean looksLikeF1(String fold) {
        if (fold == null) return false;
        return fold.contains("formule 1")
                || fold.contains("formule1")
                || fold.contains("formula 1")
                || fold.contains("grand prix")
                || fold.contains("prochain gp")
                || fold.contains("dernier gp")
                || fold.equals("f1")
                || fold.startsWith("f1 ")
                || fold.contains(" f1 ")
                || fold.endsWith(" f1")
                || fold.contains("safety car")
                || fold.contains("verstappen")
                || fold.contains("leclerc")
                || fold.contains("norris")
                || fold.contains("piastri")
                || fold.contains("hamilton")
                || fold.contains("mclaren")
                || fold.contains("red bull")
                || fold.contains("debrief") && (fold.contains("gp") || fold.contains("course")
                || fold.contains("ferrari") || fold.contains("mclaren"))
                || (fold.contains("ferrari") && (fold.contains("gp") || fold.contains("course")
                || fold.contains("pilote") || fold.contains("strategie")
                || fold.contains("podium") || fold.contains("pense")))
                || fold.contains("pronostic")
                || fold.contains("chambrage")
                || (fold.contains("souviens") && (fold.contains("ferrari") || fold.contains("mclaren")
                || fold.contains("mercedes") || fold.contains("gp") || fold.contains("course")
                || fold.contains("f1") || fold.contains("verstappen") || fold.contains("leclerc")
                || fold.contains("norris") || fold.contains("hamilton")));
    }

    static boolean looksLikeF1Debrief(String fold) {
        if (!looksLikeF1(fold)) return false;
        return fold.contains("pense")
                || fold.contains("debrief")
                || fold.contains("strategie")
                || fold.contains("opinion")
                || fold.contains("analyse")
                || fold.contains("tu en as")
                || fold.contains("apres le")
                || fold.contains("comment etait")
                || fold.contains("comment c etait")
                || fold.contains("penalit")
                || fold.contains("pouvait gagner")
                || fold.contains("merite");
    }

    static String f1ToolJson(String fold) {
        try {
            String action = "debrief";
            String mode = "quick";
            if (fold.contains("refresh") || fold.contains("mets a jour")
                    || fold.contains("actualise") || fold.contains("rafraichi")) {
                action = "refresh";
            } else if (fold.contains("pronostic") || fold.contains("je mise")
                    || fold.contains("je parie") || fold.contains("mon predict")
                    || fold.contains("prediction")) {
                action = "predict";
            } else if (fold.contains("souviens") || fold.contains("reten")
                    || fold.contains("note que") || fold.contains("rappelee que")
                    || fold.contains("rappelle que")) {
                action = "remember";
            } else if (fold.contains("memoire fan") || fold.contains("mes avis")
                    || fold.contains("mes pronostics") || fold.contains("chambrage")) {
                action = "memory";
            } else if (fold.contains("live") || fold.contains("en direct")
                    || fold.contains("safety car") || fold.contains("classement live")
                    || (fold.contains("y a quoi") && fold.contains("course"))) {
                action = "live";
            } else if (fold.contains("prochain") || fold.contains("quand")) {
                // Calendrier prochain GP : status/fiche du dernier + note — refresh suffit
                action = "status";
            } else if (looksLikeF1Debrief(fold)
                    || fold.contains("resultat") || fold.contains("podium")
                    || fold.contains("gagn") || fold.contains("course")) {
                action = "debrief";
                if (fold.contains("approfond") || fold.contains("detail")
                        || fold.contains("long") || fold.contains("complet")) {
                    mode = "deep";
                }
            } else {
                action = "status";
            }
            JSONObject p = new JSONObject().put("action", action);
            if ("debrief".equals(action)) p.put("mode", mode);
            if ("remember".equals(action) || "predict".equals(action)) {
                String extracted = extractFanText(fold);
                if (extracted != null && !extracted.isEmpty()) {
                    p.put("text", extracted);
                }
            }
            return VoiceIntentSupport.toolJson("f1", p);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extrait le texte utile après « souviens-toi que… » / « mon pronostic : … ». */
    static String extractFanText(String fold) {
        if (fold == null) return "";
        String s = fold.trim();
        String[] prefixes = {
                "souviens toi que ", "souviens-toi que ", "souviens toi ",
                "reten que ", "retiens que ", "note que ",
                "rappelle que ", "rappelee que ",
                "mon pronostic c est que ", "mon pronostic est que ",
                "mon pronostic : ", "mon pronostic ",
                "je mise sur ", "je parie sur ", "je predit que ", "je predits que ",
                "pronostic : ", "pronostic "
        };
        for (String p : prefixes) {
            int i = s.indexOf(p);
            if (i >= 0) {
                String out = s.substring(i + p.length()).trim();
                if (!out.isEmpty()) return out;
            }
        }
        return s;
    }

    static String hasKnownTeam(String fold) {
        for (String k : KNOWN_TEAMS) {
            if ("om".equals(k)) {
                if (fold.contains(" om") || fold.endsWith("om") || fold.startsWith("om ")) {
                    return "Marseille";
                }
                continue;
            }
            if ("ol".equals(k)) {
                if (fold.contains(" ol") || fold.endsWith("ol") || fold.startsWith("ol ")) {
                    return "Lyon";
                }
                continue;
            }
            if (fold.contains(k)) {
                if ("f1".equals(k)) return "formule 1";
                return VoiceIntentSupport.capitalize(k);
            }
        }
        return null;
    }

    static boolean looksLikeNews(String fold) {
        return fold.contains("les actus")
                || fold.contains("les actualite")
                || fold.contains("quoi de neuf")
                || fold.contains("nouvelles du jour")
                || fold.contains("actualite du jour")
                || fold.contains("info du jour")
                || fold.startsWith("actus ")
                || fold.startsWith("actualite ")
                || fold.equals("actus")
                || fold.equals("actualites");
    }

    static boolean looksLikeSearch(String fold) {
        if (fold.contains("ouvre google")
                || fold.contains("google pour")
                || fold.contains("cherche sur google")) {
            return false;
        }
        return fold.startsWith("cherche ")
                || fold.startsWith("recherche ")
                || fold.startsWith("trouve ")
                || fold.startsWith("tu peux chercher ")
                || fold.startsWith("peux tu chercher ")
                || fold.startsWith("peux-tu chercher ");
    }

    public static String extractTeam(String text, String fold) {
        String known = hasKnownTeam(fold);
        if (known != null) return known;

        Matcher m = TEAM_AFTER.matcher(text);
        if (m.find()) return m.group(1).trim();
        m = TEAM_BEFORE.matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    static String extractSearchQuery(String text) {
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{
                "cherche ", "recherche ", "trouve ", "tu peux chercher ",
                "peux-tu chercher ", "peux tu chercher "}) {
            if (lower.startsWith(prefix)) {
                String q = t.substring(prefix.length()).trim();
                q = q.replaceAll("[?.!]+$", "").trim();
                return q.isEmpty() ? null : q;
            }
        }
        return null;
    }

    static String extractWeatherCity(String text) {
        Matcher m = WEATHER_CITY.matcher(text);
        if (m.find()) {
            String city = m.group(1).trim().replaceAll("[?.!]+$", "");
            if (city.length() >= 2 && !SpeechInputNormalizer.fold(city).contains("demain")
                    && !SpeechInputNormalizer.fold(city).contains("aujourd")) {
                return city;
            }
        }
        return null;
    }

    static String extractNewsTopic(String text) {
        Matcher m = NEWS_TOPIC.matcher(text);
        if (m.find()) {
            String topic = m.group(1).trim().replaceAll("[?.!]+$", "");
            if (topic.length() >= 2) return topic;
        }
        return null;
    }

    static String weatherJson(int days, String city) {
        try {
            JSONObject p = new JSONObject().put("days", days);
            if (city != null && !city.isEmpty()) p.put("city", city);
            return VoiceIntentSupport.toolJson("weather", p);
        } catch (Exception e) {
            return null;
        }
    }

    static String newsJson(String topic) {
        try {
            JSONObject p = new JSONObject();
            if (topic != null && !topic.isEmpty()) p.put("query", topic);
            return VoiceIntentSupport.toolJson("news", p);
        } catch (Exception e) {
            return null;
        }
    }

    static String weatherJson(int days) {
        return weatherJson(days, null);
    }

    /** Recherche web Tavily pour résultats sportifs (remplace l'ancien outil sports). */
    public static String searchSportsJson(String team, String type, String userText) {
        try {
            String query = team + ("next".equals(type)
                    ? " prochain match calendrier"
                    : " dernier match résultat score");
            JSONObject p = new JSONObject()
                    .put("query", query.trim())
                    .put("question", userText != null && !userText.isEmpty() ? userText : query.trim());
            return VoiceIntentSupport.toolJson("search", p);
        } catch (Exception e) {
            return null;
        }
    }
}
