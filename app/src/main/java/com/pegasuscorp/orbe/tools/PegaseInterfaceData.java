package com.pegasuscorp.orbe.tools;

import com.pegasuscorp.orbe.tools.life.DevRoutineDefaults;

import android.content.Context;
import android.content.Intent;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.memory.SessionSummary;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.spotify.SpotifyAuthHelper;
import com.pegasuscorp.orbe.voice.LearnModeStore;
import com.pegasuscorp.orbe.voice.SituationRoutineStore;
import com.pegasuscorp.orbe.voice.VoiceIntentLearnStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Données affichées dans {@link PegaseInterfaceActivity}.
 */
public final class PegaseInterfaceData {

    public static final class ToolHint {
        public final String toolId;
        public final String label;
        public final String phrase;
        /** JSON prêt à exécuter, ou null si l'activité construit les paramètres. */
        public final String toolCallJson;

        ToolHint(String toolId, String label, String phrase, String toolCallJson) {
            this.toolId = toolId;
            this.label = label;
            this.phrase = phrase;
            this.toolCallJson = toolCallJson;
        }

        public boolean launchesDirectly() {
            return toolCallJson != null && !toolCallJson.isEmpty();
        }

        public boolean isNavigation() {
            return "know_me".equals(toolId) || "memory_settings".equals(toolId)
                    || "atlas_settings".equals(toolId)
                    || "open_interface".equals(toolId);
        }

        public String favoriteKey() {
            return toolId + "::" + phrase;
        }
    }

    public static final class GeneratedFile {
        public static final String KIND_ORION = "orion";
        public static final String KIND_CONTEXT = "context";
        public static final String KIND_BUREAU = "bureau";

        public final String name;
        public final File file;
        public final long modified;
        /** {@link #KIND_ORION}, {@link #KIND_CONTEXT} ou {@link #KIND_BUREAU}. */
        public final String kind;

        GeneratedFile(String name, File file, long modified) {
            this(name, file, modified, KIND_ORION);
        }

        GeneratedFile(String name, File file, long modified, String kind) {
            this.name = name;
            this.file = file;
            this.modified = modified;
            this.kind = kind != null ? kind : KIND_ORION;
        }
    }

    /** Entrée UI : fichier seul ou pack multi-fichiers (bundle). */
    public static final class GeneratedEntry {
        public final boolean bundle;
        public final String name;
        public final String title;
        public final File fileOrDir;
        public final long modified;
        public final List<GeneratedFile> children;
        public final String kind;

        GeneratedEntry(boolean bundle, String name, String title, File fileOrDir,
                long modified, List<GeneratedFile> children) {
            this(bundle, name, title, fileOrDir, modified, children, GeneratedFile.KIND_ORION);
        }

        GeneratedEntry(boolean bundle, String name, String title, File fileOrDir,
                long modified, List<GeneratedFile> children, String kind) {
            this.bundle = bundle;
            this.name = name;
            this.title = title;
            this.fileOrDir = fileOrDir;
            this.modified = modified;
            this.children = children != null ? children : new ArrayList<>();
            this.kind = kind != null ? kind : GeneratedFile.KIND_ORION;
        }
    }

    /** Une ligne affichable dans l'onglet Discussion (RecyclerView). */
    public static final class ChatMessageUi {
        public final boolean fromUser;
        public final String speaker;
        public final String text;
        public final String timeLabel;
        /** Raisonnement du tour (messages Pégase uniquement). */
        public final com.pegasuscorp.orbe.diag.ReasoningCard reasoning;

        public ChatMessageUi(boolean fromUser, String speaker, String text, String timeLabel) {
            this(fromUser, speaker, text, timeLabel, null);
        }

        public ChatMessageUi(boolean fromUser, String speaker, String text, String timeLabel,
                com.pegasuscorp.orbe.diag.ReasoningCard reasoning) {
            this.fromUser = fromUser;
            this.speaker = speaker == null ? "" : speaker;
            this.text = text == null ? "" : text;
            this.timeLabel = timeLabel == null ? "" : timeLabel;
            this.reasoning = reasoning;
        }
    }

    public static final class ToolCategory {
        public final String title;
        public final String emoji;
        public final List<ToolHint> hints;

        ToolCategory(String title, String emoji, List<ToolHint> hints) {
            this.title = title;
            this.emoji = emoji;
            this.hints = hints;
        }
    }

