package com.pegasuscorp.orbe.llm;

import com.pegasuscorp.orbe.tools.OpenAiToolSchemaBuilder;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commandes simples traitées par le modèle local (Qwen3.5-2B via {@link LocalLlmServer}).
 *
 * <p>Recette validée sur le Nothing Phone (1), 27/28 au banc « pegase_hard » :
 * <ol>
 *   <li>tri des outils par mots-clés (1–2 outils envoyés au lieu de tous) ;</li>
 *   <li>prompt court + 2 exemples tutoyés, réflexion désactivée, température 0,3 ;</li>
 *   <li>garde-fous code : jours de réveil seulement si répétition demandée, heure / durée /
 *       période reprises dans la phrase si le modèle les oublie ;</li>
 *   <li>la confirmation affichée est le texte de l'outil (pas de 2e passage du modèle).</li>
 * </ol>
 * Tout échec (pas de route, pas d'appel d'outil exploitable, délai) → {@code null} : l'appelant
 * repasse par le chemin cloud habituel.
 */
public final class LocalCommandRouter {

    /** Résultat : soit des appels d'outils, soit une question de clarification. */
    public static final class Decision {
        public final List<String> toolIds = new ArrayList<>();
        public final List<JSONObject> params = new ArrayList<>();
        public String clarification;
        public long latencyMs;
    }

    private static final String[][] ROUTES = {
            {"alarm", "reveil|reveille|alarme"},
            {"timer", "minuteur|chrono|compte a rebours"},
            {"agenda", "rendez[- ]?vous|\\brdv\\b|agenda|calendrier|prevu|reunion|planning|j'ai quoi|qu'est-ce que j'ai|qu'est ce que j'ai"},
            {"weather", "\\bmeteo\\b|quel temps|pleuvoir|\\bpluie\\b|manteau|parapluie|\\bneige"},
            {"spotify", "musique|chanson|playlist|ecouter|\\bmets du\\b|\\bmets de la\\b|\\bjoue\\b|spotify"},
            {"memory", "retiens|souviens-toi|n'oublie pas|memorise|rappelle-toi"},
            {"notepad", "\\bnote\\b|\\bnoter\\b|\\bliste\\b|courses|\\btache"},
            {"sms", "\\bmessage\\b|\\bsms\\b|texto|ecris a"},
            {"call", "\\bappelle\\b|telephone a|\\bappeler\\b"},
    };
    private static final Pattern REPEAT = Pattern.compile(
            "tous les|chaque|du lundi|en semaine|le week-end|les (lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)s?\\b");
    private static final Pattern HOUR = Pattern.compile("\\b(\\d{1,2})\\s*h\\s*(\\d{2})?");
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(h|heures?|min|minutes?|s|sec|secondes?)\\b");
    private static final Pattern PERIOD = Pattern.compile(
            "demain|aujourd'hui|ce soir|cette semaine");

    private LocalCommandRouter() {}

    // ======================================================================
    // Analyseur instantané (sans modèle) pour les formulations évidentes.
    // Ne renvoie une décision que s'il est sûr ; sinon null → modèle local.
    // ======================================================================

