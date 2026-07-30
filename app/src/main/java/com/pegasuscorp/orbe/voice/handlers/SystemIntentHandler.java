package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;

import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SystemIntentHandler implements IntentHandler {

    private static final Pattern DISMISS_NOTIF_NUM = Pattern.compile(
            "(?i)(?:efface|supprime|enleve|enlève|vide|retire).*?(?:notification|notif).*?(\\d+)");
    private static final Pattern OPEN_NOTIF_NUM = Pattern.compile(
            "(?i)(?:ouvre|affiche|montre).*?(?:notification|notif).*?(\\d+)");

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (com.pegasuscorp.orbe.tools.device.MathCalcTrigger.matches(text)) {
            String json = calculatorJson(text);
            if (json != null) {
                return VoiceIntentSupport.routed(context, text, json, "calcul", 0.98);
            }
        }

        if (looksLikeFlashlight(fold)) {
            RoutedIntent flashlight = routeFlashlight(context, text, fold);
            if (flashlight != null) return flashlight;
        }

        if (looksLikeNavigation(fold)) {
            RoutedIntent navigation = routeNavigation(context, text, fold);
            if (navigation != null) return navigation;
        }

        if (looksLikeDevice(fold)) {
            RoutedIntent device = routeDevice(context, text, fold);
            if (device != null) return device;
        }

        if (looksLikeAlarm(fold)) {
            RoutedIntent alarm = routeAlarm(context, text, fold);
            if (alarm != null) return alarm;
        }

        if (looksLikeCall(fold)) {
            RoutedIntent call = routeCall(context, text, fold);
            if (call != null) return call;
        }

        if (looksLikeConnectivity(fold)) {
            RoutedIntent connectivity = routeConnectivity(context, text, fold);
            if (connectivity != null) return connectivity;
        }

        if (looksLikeVolume(fold)) {
            RoutedIntent volume = routeVolume(context, text, fold);
            if (volume != null) return volume;
        }

        if (looksLikeEmail(fold)) {
            RoutedIntent email = routeEmail(context, text, fold);
            if (email != null) return email;
        }

        if (looksLikeShare(fold)) {
            RoutedIntent share = routeShare(context, text, fold);
            if (share != null) return share;
        }

        if (looksLikeSettingsPanel(fold)) {
            RoutedIntent settings = routeSettingsPanel(context, text, fold);
            if (settings != null) return settings;
        }

        if (looksLikeClipboard(fold)) {
            RoutedIntent clipboard = routeClipboard(context, text, fold);
            if (clipboard != null) return clipboard;
        }

        if (looksLikeContacts(fold)) {
            RoutedIntent contacts = routeContacts(context, text, fold);
            if (contacts != null) return contacts;
        }

        if (looksLikeFiles(fold)) {
            RoutedIntent files = routeFiles(context, text, fold);
            if (files != null) return files;
        }

        if (looksLikeNotifications(fold)) {
            RoutedIntent notif = routeNotifications(context, text, fold);
            if (notif != null) return notif;
        }

        return null;
    }

    static String calculatorJson(String text) {
        try {
            return VoiceIntentSupport.toolJson("calculator", new JSONObject()
                    .put("expression", text)
                    .put("question", text));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeNotifications(String fold) {
        return fold.contains("notification")
                || fold.contains("notif ")
                || fold.endsWith(" notif")
                || fold.startsWith("notif ")
                || fold.equals("notif")
                || fold.equals("notifs");
    }

    static RoutedIntent routeNotifications(Context context, String text, String fold) {
        try {
            if ((fold.contains("efface") || fold.contains("supprime") || fold.contains("enleve")
                    || fold.contains("enlève") || fold.contains("vide") || fold.contains("retire"))
                    && (fold.contains("toute") || fold.contains("toutes"))) {
                JSONObject p = new JSONObject().put("action", "dismiss_all");
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("notifications", p), "notifications", 0.9);
            }

            Matcher dismissNum = DISMISS_NOTIF_NUM.matcher(text);
            if (dismissNum.find()) {
                JSONObject p = new JSONObject()
                        .put("action", "dismiss")
                        .put("index", Integer.parseInt(dismissNum.group(1)));
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("notifications", p), "notifications", 0.88);
            }

            Matcher openNum = OPEN_NOTIF_NUM.matcher(text);
            if (openNum.find()) {
                JSONObject p = new JSONObject()
                        .put("action", "open")
                        .put("index", Integer.parseInt(openNum.group(1)));
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("notifications", p), "notifications", 0.88);
            }

            if (fold.contains("efface") || fold.contains("supprime") || fold.contains("enleve")
                    || fold.contains("enlève") || fold.contains("vide") || fold.contains("retire")) {
                String target = extractNotificationTarget(text);
                if (target != null) {
                    JSONObject p = new JSONObject().put("action", "dismiss").put("app", target);
                    return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("notifications", p), "notifications", 0.82);
                }
            }

            if (fold.contains("ouvre") || fold.contains("affiche") || fold.contains("montre")) {
                String target = extractNotificationTarget(text);
                if (target != null) {
                    JSONObject p = new JSONObject().put("action", "open").put("app", target);
                    return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("notifications", p), "notifications", 0.82);
                }
            }

            if (fold.contains("lis") || fold.contains("quelles") || fold.contains("affiche")
                    || fold.contains("liste") || fold.contains("montre")
                    || fold.equals("notifications") || fold.equals("notifs")
                    || fold.contains("mes notifications") || fold.contains("mes notifs")) {
                JSONObject p = new JSONObject().put("action", "list");
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("notifications", p), "notifications", 0.85);
            }
        } catch (Exception ignored) {}
        return null;
    }

    static String extractNotificationTarget(String text) {
        String t = text.trim().replaceAll("[?.!]+$", "").trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{
                "efface la notification de ", "efface la notification ", "efface la notif de ",
                "efface la notif ", "supprime la notification de ", "supprime la notification ",
                "supprime la notif de ", "supprime la notif ",
                "ouvre la notification de ", "ouvre la notification ", "ouvre la notif de ",
                "ouvre la notif "}) {
            if (lower.startsWith(prefix)) {
                String target = t.substring(prefix.length()).trim();
                if (!target.isEmpty()) return target;
            }
        }
        return null;
    }

    /** Batterie / heure / date — aussi utilisé en short-circuit texte (PegaseSession). */
    public static boolean looksLikeDevice(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("batterie") || fold.contains("niveau de batterie")
                || fold.contains("niveau de charge")
                || fold.contains("quelle heure") || fold.contains("il est quelle heure")
                || fold.equals("l heure") || fold.contains("dis moi l heure")
                || fold.contains("quelle date") || fold.contains("quelle est la date")
                || fold.contains("on est quel jour") || fold.contains("quel jour on est")
                || fold.contains("on est le combien");
    }

    /** action device depuis fold : battery | time | date. */
    public static String deviceActionFromFold(String fold) {
        if (fold == null) return "time";
        if (fold.contains("batterie") || fold.contains("charge")) return "battery";
        if (fold.contains("date") || fold.contains("jour") || fold.contains("combien")) {
            return "date";
        }
        return "time";
    }

    static RoutedIntent routeDevice(Context context, String text, String fold) {
        try {
            String action = deviceActionFromFold(fold);
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("device", new JSONObject().put("action", action)),
                    "téléphone", 0.95);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeAlarm(String fold) {
        return fold.contains("reveille") || fold.contains("réveille")
                || fold.contains("alarme") || fold.contains("reveil")
                || fold.contains("mes reveils") || fold.contains("mes alarmes");
    }

    static RoutedIntent routeAlarm(Context context, String text, String fold) {
        if (fold.contains("liste") || fold.contains("montre") || fold.contains("affiche")
                || fold.contains("quels sont") || fold.contains("mes reveils")
                || fold.contains("mes alarmes") || fold.contains("voir")) {
            try {
                return VoiceIntentSupport.routed(context, text,
                        VoiceIntentSupport.toolJson("alarm", new JSONObject().put("action", "list")),
                        "réveil", 0.92);
            } catch (Exception e) {
                return null;
            }
        }
        Matcher m = Pattern.compile("(?i)(?:à|a)\\s*(\\d{1,2})\\s*(?:h|heures?)(?:\\s*(\\d{1,2}))?")
                .matcher(text);
        if (!m.find()) {
            m = Pattern.compile("(?i)(\\d{1,2})\\s*h(?:\\s*(\\d{1,2}))?").matcher(text);
            if (!m.find()) return null;
        }
        try {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            JSONObject p = new JSONObject()
                    .put("action", "add")
                    .put("hour", hour)
                    .put("minute", minute);
            String label = extractAlarmLabel(text);
            if (label != null && !label.isEmpty()) {
                p.put("label", label);
            }
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("alarm", p), "réveil", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    /** « pour chauffer les plats » / « — note » après l'heure. */
    static String extractAlarmLabel(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile(
                "(?i)(?:pour|afin de)\\s+(.+?)(?:\\s*$|\\s*[.!?])")
                .matcher(text);
        if (m.find()) {
            String label = m.group(1).trim();
            label = label.replaceAll("(?i)^(me |m'|te )", "");
            if (!label.isEmpty()) {
                return label.substring(0, 1).toUpperCase(Locale.FRENCH)
                        + label.substring(1);
            }
        }
        return "";
    }

    static boolean looksLikeCall(String fold) {
        return fold.startsWith("appelle ") || fold.startsWith("appele ")
                || fold.contains(" telephone ") || fold.contains(" telephon")
                || fold.contains(" appeler ");
    }

    static RoutedIntent routeCall(Context context, String text, String fold) {
        String contact = extractCallContact(text);
        if (contact == null || contact.isEmpty()) {
            return RoutedIntent.withHint(text, "appel — dis par exemple « appelle maman »");
        }
        try {
            JSONObject p = new JSONObject().put("contact", contact);
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("call", p), "appel", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractCallContact(String text) {
        Matcher m = Pattern.compile("(?i)appelle\\s+(.+)$").matcher(text.trim());
        if (m.find()) return cleanCallContact(m.group(1));
        m = Pattern.compile("(?i)telephone(?:\\s+a)?\\s+(.+)$").matcher(text.trim());
        if (m.find()) return cleanCallContact(m.group(1));
        m = Pattern.compile("(?i)appeler\\s+(.+)$").matcher(text.trim());
        if (m.find()) return cleanCallContact(m.group(1));
        return null;
    }

    static String cleanCallContact(String raw) {
        if (raw == null) return null;
        String c = raw.trim();
        if (c.endsWith(".")) c = c.substring(0, c.length() - 1).trim();
        return c.isEmpty() ? null : c;
    }

    static boolean looksLikeConnectivity(String fold) {
        return fold.contains("wifi") || fold.contains("wai fi")
                || fold.contains("bluetooth") || fold.contains("blue tooth")
                || fold.contains("bluetooth");
    }

    static boolean looksLikeVolume(String fold) {
        return fold.contains("volume")
                || fold.contains("monte le son") || fold.contains("baisse le son")
                || fold.contains("plus fort") || fold.contains("moins fort")
                || ((fold.contains("silence") || fold.contains("muet") || fold.contains("sourdine"))
                && (fold.contains("mets") || fold.contains("mode") || fold.contains("volume")
                || fold.contains("telephone") || fold.contains("son")));
    }

    static RoutedIntent routeVolume(Context context, String text, String fold) {
        try {
            String action = "status";
            if (fold.contains("muet") || fold.contains("silence") || fold.contains("sourdine")
                    || fold.contains("coupe le son")) {
                action = "mute";
            } else if (fold.contains("reactive") || fold.contains("enleve le silence")
                    || fold.contains("remets le son") || fold.contains("unmute")) {
                action = "unmute";
            } else if (fold.contains("monte") || fold.contains("plus fort") || fold.contains("augmente")) {
                action = "up";
            } else if (fold.contains("baisse") || fold.contains("moins fort") || fold.contains("diminue")) {
                action = "down";
            }
            JSONObject p = new JSONObject().put("action", action);
            if ("up".equals(action) || "down".equals(action)) {
                p.put("steps", 2);
            }
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("volume", p), "volume", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeEmail(String fold) {
        return fold.contains("email") || fold.contains("e-mail") || fold.contains("courriel")
                || fold.contains("envoie un mail") || fold.contains("envoi un mail")
                || fold.contains("envoyer un mail") || fold.contains("prepare un mail")
                || fold.contains("ecris un mail") || (fold.contains("mail")
                && (fold.contains("envoie") || fold.contains("envoi") || fold.contains("prepare")
                || fold.contains("ecris") || fold.contains("compose")));
    }

    static RoutedIntent routeEmail(Context context, String text, String fold) {
        try {
            JSONObject p = new JSONObject();
            Matcher to = Pattern.compile("(?i)(?:a|à|pour)\\s+([\\w.+\\-]+@[\\w.\\-]+)")
                    .matcher(text);
            if (to.find()) p.put("to", to.group(1).trim());
            Matcher sub = Pattern.compile("(?i)(?:sujet|objet)\\s+[:\\-]?\\s*(.+)$")
                    .matcher(text.trim());
            if (sub.find()) {
                String s = sub.group(1).trim();
                if (s.length() > 80) s = s.substring(0, 80).trim();
                p.put("subject", s);
            }
            if (!p.has("subject") && !p.has("to")) {
                return RoutedIntent.withHint(text,
                        "e-mail — dis par exemple « prépare un mail à moi@exemple.fr sujet Salut »");
            }
            if (!p.has("body")) {
                p.put("body", "");
            }
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("email", p), "e-mail", 0.86);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeShare(String fold) {
        return fold.startsWith("partage ") || fold.contains("partage ca")
                || fold.contains("partage ça") || fold.contains("partage ce texte")
                || fold.contains("partager ") || fold.contains("envoie ce texte");
    }

    static RoutedIntent routeShare(Context context, String text, String fold) {
        try {
            String payload = extractShareText(text);
            if (payload == null || payload.length() < 2) {
                return RoutedIntent.withHint(text,
                        "partage — dis par exemple « partage ce texte : bonjour »");
            }
            JSONObject p = new JSONObject().put("text", payload);
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("share", p), "partage", 0.88);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractShareText(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile(
                "(?i)(?:partage(?:r)?(?:\\s+(?:ca|ça|ce texte))?|envoie ce texte)\\s*[:\\-]?\\s*(.+)$")
                .matcher(text.trim());
        if (m.find()) return m.group(1).trim();
        return null;
    }

    static boolean looksLikeSettingsPanel(String fold) {
        return fold.contains("mode avion") || fold.contains("hotspot")
                || fold.contains("partage de connexion") || fold.contains("partage connexion")
                || fold.contains("luminosite") || fold.contains("reglage son")
                || fold.contains("reglages son") || fold.contains("reglages du son")
                || (fold.contains("ouvre") && (fold.contains("luminosite") || fold.contains("affichage")
                || fold.contains("hotspot") || fold.contains("mode avion")));
    }

    static RoutedIntent routeSettingsPanel(Context context, String text, String fold) {
        try {
            String panel = "sound";
            if (fold.contains("avion")) panel = "airplane";
            else if (fold.contains("hotspot") || fold.contains("partage de connexion")
                    || fold.contains("partage connexion") || fold.contains("modem")) {
                panel = "hotspot";
            } else if (fold.contains("lumin") || fold.contains("affichage") || fold.contains("ecran")) {
                panel = "brightness";
            } else if (fold.contains("son") || fold.contains("audio")) {
                panel = "sound";
            }
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("settings", new JSONObject().put("panel", panel)),
                    "réglages", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeClipboard(String fold) {
        return fold.contains("presse papier") || fold.contains("presse-papiers")
                || fold.contains("presse papiers") || fold.contains("clipboard")
                || fold.contains("dans le presse")
                || (fold.contains("copie ") && fold.contains("presse"))
                || (fold.contains("mets ") && fold.contains("presse"));
    }

    static RoutedIntent routeClipboard(Context context, String text, String fold) {
        try {
            boolean set = fold.contains("mets ") || fold.contains("copie ")
                    || fold.contains("coller ") || fold.contains("copier ")
                    || fold.contains("action set");
            if (set) {
                String payload = extractClipboardText(text);
                if (payload == null || payload.length() < 1) {
                    return RoutedIntent.withHint(text,
                            "presse-papiers — dis « mets Bonjour dans le presse-papiers »");
                }
                return VoiceIntentSupport.routed(context, text,
                        VoiceIntentSupport.toolJson("clipboard", new JSONObject()
                                .put("action", "set").put("text", payload)),
                        "presse-papiers", 0.88);
            }
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("clipboard", new JSONObject().put("action", "get")),
                    "presse-papiers", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractClipboardText(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile(
                "(?i)(?:mets|copie|copier|colle)\\s+[«\"]?(.+?)[»\"]?\\s+(?:dans|au)\\s+le\\s+presse")
                .matcher(text.trim());
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?i)presse[- ]papiers?\\s*[:\\-]\\s*(.+)$").matcher(text.trim());
        if (m.find()) return m.group(1).trim();
        return null;
    }

    static boolean looksLikeContacts(String fold) {
        return fold.contains("cherche le contact") || fold.contains("cherche contact")
                || fold.contains("trouve le contact") || fold.contains("dans mes contacts")
                || fold.contains("dans les contacts") || fold.contains("annuaire")
                || (fold.contains("contact") && (fold.contains("cherche") || fold.contains("trouve")
                || fold.contains("qui est") || fold.contains("numero de")));
    }

    static RoutedIntent routeContacts(Context context, String text, String fold) {
        try {
            String query = extractContactQuery(text, fold);
            if (query == null || query.length() < 2) {
                return RoutedIntent.withHint(text,
                        "contacts — dis par exemple « cherche le contact maman »");
            }
            String action = "search";
            if (fold.contains("appelle") || fold.contains("appel")) action = "call";
            else if (fold.contains("sms") || fold.contains("message")) action = "sms";
            JSONObject p = new JSONObject().put("action", action).put("query", query);
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("contacts", p), "contacts", 0.88);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractContactQuery(String text, String fold) {
        Matcher m = Pattern.compile(
                "(?i)(?:cherche(?:r)?|trouve)\\s+(?:le\\s+)?contact\\s+(.+)$")
                .matcher(text.trim());
        if (m.find()) return cleanCallContact(m.group(1));
        m = Pattern.compile("(?i)(?:numero|numéro)\\s+(?:de\\s+)?(.+)$").matcher(text.trim());
        if (m.find()) return cleanCallContact(m.group(1));
        m = Pattern.compile("(?i)dans\\s+(?:mes|les)\\s+contacts\\s+(.+)$").matcher(text.trim());
        if (m.find()) return cleanCallContact(m.group(1));
        if (fold != null && fold.contains("contact")) {
            m = Pattern.compile("(?i)contact\\s+(.+)$").matcher(text.trim());
            if (m.find()) return cleanCallContact(m.group(1));
        }
        return null;
    }

    static boolean looksLikeFiles(String fold) {
        return fold.contains("mes fichiers") || fold.contains("dans mes fichiers")
                || fold.contains("liste mes fichiers") || fold.contains("liste des fichiers")
                || fold.contains("dans telechargements") || fold.contains("dans les telechargements")
                || fold.contains("ouvre le fichier") || fold.contains("ouvrir le fichier")
                || ((fold.contains("supprime") || fold.contains("efface") || fold.contains("deplace")
                || fold.contains("déplace") || fold.contains("ou est") || fold.contains("où est")
                || fold.contains("cherche") || fold.contains("trouve"))
                && (fold.contains("fichier") || fold.contains("pdf") || fold.contains("photo")
                || fold.contains("facture") || fold.contains("telecharg")
                || fold.contains(".pdf") || fold.contains(".jpg") || fold.contains(".png")));
    }

    static RoutedIntent routeFiles(Context context, String text, String fold) {
        try {
            String action = "search";
            if (fold.contains("supprime") || fold.contains("efface") || fold.contains("corbeille")
                    || fold.contains("delete")) {
                action = "delete";
            } else if (fold.contains("deplace") || fold.contains("déplace")
                    || fold.contains("deplacer") || fold.contains("déplacer")
                    || fold.contains("mets dans") || fold.contains("mettre dans")) {
                action = "move";
            } else if (fold.contains("ouvre") || fold.contains("ouvrir")) {
                action = "open";
            } else if (fold.contains("liste") || fold.contains("list ")
                    || fold.contains("montre mes telecharg")
                    || fold.contains("contenu de telecharg")) {
                action = "list";
            }

            JSONObject p = new JSONObject().put("action", action);
            if ("list".equals(action)) {
                String folder = "downloads";
                if (fold.contains("document")) folder = "documents";
                else if (fold.contains("photo") || fold.contains("picture") || fold.contains("image")) {
                    folder = "pictures";
                } else if (fold.contains("dcim") || fold.contains("camera")) {
                    folder = "dcim";
                }
                p.put("folder", folder);
                return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("files", p), "fichiers", 0.88);
            }

            String query = extractFileQuery(text);
            if ((query == null || query.length() < 2) && !"list".equals(action)) {
                return RoutedIntent.withHint(text,
                        "fichiers — dis « où est facture.pdf » ou « liste mes téléchargements »");
            }
            if (query != null && !query.isEmpty()) p.put("query", query);

            if ("move".equals(action)) {
                String dest = "documents";
                if (fold.contains("telecharg")) dest = "downloads";
                else if (fold.contains("photo") || fold.contains("picture")) dest = "pictures";
                else if (fold.contains("dcim")) dest = "dcim";
                else if (fold.contains("document")) dest = "documents";
                p.put("destination", dest);
            }

            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("files", p), "fichiers", 0.88);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractFileQuery(String text) {
        if (text == null) return null;
        String t = text.trim();
        Matcher m = Pattern.compile(
                "(?i)(?:ou\\s+est|où\\s+est|cherche(?:r)?|trouve|ouvre|ouvrir|supprime(?:r)?"
                        + "|efface(?:r)?|deplace(?:r)?|déplace(?:r)?)\\s+(?:le\\s+|la\\s+|mon\\s+|ma\\s+|les\\s+)?"
                        + "(?:fichier\\s+|photo\\s+|pdf\\s+)?[«\"]?(.+?)[»\"]?\\s*$")
                .matcher(t);
        if (m.find()) {
            String q = m.group(1).trim();
            q = q.replaceAll("(?i)\\s+(dans|vers)\\s+(mes\\s+)?(documents?|telechargements?|photos?).*$", "");
            q = q.replaceAll("(?i)^(le|la|les|mon|ma|mes)\\s+", "");
            return q.trim();
        }
        m = Pattern.compile("(?i)(?:fichier|pdf|photo)\\s+[«\"]?([\\w.\\-]+)[»\"]?").matcher(t);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    static RoutedIntent routeConnectivity(Context context, String text, String fold) {
        try {
            String target = fold.contains("bluetooth") || fold.contains("blue tooth")
                    ? "bluetooth" : "wifi";
            String action = "status";
            if (fold.contains("panneau") || fold.contains("reglage") || fold.contains("reglages")) {
                action = "panel";
            } else if (fold.contains("desactive") || fold.contains("eteint") || fold.contains("coupe")) {
                action = "off";
            } else if ((fold.contains("active") || fold.contains("allume") || fold.contains("ouvre"))
                    && !fold.contains("?") && !fold.contains("est ")) {
                action = "on";
            }
            JSONObject p = new JSONObject()
                    .put("target", target)
                    .put("action", action);
            String hint = "bluetooth".equals(target) ? "bluetooth" : "wi-fi";
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("connectivity", p), hint, 0.88);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeFlashlight(String fold) {
        return fold.contains("lampe torche") || fold.contains("torche")
                || (fold.contains("lampe") && (fold.contains("allume") || fold.contains("eteint")
                || fold.contains("coupe")));
    }

    static RoutedIntent routeFlashlight(Context context, String text, String fold) {
        try {
            String action = "toggle";
            if (fold.contains("allume") || fold.contains("active")) {
                action = "on";
            } else if (fold.contains("eteint") || fold.contains("coupe") || fold.contains("eteins")) {
                action = "off";
            }
            return VoiceIntentSupport.routed(context, text,
                    VoiceIntentSupport.toolJson("flashlight", new JSONObject().put("action", action)),
                    "lampe torche", 0.92);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean looksLikeNavigation(String fold) {
        return fold.contains("itineraire") || fold.contains("navigation")
                || fold.contains("waze") || fold.contains("google maps")
                || fold.contains("aller a") || fold.contains("va a")
                || fold.contains("direction") || fold.contains("route pour")
                || fold.contains("chemin pour");
    }

    static RoutedIntent routeNavigation(Context context, String text, String fold) {
        String destination = extractNavigationDestination(text);
        if (destination == null || destination.isEmpty()) {
            return RoutedIntent.withHint(text, "navigation — précise la destination");
        }
        try {
            String app = "auto";
            if (fold.contains("waze")) app = "waze";
            else if (fold.contains("google maps") || fold.contains(" maps ")) app = "maps";
            JSONObject p = new JSONObject()
                    .put("destination", destination)
                    .put("app", app);
            return VoiceIntentSupport.routed(context, text, VoiceIntentSupport.toolJson("navigation", p), "navigation", 0.9);
        } catch (Exception e) {
            return null;
        }
    }

    static String extractNavigationDestination(String text) {
        Matcher m = Pattern.compile(
                "(?i)(?:itineraire|itinéraire|navigation|route|chemin|direction)"
                        + "\\s+(?:pour|vers|jusqu['']?a|a|à)\\s+(.+)$")
                .matcher(text.trim());
        if (m.find()) return cleanNavigationDest(m.group(1));
        m = Pattern.compile("(?i)(?:va|aller)\\s+(?:a|à)\\s+(.+)$").matcher(text.trim());
        if (m.find()) return cleanNavigationDest(m.group(1));
        m = Pattern.compile("(?i)(?:waze|maps|google maps)\\s+(?:pour|vers)\\s+(.+)$")
                .matcher(text.trim());
        if (m.find()) return cleanNavigationDest(m.group(1));
        m = Pattern.compile("(?i)ouvre\\s+waze\\s+pour\\s+(.+)$").matcher(text.trim());
        if (m.find()) return cleanNavigationDest(m.group(1));
        return null;
    }

    static String cleanNavigationDest(String raw) {
        if (raw == null) return null;
        String d = raw.trim();
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1).trim();
        if (d.toLowerCase(Locale.ROOT).startsWith("aller ")) {
            d = d.substring(6).trim();
        }
        if (d.toLowerCase(Locale.ROOT).startsWith("a ") || d.startsWith("à ")) {
            d = d.substring(2).trim();
        }
        return d.isEmpty() ? null : d;
    }
}