    private static final List<ToolHint> STATIC_HINTS = Arrays.asList(
            new ToolHint("spotify",
                    "Spotify — lancer une musique",
                    "Mets du Daft Punk sur Spotify",
                    "{\"tool\":\"spotify\",\"params\":{\"action\":\"play\",\"query\":\"Daft Punk\"}}"),
            new ToolHint("spotify",
                    "Spotify — playlist",
                    "Une playlist des meilleures chansons d'Orelsan",
                    "{\"tool\":\"spotify\",\"params\":{\"action\":\"playlist\",\"query\":\"Orelsan\"}}"),
            new ToolHint("spotify",
                    "Spotify — titre en cours",
                    "Qu'est-ce qui joue sur Spotify ?",
                    "{\"tool\":\"spotify\",\"params\":{\"action\":\"now_playing\"}}"),
            new ToolHint("spotify",
                    "Spotify — pause",
                    "Pause la musique",
                    "{\"tool\":\"spotify\",\"params\":{\"action\":\"pause\"}}"),
            new ToolHint("spotify",
                    "Spotify — suivant",
                    "Chanson suivante",
                    "{\"tool\":\"spotify\",\"params\":{\"action\":\"next\"}}"),
            new ToolHint("youtube",
                    "YouTube — chercher",
                    "Cherche des tutos Python sur YouTube",
                    "{\"tool\":\"youtube\",\"params\":{\"action\":\"search\",\"query\":\"tutos Python\"}}"),
            new ToolHint("weather",
                    "Météo (Open-Meteo)",
                    "Quelle météo pour demain ?",
                    "{\"tool\":\"weather\",\"params\":{\"days\":2}}"),
            new ToolHint("news",
                    "Actualités (NewsAPI)",
                    "Quoi de neuf aujourd'hui ?",
                    "{\"tool\":\"news\",\"params\":{}}"),
            new ToolHint("search",
                    "Sport — recherche web (foot…)",
                    "Quel est le dernier match du PSG ?",
                    "{\"tool\":\"search\",\"params\":{\"query\":\"PSG dernier match résultat\","
                            + "\"question\":\"Quel est le dernier match du PSG ?\"}}"),
            new ToolHint("f1",
                    "F1 — débrief Grand Prix (fiche OpenF1)",
                    "Tu en as pensé quoi du GP ?",
                    "{\"tool\":\"f1\",\"params\":{\"action\":\"debrief\",\"mode\":\"quick\"}}"),
            new ToolHint("search",
                    "Recherche web (actualité)",
                    "C'est combien le Bitcoin aujourd'hui ?",
                    "{\"tool\":\"search\",\"params\":{\"query\":\"prix bitcoin actuel\","
                            + "\"question\":\"C'est combien le Bitcoin aujourd'hui ?\"}}"),
            new ToolHint("wikipedia",
                    "Wikipedia — définition",
                    "C'est quoi le coefficient de restitution ?",
                    "{\"tool\":\"wikipedia\",\"params\":{\"query\":\"coefficient de restitution\"}}"),
            new ToolHint("wikidata",
                    "Wikidata — inventeur / entité",
                    "Qui a inventé le HTML ?",
                    "{\"tool\":\"wikidata\",\"params\":{\"query\":\"HTML\"}}"),
            new ToolHint("nasa",
                    "NASA — photo du jour",
                    "Montre-moi la photo NASA du jour",
                    "{\"tool\":\"nasa\",\"params\":{}}"),
            new ToolHint("brief",
                    "Brief du matin",
                    "Brief du matin",
                    "{\"tool\":\"brief\",\"params\":{\"action\":\"brief\"}}"),
            new ToolHint("brief",
                    "Brief — plus de détail",
                    "Développe le brief",
                    "{\"tool\":\"brief\",\"params\":{\"action\":\"detail\"}}"),
            new ToolHint("brief",
                    "Routine du matin — liste",
                    "Quelles sont mes routines du matin ?",
                    "{\"tool\":\"brief\",\"params\":{\"action\":\"list\"}}"),
            new ToolHint("brief",
                    "Routine du matin — ajouter",
                    "Ajoute à ma routine du matin : cherche les résultats F1",
                    null),
            new ToolHint("diag",
                    "Diag — bilan du jour",
                    "Bilan de session",
                    "{\"tool\":\"diag\",\"params\":{\"action\":\"summary\"}}"),
            new ToolHint("diag",
                    "Diag — hésitations",
                    "Montre-moi tes hésitations",
                    "{\"tool\":\"diag\",\"params\":{\"action\":\"hesitations\"}}"),
            new ToolHint("diag",
                    "Diag — échecs outils",
                    "Quels outils ont échoué ?",
                    "{\"tool\":\"diag\",\"params\":{\"action\":\"failures\"}}"),
            new ToolHint("diag",
                    "Diag — bilan semaine",
                    "Bilan de la semaine",
                    "{\"tool\":\"diag\",\"params\":{\"action\":\"weekly\",\"days\":7}}"),
            new ToolHint("diag",
                    "Diag — chercher dans les traces",
                    "Tu as déjà eu ce problème de quota ?",
                    "{\"tool\":\"diag\",\"params\":{\"action\":\"search\",\"query\":\"quota rate limit\"}}"),
            new ToolHint("diag",
                    "Diag — analyser / corrections",
                    "Analyse tes problèmes",
                    "{\"tool\":\"diag\",\"params\":{\"action\":\"analyze\"}}"),
            new ToolHint("calculator",
                    "Calculatrice",
                    "Calcule 119 fois 5,5 pourcent",
                    "{\"tool\":\"calculator\",\"params\":{\"expression\":\"119*5.5/100\"}}"),
            new ToolHint("named_context",
                    "Contexte nommé — liste",
                    "Quels contextes sont chargés ?",
                    "{\"tool\":\"named_context\",\"params\":{\"action\":\"list\"}}"),
            new ToolHint("named_context",
                    "Contexte nommé — statut",
                    "Statut des contextes",
                    "{\"tool\":\"named_context\",\"params\":{\"action\":\"status\"}}"),
            new ToolHint("named_context",
                    "Contexte nommé — chercher",
                    "Cherche dans mes contextes : marges",
                    "{\"tool\":\"named_context\",\"params\":{\"action\":\"search\",\"query\":\"marges\"}}"),
            new ToolHint("orion_manager",
                    "Orion — statut",
                    "Statut Orion",
                    "{\"tool\":\"orion_manager\",\"params\":{\"action\":\"status\"}}"),
            new ToolHint("orion_manager",
                    "Orion — démarrer",
                    "Lance Orion",
                    "{\"tool\":\"orion_manager\",\"params\":{\"action\":\"start\"}}"),
            new ToolHint("orion_manager",
                    "Comfy — démarrer",
                    "Lance Comfy",
                    "{\"tool\":\"orion_manager\",\"params\":{\"action\":\"start_comfy\"}}"),
            new ToolHint("orion_manager",
                    "Orion — arrêter",
                    "Arrête Orion",
                    "{\"tool\":\"orion_manager\",\"params\":{\"action\":\"stop\"}}"),
            new ToolHint("orion_manager",
                    "Orion — pods",
                    "Liste les pods Orion",
                    "{\"tool\":\"orion_manager\",\"params\":{\"action\":\"list_pods\"}}"),
            new ToolHint("orion_code",
                    "Orion — coder",
                    "Demande à Orion d'écrire une fonction hello",
                    "{\"tool\":\"orion_code\",\"params\":{\"prompt\":\"Écris une fonction hello en Java\"}}"),
            new ToolHint("git_commit",
                    "Git — valider config",
                    "Vérifie GitHub et Hostinger",
                    "{\"tool\":\"git_commit\",\"params\":{\"action\":\"validate\"}}"),
            new ToolHint("git_commit",
                    "Git — pousser un fichier",
                    "Pousse le dernier fichier généré sur GitHub",
                    "{\"tool\":\"git_commit\",\"params\":{\"action\":\"commit\"}}"),
            new ToolHint("notepad",
                    "Bloc-notes — ajouter",
                    "Ajoute acheter du lait à ma liste",
                    "{\"tool\":\"notepad\",\"params\":{\"action\":\"add\",\"text\":\"acheter du lait\"}}"),
            new ToolHint("notepad",
                    "Bloc-notes — demain",
                    "Qu'est-ce que j'ai à faire demain ?",
                    "{\"tool\":\"notepad\",\"params\":{\"action\":\"list_tomorrow\"}}"),
            new ToolHint("notepad",
                    "Bloc-notes — lire",
                    "Montre-moi le bloc-notes",
                    "{\"tool\":\"notepad\",\"params\":{\"action\":\"list\"}}"),
            new ToolHint("memory",
                    "Mémoire — retenir",
                    "Retiens que ma ville c'est Lyon",
                    "{\"tool\":\"memory\",\"params\":{\"action\":\"add\",\"text\":\"ma ville c'est Lyon\"}}"),
            new ToolHint("memory",
                    "Mémoire — lire",
                    "Qu'est-ce que tu retiens sur moi ?",
                    "{\"tool\":\"memory\",\"params\":{\"action\":\"list\"}}"),
            new ToolHint("create_file",
                    "Créer un fichier récap",
                    "Crée un fichier recap.txt avec le résumé de notre discussion",
                    null),
            new ToolHint("open_app",
                    "Ouvrir une app",
                    "Ouvre WhatsApp",
                    "{\"tool\":\"open_app\",\"params\":{\"name\":\"WhatsApp\"}}"),
            new ToolHint("timer",
                    "Minuteur",
                    "Lance un minuteur de 10 minutes",
                    "{\"tool\":\"timer\",\"params\":{\"seconds\":600}}"),
            new ToolHint("alarm",
                    "Réveil",
                    "Réveille-moi à 7 heures",
                    "{\"tool\":\"alarm\",\"params\":{\"action\":\"add\",\"hour\":7,\"minute\":0}}"),
            new ToolHint("device",
                    "Batterie",
                    "Quel est le niveau de batterie ?",
                    "{\"tool\":\"device\",\"params\":{\"action\":\"battery\"}}"),
            new ToolHint("device",
                    "Heure",
                    "Quelle heure est-il ?",
                    "{\"tool\":\"device\",\"params\":{\"action\":\"time\"}}"),
            new ToolHint("device",
                    "Date",
                    "Quelle date sommes-nous ?",
                    "{\"tool\":\"device\",\"params\":{\"action\":\"date\"}}"),
            new ToolHint("navigation",
                    "Navigation — itinéraire",
                    "Itinéraire pour aller à Lyon",
                    "{\"tool\":\"navigation\",\"params\":{\"destination\":\"Lyon\"}}"),
            new ToolHint("navigation",
                    "Navigation — Waze",
                    "Ouvre Waze pour aller à Marseille",
                    "{\"tool\":\"navigation\",\"params\":{\"destination\":\"Marseille\",\"app\":\"waze\"}}"),
            new ToolHint("flashlight",
                    "Lampe torche — allumer",
                    "Allume la lampe torche",
                    "{\"tool\":\"flashlight\",\"params\":{\"action\":\"on\"}}"),
            new ToolHint("flashlight",
                    "Lampe torche — éteindre",
                    "Éteins la lampe torche",
                    "{\"tool\":\"flashlight\",\"params\":{\"action\":\"off\"}}"),
            new ToolHint("call",
                    "Appel — contact",
                    "Appelle maman",
                    "{\"tool\":\"call\",\"params\":{\"contact\":\"maman\"}}"),
            new ToolHint("connectivity",
                    "Wi-Fi — état",
                    "Le Wi-Fi est activé ?",
                    "{\"tool\":\"connectivity\",\"params\":{\"target\":\"wifi\",\"action\":\"status\"}}"),
            new ToolHint("connectivity",
                    "Wi-Fi — panneau",
                    "Ouvre le panneau Wi-Fi",
                    "{\"tool\":\"connectivity\",\"params\":{\"target\":\"wifi\",\"action\":\"panel\"}}"),
            new ToolHint("connectivity",
                    "Bluetooth — état",
                    "Le Bluetooth est activé ?",
                    "{\"tool\":\"connectivity\",\"params\":{\"target\":\"bluetooth\",\"action\":\"status\"}}"),
            new ToolHint("connectivity",
                    "Bluetooth — activer",
                    "Active le Bluetooth",
                    "{\"tool\":\"connectivity\",\"params\":{\"target\":\"bluetooth\",\"action\":\"on\"}}"),
            new ToolHint("sms",
                    "SMS",
                    "Prépare un SMS pour dire que j'arrive dans 10 minutes",
                    null),
            new ToolHint("email",
                    "E-mail — composer",
                    "Prépare un mail à yannick@example.com avec le sujet Salut",
                    "{\"tool\":\"email\",\"params\":{\"to\":\"yannick@example.com\","
                            + "\"subject\":\"Salut\",\"body\":\"Bonjour,\"}}"),
            new ToolHint("share",
                    "Partager un texte",
                    "Partage ce texte : Brief du matin prêt",
                    "{\"tool\":\"share\",\"params\":{\"text\":\"Brief du matin prêt\"}}"),
            new ToolHint("volume",
                    "Volume — silence",
                    "Mets le téléphone en sourdine",
                    "{\"tool\":\"volume\",\"params\":{\"action\":\"mute\"}}"),
            new ToolHint("volume",
                    "Volume — monter",
                    "Monte le volume",
                    "{\"tool\":\"volume\",\"params\":{\"action\":\"up\",\"steps\":2}}"),
            new ToolHint("volume",
                    "Volume — état",
                    "Quel est le volume ?",
                    "{\"tool\":\"volume\",\"params\":{\"action\":\"status\"}}"),
            new ToolHint("settings",
                    "Réglages — mode avion",
                    "Ouvre le mode avion",
                    "{\"tool\":\"settings\",\"params\":{\"panel\":\"airplane\"}}"),
            new ToolHint("settings",
                    "Réglages — hotspot",
                    "Ouvre le partage de connexion",
                    "{\"tool\":\"settings\",\"params\":{\"panel\":\"hotspot\"}}"),
            new ToolHint("settings",
                    "Réglages — luminosité",
                    "Ouvre la luminosité",
                    "{\"tool\":\"settings\",\"params\":{\"panel\":\"brightness\"}}"),
            new ToolHint("settings",
                    "Réglages — son",
                    "Ouvre les réglages son",
                    "{\"tool\":\"settings\",\"params\":{\"panel\":\"sound\"}}"),
            new ToolHint("clipboard",
                    "Presse-papiers — lire",
                    "Qu'est-ce qu'il y a dans le presse-papiers ?",
                    "{\"tool\":\"clipboard\",\"params\":{\"action\":\"get\"}}"),
            new ToolHint("clipboard",
                    "Presse-papiers — coller",
                    "Mets « Bonjour » dans le presse-papiers",
                    "{\"tool\":\"clipboard\",\"params\":{\"action\":\"set\",\"text\":\"Bonjour\"}}"),
            new ToolHint("contacts",
                    "Contacts — chercher",
                    "Cherche le contact maman",
                    "{\"tool\":\"contacts\",\"params\":{\"action\":\"search\",\"query\":\"maman\"}}"),
            new ToolHint("contacts",
                    "Contacts — appeler",
                    "Appelle maman depuis les contacts",
                    "{\"tool\":\"contacts\",\"params\":{\"action\":\"call\",\"query\":\"maman\"}}"),
            new ToolHint("contacts",
                    "Contacts — SMS",
                    "Prépare un SMS à maman",
                    "{\"tool\":\"contacts\",\"params\":{\"action\":\"sms\",\"query\":\"maman\","
                            + "\"message\":\"J'arrive\"}}"),
            new ToolHint("files",
                    "Chercher un fichier",
                    "Où est ma facture PDF ?",
                    "{\"tool\":\"files\",\"params\":{\"action\":\"search\",\"query\":\"facture\"}}"),
            new ToolHint("files",
                    "Lister Téléchargements",
                    "Liste mes téléchargements",
                    "{\"tool\":\"files\",\"params\":{\"action\":\"list\",\"folder\":\"downloads\"}}"),
            new ToolHint("files",
                    "Déplacer vers Documents",
                    "Déplace recap.pdf dans Documents",
                    "{\"tool\":\"files\",\"params\":{\"action\":\"move\",\"query\":\"recap.pdf\","
                            + "\"destination\":\"documents\"}}"),
            new ToolHint("files",
                    "Mettre à la corbeille",
                    "Supprime photo.png (corbeille)",
                    "{\"tool\":\"files\",\"params\":{\"action\":\"delete\",\"query\":\"photo.png\"}}"),
            new ToolHint("notifications",
                    "Notifications — lire",
                    "Lis mes notifications",
                    "{\"tool\":\"notifications\",\"params\":{\"action\":\"list\"}}"),
            new ToolHint("notifications",
                    "Notifications — tout effacer",
                    "Efface toutes mes notifications",
                    "{\"tool\":\"notifications\",\"params\":{\"action\":\"dismiss_all\"}}"),
            new ToolHint("calendar",
                    "Calendrier",
                    "Ajoute un rappel demain à 14 heures pour le projet Orbe",
                    "{\"tool\":\"calendar\",\"params\":{\"title\":\"Projet Orbe\",\"duration_min\":60}}"),
            new ToolHint("web_search",
                    "Ouvrir Google (navigateur)",
                    "Ouvre Google pour chercher Piper TTS français",
                    null),
            new ToolHint("open_interface",
                    "Voir la discussion",
                    "Ouvre ton interface",
                    null),
            new ToolHint("know_me",
                    "Apprends à me connaître",
                    "Gérer mon corpus vocal personnel",
                    null),
            new ToolHint("memory_settings",
                    "Mémoire — réglages avancés",
                    "Voir tous mes souvenirs",
                    null),
            new ToolHint("atlas_settings",
                    "Atlas — personnes & projets",
                    "Ouvre l'atlas des entités",
                    null)
    );