    private static final String[][] NUMBER_WORDS = {
            {"une", "1"}, {"un", "1"}, {"deux", "2"}, {"trois", "3"}, {"quatre", "4"},
            {"cinq", "5"}, {"six", "6"}, {"sept", "7"}, {"huit", "8"}, {"neuf", "9"},
            {"dix", "10"}, {"onze", "11"}, {"douze", "12"}, {"quinze", "15"},
            {"vingt", "20"}, {"trente", "30"}, {"quarante-cinq", "45"}, {"quarante", "40"},
            {"cinquante", "50"}, {"soixante", "60"},
    };
    private static final Pattern Q_TIMER = Pattern.compile(
            "^(?:(?:mets?|lance|demarre|programme|fais)(?:-moi)?\\s+)?(?:un|le|1)?\\s*(?:minuteur|chrono|timer|compte a rebours)"
                    + "\\s*(?:de|pour|dans|sur)?\\s*(\\d+)\\s*(h|heures?|min|minutes?|s|sec|secondes?)"
                    + "(?:\\s*(?:et\\s+)?(\\d+)\\s*(min|minutes?|s|sec|secondes?)?)?"
                    + "(?:\\s+pour\\s+(?:les\\s+|le\\s+|la\\s+|l')?([a-z' -]{2,30}))?\\s*[.!]?$");
    private static final Pattern Q_ALARM = Pattern.compile(
            "^(?:reveille[- ]moi|(?:mets?|programme|regle)(?:-moi)?\\s+(?:(?:un|1)\\s+reveil|(?:une|1)\\s+alarme)|reveil|alarme)"
                    + "\\s*(?:pour\\s+)?(demain\\s+(?:matin\\s+)?)?(?:a\\s+)?(\\d{1,2})\\s*h\\s*(\\d{2})?(.*)$");
    private static final Pattern Q_NOTE = Pattern.compile(
            "^(?:note|noter|prends note|ecris)\\s+(?:que\\s+|qu')?(.{3,})$");
    private static final Pattern Q_LIST_ADD = Pattern.compile(
            "^ajoute\\s+(.{2,}?)\\s+(?:a|dans|sur)\\s+(?:ma|la|mes)\\s+(?:liste|notes?)(?:\\s+de courses)?\\s*[.!]?$");
    private static final Pattern Q_MEMORY = Pattern.compile(
            "^(?:retiens|souviens-toi|memorise)\\s+(?:bien\\s+)?(?:que\\s+|qu')?(.{3,})$");
    private static final Pattern Q_MUSIC_PLAY = Pattern.compile(
            "^(?:mets?|joue|lance)\\s+(?:du|de la|des|un peu de)\\s+(.{2,}?)(?:\\s+sur spotify)?\\s*[.!]?$");
    private static final Pattern Q_AGENDA = Pattern.compile(
            "(prevu|rendez[- ]?vous|\\brdv\\b|agenda|au programme|j'ai quoi)");

