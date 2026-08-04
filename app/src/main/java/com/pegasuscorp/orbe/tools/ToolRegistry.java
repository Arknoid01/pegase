package com.pegasuscorp.orbe.tools;

import com.pegasuscorp.orbe.tools.device.AlarmTool;
import com.pegasuscorp.orbe.tools.device.CalculatorTool;
import com.pegasuscorp.orbe.tools.device.CallTool;
import com.pegasuscorp.orbe.tools.copilot.CopilotActionTool;
import com.pegasuscorp.orbe.tools.copilot.UiActionTool;
import com.pegasuscorp.orbe.tools.copilot.UiExplainTool;
import com.pegasuscorp.orbe.tools.copilot.UiSearchTool;
import com.pegasuscorp.orbe.tools.device.ClipboardTool;
import com.pegasuscorp.orbe.tools.device.ConnectivityTool;
import com.pegasuscorp.orbe.tools.device.ContactsTool;
import com.pegasuscorp.orbe.tools.device.DeviceTool;
import com.pegasuscorp.orbe.tools.device.EmailTool;
import com.pegasuscorp.orbe.tools.device.FilesTool;
import com.pegasuscorp.orbe.tools.device.FlashlightTool;
import com.pegasuscorp.orbe.tools.device.NavigationTool;
import com.pegasuscorp.orbe.tools.device.NotificationsTool;
import com.pegasuscorp.orbe.tools.device.OpenAppTool;
import com.pegasuscorp.orbe.tools.device.OpenInterfaceTool;
import com.pegasuscorp.orbe.tools.device.SettingsTool;
import com.pegasuscorp.orbe.tools.device.ScreenCaptureTool;
import com.pegasuscorp.orbe.tools.device.ShareTool;
import com.pegasuscorp.orbe.tools.device.SmsTool;
import com.pegasuscorp.orbe.tools.device.TimerTool;
import com.pegasuscorp.orbe.tools.device.VolumeTool;
import com.pegasuscorp.orbe.tools.knowledge.BriefTool;
import com.pegasuscorp.orbe.tools.knowledge.DiagTool;
import com.pegasuscorp.orbe.tools.knowledge.F1CompanionTool;
import com.pegasuscorp.orbe.tools.knowledge.NasaTool;
import com.pegasuscorp.orbe.tools.knowledge.NewsTool;
import com.pegasuscorp.orbe.tools.knowledge.TavilyTool;
import com.pegasuscorp.orbe.tools.knowledge.WeatherTool;
import com.pegasuscorp.orbe.tools.knowledge.WebSearchTool;
import com.pegasuscorp.orbe.tools.knowledge.WikidataTool;
import com.pegasuscorp.orbe.tools.knowledge.WikipediaTool;
import com.pegasuscorp.orbe.tools.life.AgendaTool;
import com.pegasuscorp.orbe.tools.life.CalendarTool;
import com.pegasuscorp.orbe.tools.life.LifePatternTool;
import com.pegasuscorp.orbe.tools.life.ProjectObjectTool;
import com.pegasuscorp.orbe.tools.media.CreateFileTool;
import com.pegasuscorp.orbe.tools.media.SpotifyTool;
import com.pegasuscorp.orbe.tools.media.YouTubeTool;
import com.pegasuscorp.orbe.tools.memory.ContextTool;
import com.pegasuscorp.orbe.tools.memory.MemoryTool;
import com.pegasuscorp.orbe.tools.memory.NotepadTool;
import com.pegasuscorp.orbe.tools.orion.GitCommitTool;
import com.pegasuscorp.orbe.tools.orion.OrionCodeTool;
import com.pegasuscorp.orbe.tools.orion.OrionFilesTool;
import com.pegasuscorp.orbe.tools.orion.OrionManagerTool;
import com.pegasuscorp.orbe.tools.orion.OrionProjectTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Catalogue de tous les outils disponibles et générateur de la section prompt.
 */
public class ToolRegistry {