    private PegaseInterfaceData() {}

    public static List<ToolHint> toolHints() {
        List<ToolHint> all = new ArrayList<>(STATIC_HINTS);
        return all;
    }

    public static List<ToolCategory> toolCategories(Context ctx) {
        List<ToolCategory> out = new ArrayList<>();
        out.add(cat("🎵 Musique", filter("spotify", "youtube")));
        out.add(cat("📡 Info & web", filter("weather", "news", "search", "f1", "wikipedia",
                "wikidata", "nasa", "web_search", "brief", "calculator")));
        out.add(cat("🔍 Diagnostic", filter("diag")));
        out.add(cat("✅ Productivité", filter("notepad", "memory", "named_context",
                "create_file", "calendar", "timer", "alarm")));
        out.add(cat("📱 Téléphone", filter("open_app", "sms", "email", "share", "volume",
                "settings", "clipboard", "contacts", "files", "notifications", "device",
                "navigation", "flashlight", "call", "connectivity")));
        out.add(buildDevCategory());
        out.add(buildSituationCategory(ctx));
        out.add(buildLearnedCategory(ctx));
        out.add(cat("🎓 Pégase", filter("open_interface", "know_me", "memory_settings", "atlas_settings")));
        return out;
    }

    public static String formatApiStatus(Context ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder("État : ");
        sb.append(SpotifyAuthHelper.canUseApi(ctx) ? "✓ Spotify" : "○ Spotify");
        sb.append(" · ").append(ApiKeyStore.hasTavilyKey(ctx) ? "✓ Recherche" : "○ Recherche");
        sb.append(" · ✓ Wiki");
        sb.append(" · ").append(ApiKeyStore.hasNewsApiKey(ctx) ? "✓ Actus" : "○ Actus");
        sb.append(" · ").append(ApiKeyStore.hasApiFootballKey(ctx) ? "✓ Sports" : "○ Sports");
        if (LearnModeStore.isEnabled(ctx)) {
            sb.append(" · 🎓 Apprentissage actif");
        }
        return sb.toString();
    }