    /** Décision immédiate sans modèle, ou null. {@code latencyMs} = 0. */
    public static Decision quickParse(String userText) {
        if (userText == null) return null;
        String orig = userText.trim();
        String fk = foldKeepLength(orig);           // même longueur que orig (extraits de texte)
        String fn = numbersToDigits(fold(orig));    // chiffres pour durées / heures
        try {
            Matcher m = Q_TIMER.matcher(fn);
            if (m.matches()) {
                String dur = m.group(1) + " " + m.group(2)
                        + (m.group(3) != null ? " " + m.group(3) + " " + (m.group(4) != null ? m.group(4) : "min") : "");
                JSONObject a = new JSONObject().put("action", "start").put("duration", dur.trim());
                if (m.group(5) != null) a.put("label", m.group(5).trim());
                return decision("timer", a);
            }
            m = Q_ALARM.matcher(fn);
            if (m.matches()) {
                int h = Integer.parseInt(m.group(2));
                int mi = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
                String rest = m.group(4) != null ? m.group(4) : "";
                JSONObject a = new JSONObject().put("action", "add");
                if (rest.matches(".*\\b(du soir|de l'apres-midi)\\b.*") && h < 12) h += 12;
                if (rest.matches(".*(du lundi au vendredi|en semaine).*")) {
                    a.put("days", new JSONArray(new int[]{1, 2, 3, 4, 5}));
                } else if (rest.matches(".*tous les jours.*")) {
                    a.put("days", new JSONArray(new int[]{1, 2, 3, 4, 5, 6, 7}));
                } else if (rest.matches(".*(le week-?end).*")) {
                    a.put("days", new JSONArray(new int[]{6, 7}));
                }
                String leftover = rest.replaceAll("du lundi au vendredi|en semaine|tous les jours|le week-?end"
                        + "|demain|du matin|du soir|de l'apres-midi|pile|s'il te plait|stp|[.,!]", "").trim();
                if (h > 23 || mi > 59 || !leftover.isEmpty()) return null;
                a.put("hour", h).put("minute", mi);
                return decision("alarm", a);
            }
            if (!fk.endsWith("?")) {
                m = Q_LIST_ADD.matcher(fk);
                if (m.matches()) return decision("notepad", new JSONObject()
                        .put("action", "add").put("text", capitalize(orig.substring(m.start(1), m.end(1)))));
                m = Q_NOTE.matcher(fk);
                if (m.matches() && !fk.startsWith("note mes") && !fk.contains("mes notes")) {
                    return decision("notepad", new JSONObject()
                            .put("action", "add").put("text", capitalize(stripEnd(orig.substring(m.start(1))))));
                }
                m = Q_MEMORY.matcher(fk);
                if (m.matches()) return decision("memory", new JSONObject()
                        .put("action", "add").put("text", capitalize(stripEnd(orig.substring(m.start(1))))));
                if (fk.matches("^(pause|mets? (la musique )?en pause|stoppe? la musique)[.!]?$")) {
                    return decision("spotify", new JSONObject().put("action", "pause"));
                }
                if (fk.matches("^((chanson|musique|morceau|titre) suivante?|suivant)[.!]?$")) {
                    return decision("spotify", new JSONObject().put("action", "next"));
                }
                m = Q_MUSIC_PLAY.matcher(fk);
                if (m.matches() && !fk.matches(".*\\b(liste|notes?|courses|minuteur|reveil|alarme|agenda)\\b.*")) return decision("spotify", new JSONObject()
                        .put("action", "play").put("query", orig.substring(m.start(1), m.end(1))));
            }
            // Lecture d'agenda : question + mot d'agenda + période claire, sans verbe d'ajout
            boolean question = fk.endsWith("?") || fk.startsWith("qu'") || fk.startsWith("j'ai")
                    || fk.startsWith("est-ce");
            if (question && Q_AGENDA.matcher(fk).find()
                    && !fk.matches(".*\\b(ajoute|mets|cree|note|annule|supprime|programme (un|une|le|la))\\b.*")) {
                String act = fk.contains("demain") ? "tomorrow"
                        : fk.contains("cette semaine") ? "week"
                        : (fk.contains("aujourd'hui") || fk.contains("ce soir")) ? "today" : null;
                if (act != null) return decision("agenda", new JSONObject().put("action", act));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Decision decision(String tool, JSONObject args) {
        Decision d = new Decision();
        d.toolIds.add(tool);
        d.params.add(args);
        d.latencyMs = 0;
        return d;
    }

    /** Minuscules sans accents, MÊME longueur que l'original (pour extraire des sous-chaînes). */
    static String foldKeepLength(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.FRENCH).toCharArray()) {
            if (c == '’') { b.append('\''); continue; }
            String n = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            b.append(n.isEmpty() ? c : n.charAt(0));
        }
        return b.toString();
    }

    private static String numbersToDigits(String f) {
        String out = " " + f + " ";
        for (String[] nw : NUMBER_WORDS) {
            out = out.replaceAll("(?<=[\\s'])" + nw[0] + "(?=\\s)", nw[1]);
        }
        return out.trim();
    }

    private static String stripEnd(String s) {
        return s.replaceAll("[\\s.!]+$", "");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Identifiants d'outils pertinents pour la phrase (vide = pas une commande simple). */
    public static List<String> route(String userText) {
        String f = fold(userText);
        Set<String> out = new LinkedHashSet<>();
        for (String[] r : ROUTES) {
            if (Pattern.compile(r[1]).matcher(f).find()) out.add(r[0]);
        }
        if (out.contains("sms") || out.contains("call")) out.add("contacts");
        // Question de culture / web → cloud (le petit modèle sait peu de choses)
        if (f.matches("^(qui|pourquoi|comment|combien|c'est quoi|explique).*") && !out.contains("weather")) {
            out.clear();
        }
        return new ArrayList<>(out);
    }

    /**
     * Demande au modèle local quoi faire. Bloquant (≈4–8 s) : hors thread UI.
     * @return décision, ou null → repli cloud.
     */
    public static Decision decide(LocalLlmServer server, ToolRegistry registry, String userText) {
        List<String> ids = route(userText);
        if (ids.isEmpty() || server == null || registry == null) return null;
        long t0 = System.currentTimeMillis();
        try {
            JSONArray tools = new JSONArray();
            for (String id : ids) {
                Tool t = registry.findById(id);
                if (t != null) tools.put(OpenAiToolSchemaBuilder.toOpenAiTool(t));
            }
            if (tools.length() == 0) return null;
            if (!server.ensureStarted()) return null;

            JSONArray msgs = messages(userText);
            String key = String.join("+", ids);
            int slot = GROUPS.indexOf(key);
            String cacheName = null;
            if (slot >= 0) {
                cacheName = cacheName(key, msgs, tools);
            } else {
                slot = LocalLlmServer.SLOT_COUNT - 1;    // combinaison rare : slot libre
            }
            JSONObject body = new JSONObject()
                    .put("messages", msgs)
                    .put("tools", tools)
                    .put("temperature", 0.3)
                    .put("top_p", 0.9)
                    .put("top_k", 40)
                    .put("max_tokens", 160)
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
            if (cacheName == null) body.put("id_slot", slot);
            JSONObject templateBody = new JSONObject().put("messages", msgs).put("tools", tools)
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
            JSONObject resp = cacheName != null
                    ? server.chatInSlot(body, slot, cacheName, templateBody, 45_000)
                    : server.chat(body, 45_000);
            JSONObject msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            Decision d = new Decision();
            d.latencyMs = System.currentTimeMillis() - t0;
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                String text = msg.optString("content", "").trim();
                // Seule réponse texte acceptée : une question de clarification courte.
                if (text.endsWith("?") && text.length() < 200) {
                    d.clarification = text;
                    return d;
                }
                return null;
            }
            for (int i = 0; i < calls.length(); i++) {
                JSONObject fn = calls.getJSONObject(i).getJSONObject("function");
                String name = fn.optString("name", "");
                if (!ids.contains(name)) return null;               // outil non proposé
                JSONObject args = parseArgs(fn.opt("arguments"));
                fixAction(name, args, tools);
                String ask = needsClarification(name, args, userText);
                if (ask != null) {
                    d.toolIds.clear(); d.params.clear();
                    d.clarification = ask;
                    return d;
                }
                args = guard(name, args, userText);
                if (args == null) return null;
                d.toolIds.add(name);
                d.params.add(args);
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Nom du cache : change si le prompt fixe ou les schémas d'outils changent. */
    private static String cacheName(String key, JSONArray msgs, JSONArray tools) throws Exception {
        String prefix = msgs.getJSONObject(0).toString() + tools.toString();
        return key.replace('+', '_') + "-p2-" + Integer.toHexString(prefix.hashCode());
    }

    /**
     * Démarre le serveur et recharge tous les caches déjà calculés (bloquant, hors UI).
     * Appelé à l'ouverture de la discussion : le chargement (~7 s) se fait pendant la frappe.
     */
    public static void prewarm(LocalLlmServer server, ToolRegistry registry) {
        if (server == null || registry == null || !server.isAvailable()) return;
        if (!server.ensureStarted()) return;
        try {
            JSONArray msgs = messages("x");
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (String key : GROUPS) {
                JSONArray tools = new JSONArray();
                for (String id : key.split("\\+")) {
                    Tool t = registry.findById(id);
                    if (t != null) tools.put(OpenAiToolSchemaBuilder.toOpenAiTool(t));
                }
                if (tools.length() > 0) keep.add(LocalLlmServer.fileFor(cacheName(key, msgs, tools)));
            }
            server.pruneCaches(keep);   // anciens formats / prompts modifiés
        } catch (Exception ignored) {
        }
    }

    /** Groupes d'outils ayant un slot (cache) réservé, index = numéro de slot. */
    private static final List<String> GROUPS = java.util.Arrays.asList(
            "alarm", "timer", "agenda", "weather", "spotify", "memory", "notepad",
            "sms+contacts", "call+contacts");

    private static final java.util.Map<String, String[]> KNOWN_ACTIONS = new java.util.HashMap<>();
    static {
        KNOWN_ACTIONS.put("notepad", new String[]{"add", "list", "list_history", "list_tomorrow", "remove", "done", "clear"});
        KNOWN_ACTIONS.put("memory", new String[]{"add", "list", "forget"});
        KNOWN_ACTIONS.put("alarm", new String[]{"add", "list", "recent"});
        KNOWN_ACTIONS.put("timer", new String[]{"start", "list", "recent", "open"});
        KNOWN_ACTIONS.put("agenda", new String[]{"add", "list", "today", "tomorrow", "week", "delete", "open"});
        KNOWN_ACTIONS.put("spotify", new String[]{"play", "playlist", "pause", "next", "previous", "now_playing"});
    }

    /** Action hors de l'énumération du schéma → action la plus probable. */
    static void fixAction(String tool, JSONObject a, JSONArray tools) throws Exception {
        JSONArray allowed = null;
        String[] known = KNOWN_ACTIONS.get(tool);
        if (known != null) allowed = new JSONArray(java.util.Arrays.asList(known));
        for (int i = 0; i < tools.length(); i++) {
            JSONObject f = tools.getJSONObject(i).getJSONObject("function");
            if (!tool.equals(f.optString("name"))) continue;
            JSONObject props = f.optJSONObject("parameters") != null
                    ? f.getJSONObject("parameters").optJSONObject("properties") : null;
            JSONObject act = props != null ? props.optJSONObject("action") : null;
            if (allowed == null) allowed = act != null ? act.optJSONArray("enum") : null;
        }
        if (allowed == null || !a.has("action")) return;
        String cur = a.optString("action");
        for (int i = 0; i < allowed.length(); i++) if (cur.equals(allowed.optString(i))) return;
        // « note », « save », « create »… avec un contenu → ajout
        if (a.has("text") || a.has("title") || a.has("start") || a.has("hour")) a.put("action", "add");
        else if ("timer".equals(tool)) a.put("action", "start");
        else a.remove("action");
    }

    /** SMS / appel dont le destinataire ou le texte ne viennent pas de la phrase → on demande. */
    static String needsClarification(String tool, JSONObject a, String userText) {
        String f = fold(userText);
        if ("sms".equals(tool)) {
            String to = fold(a.optString("to", ""));
            String msg = fold(a.optString("message", ""));
            boolean toOk = !to.isEmpty() && f.contains(to.split(" ")[0]);
            boolean msgOk = false;
            for (String w : msg.split("[^a-z0-9']+")) {
                if (w.length() > 3 && f.contains(w)) { msgOk = true; break; }
            }
            if (!toOk && !msgOk) return "À qui veux-tu envoyer un message, et qu'est-ce que je lui dis ?";
            if (!toOk) return "À qui veux-tu envoyer ce message ?";
            if (!msgOk) return "Qu'est-ce que je lui écris ?";
        }
        if ("call".equals(tool)) {
            String c = fold(a.optString("contact", ""));
            if (c.isEmpty() || !f.contains(c.split(" ")[0])) return "Qui veux-tu appeler ?";
        }
        return null;
    }

    // ---- Garde-fous (ce que le petit modèle rate le plus) ----

    static JSONObject guard(String tool, JSONObject a, String userText) throws Exception {
        String f = fold(userText);
        switch (tool) {
            case "alarm": {
                if (a.has("days") && !REPEAT.matcher(f).find()) a.remove("days");
                if ("add".equals(a.optString("action", "add")) && !a.has("hour") && !a.has("time")) {
                    Matcher m = HOUR.matcher(f);
                    if (!m.find()) return null;
                    a.put("hour", Integer.parseInt(m.group(1)));
                    a.put("minute", m.group(2) != null ? Integer.parseInt(m.group(2)) : 0);
                }
                if (!a.has("action")) a.put("action", "add");
                return a;
            }
            case "timer": {
                if (!a.has("action")) a.put("action", "start");
                a.remove("confirm");
                a.remove("when");     // souvent l'heure du message recopiée
                if (a.has("label") && !f.contains(fold(a.optString("label")).split(" ")[0])) a.remove("label");
                if ("start".equals(a.optString("action"))) {
                    // Le code fait foi : « 30 sec » ne devient jamais 30 minutes.
                    Matcher m = DURATION.matcher(f);
                    if (m.find()) {
                        a.remove("minutes"); a.remove("seconds");
                        a.put("duration", m.group(0));
                    } else if (!a.has("minutes") && !a.has("seconds") && !a.has("duration")) {
                        return null;
                    }
                }
                return a;
            }
            case "notepad":
            case "memory": {
                // Pas d'échéance ni de projet inventés (souvent recopiés de l'heure du message)
                if (a.has("due_date") && !f.matches(".*(demain|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|\\d+ ?(janv|fev|mars|avr|mai|juin|juil|aout|sept|oct|nov|dec)).*"))
                    a.remove("due_date");
                if (a.has("projet_tag") && !f.contains("projet")) a.remove("projet_tag");
                a.remove("reminder_at");
                return a;
            }
            case "agenda": {
                String act = a.optString("action", "");
                Matcher p = PERIOD.matcher(f);
                if (p.find() && ("today".equals(act) || "list".equals(act))) {
                    String w = p.group();
                    if (w.equals("demain")) a.put("action", "tomorrow");
                    else if (w.equals("cette semaine")) a.put("action", "week");
                }
                if ("add".equals(act) && a.optString("start", "").isEmpty()) return null;
                String now = a.optString("action", "");
                if (now.equals("today") || now.equals("tomorrow") || now.equals("week")
                        || now.equals("list")) {
                    // Lecture : pas de titre / date recopiés de l'exemple de la description
                    a.remove("start"); a.remove("end"); a.remove("title");
                }
                return a;
            }
            case "sms":
                // Pas de texte inventé : le message doit venir de la phrase.
                if (a.optString("message", "").trim().isEmpty()) return null;
                return a;
            default:
                return a;
        }
    }

    // ---- Prompt ----

    private static JSONArray messages(String userText) throws Exception {
        String now = new SimpleDateFormat("EEEE d MMMM yyyy, HH'h'mm", Locale.FRENCH).format(new Date());
        // Prompt système FIXE (aucune heure dedans) : sinon le cache de préfixe ne sert jamais.
        String sys = "Tu es Pégase, l'assistant personnel de ton utilisateur sur son téléphone Android. "
                + "Tu le tutoies toujours. La date et l'heure actuelles sont indiquées à la fin "
                + "de chaque demande. "
                + "Utilise un outil quand c'est utile ; écris les dates, heures et durées comme "
                + "l'utilisateur les a dites (« jeudi 14h30 », « 12 minutes »). "
                + "Ne remplis que les paramètres nécessaires, rien d'inventé. "
                + "Si la demande est trop vague pour agir, pose UNE question courte. "
                + "Réponds en français, brièvement.";
        return new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", sys))
                .put(new JSONObject().put("role", "user").put("content", "Tu peux baisser le son ?"))
                .put(new JSONObject().put("role", "assistant").put("content",
                        "Je n'ai pas d'outil pour le volume ici, désolé. Utilise les boutons de ton téléphone."))
                .put(new JSONObject().put("role", "user").put("content", "Ok merci, t'es au top."))
                .put(new JSONObject().put("role", "assistant").put("content",
                        "Avec plaisir ! Dis-moi si tu as besoin d'autre chose."))
                .put(new JSONObject().put("role", "user").put("content",
                        userText.trim() + "\n\n(Nous sommes le " + now + ".)"));
    }

    private static JSONObject parseArgs(Object raw) {
        try {
            if (raw instanceof JSONObject) return (JSONObject) raw;
            String s = raw != null ? raw.toString() : "{}";
            return new JSONObject(s.isEmpty() ? "{}" : s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.FRENCH), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replace('’', '\'').replaceAll("\\s+", " ").trim();
    }
}