    private final List<Tool> tools = Arrays.asList(
            new CreateFileTool(),
            new WebSearchTool(),
            new SpotifyTool(),
            new SmsTool(),
            new AgendaTool(),
            new CalendarTool(),
            new TimerTool(),
            new OpenAppTool(),
            new OpenInterfaceTool(),
            new WeatherTool(),
            new NewsTool(),
            new TavilyTool(),
            new F1CompanionTool(),
            new WikipediaTool(),
            new WikidataTool(),
            new CalculatorTool(),
            new NasaTool(),
            new NotepadTool(),
            new ContextTool(),
            new NotificationsTool(),
            new YouTubeTool(),
            new MemoryTool(),
            new DiagTool(),
            new BriefTool(),
            new LifePatternTool(),
            new ProjectObjectTool(),
            new OrionManagerTool(),
            new OrionCodeTool(),
            new OrionFilesTool(),
            new OrionProjectTool(),
            new GitCommitTool(),
            new AlarmTool(),
            new DeviceTool(),
            new NavigationTool(),
            new FlashlightTool(),
            new CallTool(),
            new ConnectivityTool(),
            new EmailTool(),
            new ShareTool(),
            new ScreenCaptureTool(),
            new VolumeTool(),
            new SettingsTool(),
            new ClipboardTool(),
            new CopilotActionTool(),
            new UiActionTool(),
            new UiExplainTool(),
            new UiSearchTool(),
            new ContactsTool(),
            new FilesTool()
    );

    private final CompositeTool compositeTool;

    public ToolRegistry() {
        compositeTool = new CompositeTool(this);
    }

    public Tool findById(String id) {
        if (compositeTool.id().equals(id)) return compositeTool;
        for (Tool t : tools) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }

    /** Outils filtrés par tag — schémas OpenAI et function calling. */
    public List<Tool> listTools(EnumSet<ToolTag> allowed) {
        EnumSet<ToolTag> tags = allowed != null && !allowed.isEmpty()
                ? allowed : EnumSet.allOf(ToolTag.class);
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools) {
            if (tags.contains(t.tag())) {
                out.add(t);
            }
        }
        if (tags.contains(ToolTag.COMPOSITE)) {
            out.add(compositeTool);
        }
        return out;
    }

    /** Hint court quand le modèle reçoit les outils via l'API (pas de JSON texte). */
    public String buildNativeToolsHint() {
        return "\n=== OUTILS (function calling) ===\n"
                + "Des fonctions sont disponibles pour les actions téléphone et les données fraîches.\n"
                + "Utilise-les quand la demande l'exige ; sinon réponds en français naturel.\n"
                + "Ne prétends jamais avoir exécuté une action sans appeler la fonction correspondante.\n"
                + "Pour tout calcul / pourcentage / marge : appelle calculator — ne calcule jamais de tête.\n";
    }

    /** Section complète (tous les outils) — tests et fallback. */
    public String buildPromptSection() {
        return buildPromptSection(EnumSet.allOf(ToolTag.class));
    }

    /**
     * Section à injecter dans le prompt système — filtrée selon {@link ContextIntent#allowedTools}.
     */
    public String buildPromptSection(EnumSet<ToolTag> allowed) {
        EnumSet<ToolTag> tags = allowed != null && !allowed.isEmpty()
                ? allowed : EnumSet.allOf(ToolTag.class);

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== OUTILS DISPONIBLES ===\n");
        sb.append("DÉFAUT tool_choice none : réponds en français sans JSON ; n'émet un outil que si la demande exige une action système ou une donnée externe fraîche.\n");
        sb.append("INTERDICTION : Ne répète jamais la description de l'outil dans ta réponse.\n");
        sb.append("RÈGLE D'OR : Si tu décides d'utiliser un outil, ta réponse doit être UNIQUEMENT le bloc JSON brut, sans aucun texte explicatif avant ou après.\n");
        sb.append("JAMAIS de JSON tronqué ni de prose mélangée au JSON — soit 100 % JSON, soit 100 % français.\n");
        sb.append("Si tu ne trouves pas d'outil adapté, réponds normalement en français.\n");
        sb.append("\nFormat obligatoire : {\"tool\":\"id\",\"params\":{...}}\n");
        sb.append("\nListe des outils disponibles (ID et signature) :\n");
        for (Tool t : tools) {
            if (tags.contains(t.tag())) {
                sb.append("  • ").append(t.description()).append("\n");
            }
        }
        if (tags.contains(ToolTag.COMPOSITE)) {
            sb.append("  • ").append(compositeTool.description()).append("\n");
        }
        appendExamples(sb, tags);
        return sb.toString();
    }

    private static void appendExamples(StringBuilder sb, EnumSet<ToolTag> tags) {
        sb.append("\nExemples de réponses attendues :\n");
        if (tags.contains(ToolTag.WEATHER)) {
            sb.append("  Météo     → {\"tool\":\"weather\",\"params\":{\"days\":2}} "
                    + "(sans city : GPS des paramètres)\n");
            sb.append("  Météo autre → {\"tool\":\"weather\",\"params\":{\"city\":\"Lyon\",\"days\":1}}\n");
        }
        if (tags.contains(ToolTag.SEARCH)) {
            sb.append("  Tavily    → {\"tool\":\"search\",\"params\":{\"query\":\"prix bitcoin actuel\","
                    + "\"question\":\"C'est combien le bitcoin aujourd'hui ?\"}} "
                    + "(actualité / prix / scores — plusieurs sources)\n");
            sb.append("  Foot/sport→ {\"tool\":\"search\",\"params\":{\"query\":\"PSG dernier match résultat\","
                    + "\"question\":\"Quel est le dernier match du PSG ?\"}}\n");
        }
        if (tags.contains(ToolTag.F1)) {
            sb.append("  F1        → {\"tool\":\"f1\",\"params\":{\"action\":\"debrief\",\"mode\":\"quick\"}} "
                    + "(« tu en as pensé quoi du GP », débrief, stratégie — fiche OpenF1, pas search)\n");
            sb.append("  F1 refresh→ {\"tool\":\"f1\",\"params\":{\"action\":\"refresh\"}}\n");
        }
        if (tags.contains(ToolTag.WIKIPEDIA)) {
            sb.append("  Wikipedia → {\"tool\":\"wikipedia\",\"params\":{\"query\":\"coefficient de restitution\"}} "
                    + "(définitions / concepts — 0 clé, préfère à search)\n");
        }
        if (tags.contains(ToolTag.WIKIDATA)) {
            sb.append("  Wikidata  → {\"tool\":\"wikidata\",\"params\":{\"query\":\"HTML\"}} "
                    + "(« qui a inventé », entités — 0 clé)\n");
        }
        if (tags.contains(ToolTag.CALCULATOR)) {
            sb.append("  Calcul    → {\"tool\":\"calculator\",\"params\":{\"expression\":\"12×4\"}} "
                    + "ou expression:\"50 marge 36%\" — ne calcule JAMAIS de tête\n");
        }
        if (tags.contains(ToolTag.WEB_SEARCH)) {
            sb.append("  Navigateur→ {\"tool\":\"web_search\",\"params\":{\"query\":\"Piper TTS\"}} "
                    + "(ouvre Google — seulement si l'utilisateur veut naviguer lui-même)\n");
        }
        if (tags.contains(ToolTag.NASA)) {
            sb.append("  NASA      → {\"tool\":\"nasa\",\"params\":{}}\n");
        }
        if (tags.contains(ToolTag.NOTEPAD)) {
            sb.append("  Liste     → {\"tool\":\"notepad\",\"params\":{\"action\":\"add\",\"text\":\"acheter du lait\"}} "
                    + "ou action:\"list\" pour lire la liste\n");
        }
        if (tags.contains(ToolTag.NOTIFICATIONS)) {
            sb.append("  Notifs    → {\"tool\":\"notifications\",\"params\":{\"action\":\"list\"}} "
                    + "ou action:\"open\", index:1 ou app:\"WhatsApp\" ; dismiss / dismiss_all\n");
        }
        if (tags.contains(ToolTag.YOUTUBE)) {
            sb.append("  YouTube   → {\"tool\":\"youtube\",\"params\":{\"action\":\"search\",\"query\":\"tutos Python\"}}\n");
        }
        if (tags.contains(ToolTag.MEMORY)) {
            sb.append("  Mémoire   → {\"tool\":\"memory\",\"params\":{\"action\":\"add\",\"text\":\"ma ville est Lyon\"}}\n");
        }
        if (tags.contains(ToolTag.DIAG)) {
            sb.append("  Diag      → {\"tool\":\"diag\",\"params\":{\"action\":\"summary\"}} "
                    + "(« comment tu vas ») ; weekly → "
                    + "{\"tool\":\"diag\",\"params\":{\"action\":\"weekly\",\"days\":7}} ; "
                    + "search → {\"tool\":\"diag\",\"params\":{\"action\":\"search\","
                    + "\"query\":\"hésitation notepad projet\"}} "
                    + "(« tu as déjà eu ce problème », « première fois », « ça arrive souvent ») ; "
                    + "analyze → {\"tool\":\"diag\",\"params\":{\"action\":\"analyze\"}} "
                    + "(« analyse tes problèmes », QA Markdown + note bureau)\n");
        }
        if (tags.contains(ToolTag.BRIEF)) {
            sb.append("  Brief     → {\"tool\":\"brief\",\"params\":{\"action\":\"brief\"}} "
                    + "(« brief du matin », « qu'est-ce que j'ai aujourd'hui ») ; "
                    + "add → {\"tool\":\"brief\",\"params\":{\"action\":\"add\","
                    + "\"utterance\":\"ajoute à ma routine du matin : cherche F1\"}}\n");
        }
        if (tags.contains(ToolTag.ORION_MANAGER)) {
            sb.append("  Orion     → {\"tool\":\"orion_manager\",\"params\":{\"action\":\"start\"}} "
                    + "(« lance Orion » — confirmera avec le coût) ; "
                    + "stop → {\"tool\":\"orion_manager\",\"params\":{\"action\":\"stop\"}} ; "
                    + "status → {\"tool\":\"orion_manager\",\"params\":{\"action\":\"status\"}}\n");
        }
        if (tags.contains(ToolTag.ORION_CODE)) {
            sb.append("  OrionCode → {\"tool\":\"orion_code\",\"params\":{\"prompt\":"
                    + "\"écris une fonction Kotlin qui parse du JSON\"}} "
                    + "(« demande à Orion », « code moi », « génère », « écris une fonction »)\n");
            sb.append("  OrionFiles → {\"tool\":\"orion_files\",\"params\":{\"action\":"
                    + "\"validate_all\"}} "
                    + "(« valide tout », « montre les fichiers Orion », « vide la session », "
                    + "« committe tout » → action:\"commit_all\")\n");
            sb.append("  OrionProject → {\"tool\":\"orion_project\",\"params\":{\"action\":"
                    + "\"switch\",\"name\":\"balle-html\"}} "
                    + "(« nouveau projet », « passe sur le projet », « push le projet », "
                    + "« fichiers du projet » — local = vérité, jamais push auto)\n");
        }
        if (tags.contains(ToolTag.GIT_COMMIT)) {
            sb.append("  GitCommit → {\"tool\":\"git_commit\",\"params\":{\"action\":\"commit\","
                    + "\"session\":true,\"message\":\"feat: …\"}} "
                    + "ou local_file / files:[{path,content}] ; 1 commit multi via Git Trees ; "
                    + "sans repo → choix dépôt / créer "
                    + "(« committe tout », « pousse sur GitHub »)\n");
        }
        if (tags.contains(ToolTag.NAMED_CONTEXT)) {
            sb.append("  Contexte  → {\"tool\":\"named_context\",\"params\":{\"action\":\"load\",\"name\":\"Orion\"}} "
                    + "; search → {\"tool\":\"named_context\",\"params\":{\"action\":\"search\",\"query\":\"Tavily\"}}\n");
        }
        if (tags.contains(ToolTag.ALARM)) {
            sb.append("  Réveil    → {\"tool\":\"alarm\",\"params\":{\"action\":\"add\","
                    + "\"hour\":10,\"minute\":0,\"label\":\"Chauffer les plats\"}} "
                    + "✅ ; list / recent\n");
        }
        if (tags.contains(ToolTag.TIMER)) {
            sb.append("  Minuteur  → {\"tool\":\"timer\",\"params\":{\"action\":\"start\","
                    + "\"duration\":\"5 minutes\",\"label\":\"Pizza\"}} "
                    + "✅ ; list / recent\n");
        }
        if (tags.contains(ToolTag.AGENDA) || tags.contains(ToolTag.CALENDAR)) {
            sb.append("  Agenda    → add : {\"tool\":\"agenda\",\"params\":{\"title\":\"Buffet\","
                    + "\"start\":\"demain 11:30\"}} ; "
                    + "today / tomorrow / week / delete ; silent:true si permission\n");
        }
        if (tags.contains(ToolTag.DEVICE)) {
            sb.append("  Téléphone → {\"tool\":\"device\",\"params\":{\"action\":\"battery\"}} "
                    + "ou action:\"time\" / \"date\"\n");
        }
        if (tags.contains(ToolTag.UI)) {
            sb.append("  UI copilote→ click seul : {\"tool\":\"ui_action\",\"params\":"
                    + "{\"action\":\"click\",\"target\":\"Astronomie et espace\"}} "
                    + "(libellé visible, jamais viewId) ; type/scroll/back. "
                    + "Phrase multi (ouvre+clique+tape) : UNE ui_action avec "
                    + "steps:[{action:\"open\",name:\"Chrome\"},"
                    + "{action:\"click\",target:\"barre d'adresse\"},"
                    + "{action:\"type\",value:\"Wikipedia\"}] — name=libellé app "
                    + "(pas package com.xxx), pas N appels, pas open_app seul. "
                    + "ui_explain / ui_search sur élément visible\n");
            sb.append("  Cursor micro→ {\"tool\":\"copilot_action\",\"params\":"
                    + "{\"action\":\"cursor_mic\"}} ou ui_action click target micro "
                    + "(page cursor.com)\n");
        }
        if (tags.contains(ToolTag.LIFE_PATTERN)) {
            sb.append("  Rythme    → {\"tool\":\"life_pattern\",\"params\":{\"action\":\"add\","
                    + "\"utterance\":\"ajoute un rythme ménage de 18h30 à 19h45\"}}\n");
        }
        if (tags.contains(ToolTag.PROJECT_OBJECT)) {
            sb.append("  Fiche projet→ {\"tool\":\"project_object\",\"params\":{\"action\":\"add\","
                    + "\"label\":\"cuisine\",\"status\":\"en cours\"}}\n");
        }
        if (tags.contains(ToolTag.NAVIGATION)) {
            sb.append("  Navigation→ {\"tool\":\"navigation\",\"params\":{\"destination\":\"Lyon\"}}\n");
        }
        if (tags.contains(ToolTag.FLASHLIGHT)) {
            sb.append("  Lampe     → {\"tool\":\"flashlight\",\"params\":{\"action\":\"on\"}}\n");
        }
        if (tags.contains(ToolTag.CALL)) {
            sb.append("  Appel     → {\"tool\":\"call\",\"params\":{\"contact\":\"maman\"}}\n");
        }
        if (tags.contains(ToolTag.CONNECTIVITY)) {
            sb.append("  Wi-Fi     → {\"tool\":\"connectivity\",\"params\":{\"target\":\"wifi\",\"action\":\"status\"}}\n");
        }
        if (tags.contains(ToolTag.SMS)) {
            sb.append("  SMS       → {\"tool\":\"sms\",\"params\":{\"message\":\"J'arrive dans 10 min\"}}\n");
        }
        if (tags.contains(ToolTag.EMAIL)) {
            sb.append("  E-mail    → {\"tool\":\"email\",\"params\":{\"to\":\"a@b.fr\",\"subject\":\"Salut\",\"body\":\"…\"}}\n");
        }
        if (tags.contains(ToolTag.SHARE)) {
            sb.append("  Partage   → {\"tool\":\"share\",\"params\":{\"text\":\"Voici le récap.\"}}\n");
        }
        if (tags.contains(ToolTag.VOLUME)) {
            sb.append("  Volume    → {\"tool\":\"volume\",\"params\":{\"action\":\"mute\"}} "
                    + "ou action:\"up\" / \"down\" / \"status\"\n");
        }
        if (tags.contains(ToolTag.SETTINGS)) {
            sb.append("  Réglages  → {\"tool\":\"settings\",\"params\":{\"panel\":\"airplane\"}} "
                    + "(hotspot / brightness / sound)\n");
        }
        if (tags.contains(ToolTag.CLIPBOARD)) {
            sb.append("  Presse-papiers → {\"tool\":\"clipboard\",\"params\":{\"action\":\"get\"}} "
                    + "; set → {\"tool\":\"clipboard\",\"params\":{\"action\":\"set\",\"text\":\"…\"}}\n");
        }
        if (tags.contains(ToolTag.CONTACTS)) {
            sb.append("  Contacts  → {\"tool\":\"contacts\",\"params\":{\"action\":\"search\",\"query\":\"maman\"}} "
                    + "; call / sms avec query\n");
        }
        if (tags.contains(ToolTag.FILES)) {
            sb.append("  Fichiers  → {\"tool\":\"files\",\"params\":{\"action\":\"search\",\"query\":\"facture\"}} "
                    + "; list folder:downloads ; open ; move destination:documents ; "
                    + "delete (confirm + corbeille)\n");
        }
        if (tags.contains(ToolTag.COMPOSITE)) {
            sb.append("  Routine   → composite selon l'heure si l'utilisateur dit « comme d'habitude »\n");
        }
    }
}