    private static ToolCategory cat(String title, List<ToolHint> hints) {
        return new ToolCategory(title, "", hints);
    }

    private static List<ToolHint> filter(String... toolIds) {
        List<ToolHint> out = new ArrayList<>();
        for (ToolHint h : STATIC_HINTS) {
            for (String id : toolIds) {
                if (id.equals(h.toolId)) {
                    out.add(h);
                    break;
                }
            }
        }
        return out;
    }

    private static ToolCategory buildDevCategory() {
        List<ToolHint> hints = new ArrayList<>();
        try {
            hints.add(new ToolHint("composite",
                    "Routine — On code",
                    "Lance RunPod puis Orion",
                    DevRoutineDefaults.onCodeRoutine()));
            hints.add(new ToolHint("composite",
                    "Routine — Orion seul",
                    "Ouvre Orion",
                    DevRoutineDefaults.quickDevRoutine()));
        } catch (Exception ignored) {}
        hints.addAll(filter("orion_manager", "orion_code", "git_commit"));
        hints.add(new ToolHint("open_app",
                "Ouvrir une app",
                "Ouvre WhatsApp",
                "{\"tool\":\"open_app\",\"params\":{\"name\":\"WhatsApp\"}}"));
        return new ToolCategory("💻 Dev & routines", "", hints);
    }

    private static ToolCategory buildSituationCategory(Context ctx) {
        List<ToolHint> hints = new ArrayList<>();
        if (ctx != null) {
            String morning = SituationRoutineStore.resolveRoutine(ctx,
                    SituationRoutineStore.Slot.MORNING);
            if (morning != null && !morning.isEmpty()) {
                hints.add(new ToolHint("composite",
                        "Comme d'habitude — matin",
                        "Comme d'habitude le matin",
                        morning));
            }
            String evening = SituationRoutineStore.resolveRoutine(ctx,
                    SituationRoutineStore.Slot.EVENING);
            if (evening != null && !evening.isEmpty()) {
                hints.add(new ToolHint("composite",
                        "Routine du soir",
                        "Ma routine du soir",
                        evening));
            }
            String now = SituationRoutineStore.resolveRoutine(ctx,
                    SituationRoutineStore.currentSlot());
            if (now != null && !now.isEmpty()) {
                hints.add(new ToolHint("composite",
                        "Comme d'habitude — maintenant",
                        "Comme d'habitude",
                        now));
            }
        }
        return new ToolCategory("🌅 Comme d'habitude", "", hints);
    }

    private static ToolCategory buildLearnedCategory(Context ctx) {
        List<ToolHint> hints = new ArrayList<>();
        if (ctx != null) {
            for (VoiceIntentLearnStore.LearnedIntent e
                    : VoiceIntentLearnStore.getInstance(ctx).getEntries()) {
                if (e.toolJson == null || e.toolJson.isEmpty()) continue;
                String label = e.composite
                        ? "Ma routine — " + e.label
                        : "Appris — " + e.utterance;
                hints.add(new ToolHint("composite", label, e.utterance, e.toolJson));
                if (hints.size() >= 6) break;
            }
        }
        if (hints.isEmpty()) {
            hints.add(new ToolHint("spotify",
                    "Playlist Orelsan (exemple)",
                    "Une playlist des meilleures chansons d'Orelsan",
                    "{\"tool\":\"spotify\",\"params\":{\"action\":\"playlist\",\"query\":\"Orelsan\"}}"));
        }
        return new ToolCategory("⭐ Mes habitudes", "", hints);
    }

    public static List<ToolHint> allHints(Context ctx) {
        List<ToolHint> all = new ArrayList<>();
        for (ToolCategory cat : toolCategories(ctx)) {
            all.addAll(cat.hints);
        }
        return all;
    }

    public static List<ToolHint> pinnedHints(Context ctx) {
        List<ToolHint> out = new ArrayList<>();
        for (String key : ToolFavoritesStore.getPinnedKeys(ctx)) {
            ToolHint hint = findByFavoriteKey(ctx, key);
            if (hint != null) out.add(hint);
        }
        return out;
    }

    public static ToolHint findByFavoriteKey(Context ctx, String key) {
        if (key == null || key.isEmpty()) return null;
        for (ToolHint hint : allHints(ctx)) {
            if (key.equals(hint.favoriteKey())) return hint;
        }
        return null;
    }

    public static String buildRecapFileToolCall(Context ctx) {
        try {
            JSONObject params = new JSONObject();
            params.put("filename", "recap.txt");
            params.put("content", formatConversation(ctx));
            JSONObject root = new JSONObject();
            root.put("tool", "create_file");
            root.put("params", params);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String buildSmsToolCall(String message) {
        try {
            JSONObject params = new JSONObject();
            params.put("message", message);
            JSONObject root = new JSONObject();
            root.put("tool", "sms");
            root.put("params", params);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String buildWebSearchToolCall(String query) {
        try {
            JSONObject params = new JSONObject();
            params.put("query", query);
            JSONObject root = new JSONObject();
            root.put("tool", "web_search");
            root.put("params", params);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String buildTavilyToolCall(String query) {
        try {
            JSONObject params = new JSONObject();
            params.put("query", query);
            JSONObject root = new JSONObject();
            root.put("tool", "search");
            root.put("params", params);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String formatConversation(Context ctx) {
        List<ChatBackend.Turn> turns =
                com.pegasuscorp.orbe.memory.MemoryRepository.getInstance(ctx).getRecentTurns();
        if (turns.isEmpty()) return "Aucune conversation pour l'instant.\n\n"
                + "Écris un message dans l'onglet Discussion, ou appuie longuement sur l'orbe.";

        StringBuilder sb = new StringBuilder();
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(new Date());
        sb.append("Conversation Pégase — ").append(date).append("\n");
        sb.append("─".repeat(32)).append("\n\n");
        for (ChatBackend.Turn t : turns) {
            if (t.system) continue;
            String userLabel = com.pegasuscorp.orbe.memory.UserProfileStore
                    .getInstance(ctx).getUserName();
            String speaker = t.fromUser ? userLabel : "Pégase";
            String line = t.fromUser ? t.text
                    : com.pegasuscorp.orbe.tools.ToolDispatcher.cleanForDisplay(t.text);
            if (!t.fromUser && line.isEmpty()) continue;
            sb.append(speaker).append(" : ").append(line).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** Messages structurés pour le RecyclerView Discussion. */
    public static List<ChatMessageUi> conversationMessages(Context ctx) {
        List<ChatMessageUi> out = new ArrayList<>();
        List<ChatBackend.Turn> turns =
                com.pegasuscorp.orbe.memory.MemoryRepository.getInstance(ctx).getRecentTurns();
        String userLabel = com.pegasuscorp.orbe.memory.UserProfileStore
                .getInstance(ctx).getUserName();
        if (userLabel == null || userLabel.trim().isEmpty()) userLabel = "Yannick";
        // Pas d'horodatage stocké sur Turn — heure discrète du moment d'affichage.
        String time = new SimpleDateFormat("HH:mm", Locale.FRENCH).format(new Date());
        for (ChatBackend.Turn t : turns) {
            if (t.system) continue;
            if (t.fromUser) {
                String line = t.text == null ? "" : t.text.trim();
                if (line.isEmpty()) continue;
                out.add(new ChatMessageUi(true, userLabel, line, time));
            } else {
                String line = com.pegasuscorp.orbe.memory.ConversationHistorySanitizer
                        .forDisplayAssistant(t.text);
                if (line == null || line.trim().isEmpty()) continue;
                String cleaned = line.trim();
                com.pegasuscorp.orbe.diag.ReasoningCard card =
                        com.pegasuscorp.orbe.diag.ReasoningStore.findForReply(cleaned);
                out.add(new ChatMessageUi(false, "Pégase", cleaned, time, card));
            }
        }
        return out;
    }

    /** Bandeau mémoire en tête de l'onglet Discussion (souvenirs + dernière session). */
    public static String formatMemoryReminder(Context ctx) {
        MemoryRepository repo = MemoryRepository.getInstance(ctx);
        StringBuilder sb = new StringBuilder();

        List<MemoryEntry> top = repo.getTopPermanentMemories(2);
        if (!top.isEmpty()) {
            sb.append("Pégase se souvient : ");
            for (int i = 0; i < top.size(); i++) {
                if (i > 0) sb.append(" · ");
                String content = top.get(i).content == null ? "" : top.get(i).content.trim();
                if (content.length() > 72) content = content.substring(0, 69) + "…";
                sb.append(content);
            }
        }

        SessionSummary latest = repo.getLatestSessionSummary();
        if (latest != null && latest.topic != null && !latest.topic.trim().isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Dernière session : ").append(latest.topic.trim());
        }

        return sb.toString().trim();
    }

    public static List<GeneratedFile> listGeneratedFiles(Context ctx) {
        List<GeneratedFile> out = new ArrayList<>();
        for (com.pegasuscorp.orbe.iface.FilesCatalog.Entry e :
                com.pegasuscorp.orbe.iface.FilesCatalog.listGenerated(ctx)) {
            for (com.pegasuscorp.orbe.iface.FilesCatalog.Item c : e.children) {
                out.add(new GeneratedFile(c.name, c.file, c.modified, GeneratedFile.KIND_ORION));
            }
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /**
     * @deprecated Préférer {@link com.pegasuscorp.orbe.iface.FilesCatalog#listAll}.
     */
    @Deprecated
    public static List<GeneratedEntry> listGeneratedEntries(Context ctx) {
        List<GeneratedEntry> out = new ArrayList<>();
        for (com.pegasuscorp.orbe.iface.FilesCatalog.Entry e :
                com.pegasuscorp.orbe.iface.FilesCatalog.listAll(ctx)) {
            List<GeneratedFile> kids = new ArrayList<>();
            String kind = mapKind(e.kind);
            for (com.pegasuscorp.orbe.iface.FilesCatalog.Item c : e.children) {
                kids.add(new GeneratedFile(c.name, c.file, c.modified, kind));
            }
            out.add(new GeneratedEntry(e.bundle, e.name, e.title, e.fileOrDir, e.modified, kids,
                    kind));
        }
        return out;
    }

    private static String mapKind(String catalogKind) {
        if (com.pegasuscorp.orbe.iface.FilesCatalog.KIND_CONTEXT.equals(catalogKind)) {
            return GeneratedFile.KIND_CONTEXT;
        }
        if (com.pegasuscorp.orbe.iface.FilesCatalog.KIND_BUREAU.equals(catalogKind)) {
            return GeneratedFile.KIND_BUREAU;
        }
        if (com.pegasuscorp.orbe.iface.FilesCatalog.KIND_PROJECT.equals(catalogKind)) {
            return "project";
        }
        return GeneratedFile.KIND_ORION;
    }

    /** @deprecated Préférer {@link com.pegasuscorp.orbe.iface.FilesCatalog#listContexts}. */
    @Deprecated
    public static List<GeneratedEntry> listContextMdEntries(Context ctx) {
        return filterLegacy(listGeneratedEntries(ctx), GeneratedFile.KIND_CONTEXT);
    }

    /** @deprecated Préférer {@link com.pegasuscorp.orbe.iface.FilesCatalog#listBureau}. */
    @Deprecated
    public static List<GeneratedEntry> listBureauMdEntries(Context ctx) {
        return filterLegacy(listGeneratedEntries(ctx), GeneratedFile.KIND_BUREAU);
    }

    private static List<GeneratedEntry> filterLegacy(List<GeneratedEntry> all, String kind) {
        List<GeneratedEntry> out = new ArrayList<>();
        for (GeneratedEntry e : all) {
            if (kind.equals(e.kind)) out.add(e);
        }
        return out;
    }

    public static String readFilePreview(File file, int maxChars) {
        return com.pegasuscorp.orbe.iface.FilesCatalog.readFilePreview(file, maxChars);
    }
}
