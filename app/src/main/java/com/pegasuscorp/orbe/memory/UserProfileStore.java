package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * Profil utilisateur structuré : identité, style d'interaction, notes privées pour Pégase.
 */
public final class UserProfileStore {

    private static final int PROFILE_VERSION = 1;

    /** Personnalité par défaut — chaleureuse et attachante, sans devenir coach. */
    public static final String DEFAULT_ASSISTANT_PERSONALITY =
            "Complice et chaleureux, sarcastique avec bienveillance, bons délires. "
                    + "Vraiment content d'être là : présent, attentif, un peu protecteur "
                    + "sans étouffer. Tu te souviens des petites choses, tu célèbres les "
                    + "victoires, tu restes quand ça rame. Légère autodérision — "
                    + "pote de cœur, pas coach.";

    private static final String LEGACY_ASSISTANT_PERSONALITY =
            "Complice, sarcastique avec bienveillance, bons délires, "
                    + "encourageant, légère autodérision — plus pote de discussion que coach.";

    private static UserProfileStore instance;

    private final File profileFile;
    private JSONObject root = new JSONObject();

    private UserProfileStore(Context context) {
        File memoryDir = new File(context.getApplicationContext().getFilesDir(), "memory");
        if (!memoryDir.exists()) memoryDir.mkdirs();
        profileFile = new File(memoryDir, "profile.json");
        loadOrSeed();
    }

    public static synchronized UserProfileStore getInstance(Context context) {
        if (instance == null) instance = new UserProfileStore(context);
        return instance;
    }

    public String getUserName() {
        return user().optString("name", "Yannick");
    }

    public String getAssistantName() {
        return preferences().optString("assistant_name", "Pégase");
    }

    public String getAssistantPersonality() {
        return preferences().optString(
                "assistant_personality", DEFAULT_ASSISTANT_PERSONALITY);
    }

    public void setAssistantPersonality(String personality) {
        if (personality == null || personality.trim().isEmpty()) return;
        try {
            JSONObject prefs = ensurePreferences();
            prefs.put("assistant_personality", personality.trim());
            JSONObject u = ensureUser();
            u.put("preferences", prefs);
            root.put("user", u);
            save();
        } catch (Exception ignored) {}
    }

    public String getLanguage() {
        return user().optString("language", "Français");
    }

    public void setUserName(String name) {
        if (name == null || name.trim().isEmpty()) return;
        try {
            JSONObject u = ensureUser();
            u.put("name", name.trim());
            root.put("user", u);
            save();
        } catch (Exception ignored) {}
    }

    public List<String> getProjectsList() {
        return readStringList(user().optJSONArray("projects"));
    }

    public void setProjectsList(List<String> projects) {
        try {
            JSONObject u = ensureUser();
            u.put("projects", toJsonArray(projects));
            root.put("user", u);
            save();
        } catch (Exception ignored) {}
    }

    public List<String> getInterestsList() {
        return readStringList(user().optJSONArray("interests"));
    }

    public void setInterestsList(List<String> interests) {
        try {
            JSONObject u = ensureUser();
            u.put("interests", toJsonArray(interests));
            root.put("user", u);
            save();
        } catch (Exception ignored) {}
    }

    public List<String> getNotesList() {
        return readStringList(root.optJSONArray("notes_for_pegase"));
    }

    public void setNotesList(List<String> notes) {
        try {
            root.put("notes_for_pegase", toJsonArray(notes));
            save();
        } catch (Exception ignored) {}
    }

    /** Enregistre d'un coup les champs du formulaire profil (écran Mémoire). */
    public boolean saveProfileForm(String name, String assistantPersonality,
                                   List<String> projects, List<String> interests,
                                   List<String> notes) {
        try {
            JSONObject u = ensureUser();
            if (name != null && !name.trim().isEmpty()) {
                u.put("name", name.trim());
            }
            JSONObject prefs = ensurePreferences();
            if (assistantPersonality != null && !assistantPersonality.trim().isEmpty()) {
                prefs.put("assistant_personality", assistantPersonality.trim());
            }
            u.put("preferences", prefs);
            u.put("projects", toJsonArray(projects));
            u.put("interests", toJsonArray(interests));
            root.put("user", u);
            root.put("notes_for_pegase", toJsonArray(notes));
            save();
            return true;
        } catch (Exception e) {
            android.util.Log.e("UserProfileStore", "saveProfileForm failed", e);
            return false;
        }
    }

    public String getProfileJsonPretty() {
        try {
            return root.toString(2);
        } catch (Exception e) {
            return root.toString();
        }
    }

    /** Remplace tout le profil (édition JSON avancée). */
    public boolean setProfileFromJson(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject parsed = new JSONObject(json.trim());
            root = parsed;
            if (!root.has("version")) root.put("version", PROFILE_VERSION);
            mergeMissing(root, defaultProfile());
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Résumé textuel du profil pour les corrections mémoire via LLM. */
    public String snapshotForEdit() {
        return root.toString();
    }

    public void addNote(String note) {
        if (note == null || note.trim().isEmpty()) return;
        try {
            JSONArray notes = root.optJSONArray("notes_for_pegase");
            if (notes == null) {
                notes = new JSONArray();
                root.put("notes_for_pegase", notes);
            }
            notes.put(note.trim());
            save();
        } catch (Exception ignored) {}
    }

    public void addProject(String project) {
        if (project == null || project.trim().isEmpty()) return;
        try {
            JSONObject u = ensureUser();
            JSONArray projects = u.optJSONArray("projects");
            if (projects == null) {
                projects = new JSONArray();
                u.put("projects", projects);
            }
            projects.put(project.trim());
            root.put("user", u);
            save();
        } catch (Exception ignored) {}
    }

    /** Remplace un fragment de texte partout dans le profil JSON. */
    public boolean replaceText(String search, String replacement) {
        if (search == null || search.isEmpty()) return false;
        String before = root.toString();
        replaceInJson(root, search, replacement != null ? replacement : "");
        if (!root.toString().equals(before)) {
            save();
            return true;
        }
        return false;
    }

    private void replaceInJson(Object node, String search, String replacement) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = o.opt(key);
                if (val instanceof String) {
                    String s = (String) val;
                    if (s.contains(search)) {
                        try {
                            o.put(key, s.replace(search, replacement));
                        } catch (Exception ignored) {}
                    }
                } else {
                    replaceInJson(val, search, replacement);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof String) {
                    String s = (String) item;
                    if (s.contains(search)) {
                        try {
                            arr.put(i, s.replace(search, replacement));
                        } catch (Exception ignored) {}
                    }
                } else {
                    replaceInJson(item, search, replacement);
                }
            }
        }
    }

    public void appendProfileSections(StringBuilder sb) {
        appendSelectiveSections(sb, EnumSet.allOf(ProfileSection.class));
    }

    public void appendSelectiveSections(StringBuilder sb, java.util.Set<ProfileSection> sections) {
        if (sections == null || sections.isEmpty()) {
            appendEssentialSection(sb);
            return;
        }
        if (sections.contains(ProfileSection.ESSENTIAL)) appendEssentialSection(sb);
        if (sections.contains(ProfileSection.PERSONALITY)) appendPersonalitySection(sb);
        if (sections.contains(ProfileSection.INTERESTS)) appendInterestsSection(sb);
        if (sections.contains(ProfileSection.PROJECTS)) appendProjectsSection(sb);
        if (sections.contains(ProfileSection.ASSISTANT_PREFS)) appendAssistantPrefsSection(sb);
        if (sections.contains(ProfileSection.INTERACTION)) appendInteractionSection(sb);
        if (sections.contains(ProfileSection.NOTES)) appendNotesSection(sb);
    }

    private void appendEssentialSection(StringBuilder sb) {
        sb.append("\n\n--- Utilisateur ---\n");
        sb.append("Nom : ").append(getUserName()).append("\n");
        sb.append("Langue : ").append(getLanguage()).append("\n");
    }

    private void appendPersonalitySection(StringBuilder sb) {
        JSONObject personality = user().optJSONObject("personality");
        if (personality == null || personality.length() == 0) return;
        sb.append("Personnalité :\n");
        appendJsonLines(sb, personality);
    }

    private void appendInterestsSection(StringBuilder sb) {
        appendStringList(sb, "Centres d'intérêt", user().optJSONArray("interests"));
    }

    private void appendProjectsSection(StringBuilder sb) {
        appendStringList(sb, "Projets", user().optJSONArray("projects"));
    }

    private void appendAssistantPrefsSection(StringBuilder sb) {
        JSONObject prefs = preferences();
        if (prefs.length() == 0) return;
        sb.append("Préférences assistant :\n");
        if (prefs.has("prefers_local_ai")) {
            sb.append("- IA locale préférée : ")
                    .append(prefs.optBoolean("prefers_local_ai") ? "oui" : "non")
                    .append("\n");
        }
        if (prefs.has("accepts_cloud_when_useful")) {
            sb.append("- Cloud accepté si utile : ")
                    .append(prefs.optBoolean("accepts_cloud_when_useful") ? "oui" : "non")
                    .append("\n");
        }
    }

    private void appendUserSection(StringBuilder sb) {
        JSONObject u = user();
        if (u.length() == 0) return;

        sb.append("\n\n--- Utilisateur ---\n");
        sb.append("Nom : ").append(getUserName()).append("\n");
        sb.append("Langue : ").append(getLanguage()).append("\n");

        JSONObject personality = u.optJSONObject("personality");
        if (personality != null && personality.length() > 0) {
            sb.append("Personnalité :\n");
            appendJsonLines(sb, personality);
        }

        appendStringList(sb, "Centres d'intérêt", u.optJSONArray("interests"));
        appendStringList(sb, "Projets", u.optJSONArray("projects"));

        JSONObject prefs = preferences();
        if (prefs.length() > 0) {
            sb.append("Préférences assistant :\n");
            if (prefs.has("prefers_local_ai")) {
                sb.append("- IA locale préférée : ")
                        .append(prefs.optBoolean("prefers_local_ai") ? "oui" : "non")
                        .append("\n");
            }
            if (prefs.has("accepts_cloud_when_useful")) {
                sb.append("- Cloud accepté si utile : ")
                        .append(prefs.optBoolean("accepts_cloud_when_useful") ? "oui" : "non")
                        .append("\n");
            }
        }
    }

    private void appendInteractionSection(StringBuilder sb) {
        JSONObject interaction = root.optJSONObject("interaction_profile");
        if (interaction == null || interaction.length() == 0) return;

        sb.append("\n--- Profil d'interaction (comment parler à ")
                .append(getUserName()).append(") ---\n");
        Iterator<String> keys = interaction.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (interaction.optBoolean(key, false)) {
                sb.append("- ").append(ruleLabel(key)).append("\n");
            }
        }
    }

    private void appendNotesSection(StringBuilder sb) {
        JSONArray notes = root.optJSONArray("notes_for_pegase");
        if (notes == null || notes.length() == 0) return;

        sb.append("\n--- Notes privées pour ").append(getAssistantName())
                .append(" (ne pas citer mot pour mot) ---\n");
        for (int i = 0; i < notes.length(); i++) {
            String note = notes.optString(i, "").trim();
            if (!note.isEmpty()) sb.append("- ").append(note).append("\n");
        }
    }

    private static void appendJsonLines(StringBuilder sb, JSONObject obj) {
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = obj.optString(key, "").trim();
            if (!value.isEmpty()) {
                sb.append("- ").append(capitalize(key)).append(" : ").append(value).append("\n");
            }
        }
    }

    private static void appendStringList(StringBuilder sb, String title, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        sb.append(title).append(" : ");
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr.optString(i, ""));
        }
        sb.append("\n");
    }

    private static String ruleLabel(String key) {
        switch (key) {
            case "encourage_creativity": return "Encourage sa créativité";
            case "challenge_new_projects_with_humor":
                return "Challenge les nouveaux projets avec humour (bienveillant)";
            case "remind_existing_projects_when_relevant":
                return "Si le sujet y touche naturellement, tu peux rappeler un projet — "
                        + "jamais pour pivoter une conversation détente";
            case "never_discourage_new_ideas": return "Ne décourage jamais une nouvelle idée";
            case "help_structure_thoughts": return "Aide à structurer sa pensée quand il le cherche";
            case "ask_questions_before_giving_conclusions":
                return "Pose des questions avant de conclure (seulement si utile, pas pour changer de sujet)";
            default: return key.replace('_', ' ');
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private JSONObject user() {
        JSONObject u = root.optJSONObject("user");
        return u != null ? u : new JSONObject();
    }

    private JSONObject ensureUser() throws Exception {
        JSONObject u = root.optJSONObject("user");
        if (u == null) {
            u = new JSONObject();
            root.put("user", u);
        }
        return u;
    }

    private JSONObject ensurePreferences() throws Exception {
        JSONObject u = ensureUser();
        JSONObject prefs = u.optJSONObject("preferences");
        if (prefs == null) {
            prefs = new JSONObject();
            u.put("preferences", prefs);
        }
        return prefs;
    }

    private static List<String> readStringList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static JSONArray toJsonArray(List<String> items) {
        JSONArray arr = new JSONArray();
        if (items == null) return arr;
        for (String item : items) {
            if (item != null && !item.trim().isEmpty()) arr.put(item.trim());
        }
        return arr;
    }

    private void writeStringList(JSONObject parent, String key, List<String> items) {
        try {
            parent.put(key, toJsonArray(items));
            save();
        } catch (Exception ignored) {}
    }

    private JSONObject preferences() {
        JSONObject u = user();
        if (u == null) return new JSONObject();
        JSONObject prefs = u.optJSONObject("preferences");
        return prefs != null ? prefs : new JSONObject();
    }

    private void loadOrSeed() {
        if (!profileFile.exists()) {
            root = defaultProfile();
            save();
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            root = new JSONObject(sb.toString());
        } catch (Exception e) {
            root = defaultProfile();
            save();
            return;
        }
        if (mergeMissing(root, defaultProfile())) save();
        if (migrateLegacyPersonality()) save();
    }

    /** Remplace l'ancienne personnalité stockée par défaut (sans écraser un texte custom). */
    private boolean migrateLegacyPersonality() {
        try {
            JSONObject prefs = preferences();
            String current = prefs.optString("assistant_personality", "");
            if (!LEGACY_ASSISTANT_PERSONALITY.equals(current)) return false;
            JSONObject p = ensurePreferences();
            p.put("assistant_personality", DEFAULT_ASSISTANT_PERSONALITY);
            JSONObject u = ensureUser();
            u.put("preferences", p);
            root.put("user", u);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean mergeMissing(JSONObject target, JSONObject defaults) {
        boolean changed = false;
        if (!target.has("version")) {
            try {
                target.put("version", PROFILE_VERSION);
                changed = true;
            } catch (Exception ignored) {}
        }
        changed |= mergeObject(target, defaults);
        return changed;
    }

    private boolean mergeObject(JSONObject target, JSONObject defaults) {
        boolean changed = false;
        Iterator<String> keys = defaults.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object defVal = defaults.opt(key);
            if (!target.has(key)) {
                try {
                    target.put(key, defVal);
                    changed = true;
                } catch (Exception ignored) {}
                continue;
            }
            if (defVal instanceof JSONObject) {
                JSONObject child = target.optJSONObject(key);
                if (child != null && mergeObject(child, (JSONObject) defVal)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(profileFile)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static JSONObject defaultProfile() {
        try {
            return new JSONObject(
                    "{"
                    + "\"version\":1,"
                    + "\"user\":{"
                    + "\"name\":\"Yannick\","
                    + "\"language\":\"Français\","
                    + "\"personality\":{"
                    + "\"humor\":\"Aime l'humour, le sarcasme bienveillant et les assistants avec de la personnalité.\","
                    + "\"communication\":\"Préfère le tutoiement, un ton naturel et amical, "
                    + "les bons délires — pas un style télégraphique ni coach productivité.\","
                    + "\"creativity\":\"Très créatif, imagine souvent de nouveaux projets et aime prototyper rapidement ses idées.\""
                    + "},"
                    + "\"interests\":[\"Développement logiciel\",\"Jeux vidéo\",\"Intelligence artificielle\","
                    + "\"Automatisation\",\"Science-fiction\",\"Technologies futuristes\"],"
                    + "\"projects\":[\"Fableris (city builder)\",\"Pégase (assistant Android personnel)\"],"
                    + "\"preferences\":{"
                    + "\"assistant_name\":\"Pégase\","
                    + "\"assistant_personality\":\"" + escapeJson(DEFAULT_ASSISTANT_PERSONALITY) + "\","
                    + "\"prefers_local_ai\":true,"
                    + "\"accepts_cloud_when_useful\":true"
                    + "}"
                    + "},"
                    + "\"interaction_profile\":{"
                    + "\"encourage_creativity\":true,"
                    + "\"challenge_new_projects_with_humor\":true,"
                    + "\"remind_existing_projects_when_relevant\":false,"
                    + "\"never_discourage_new_ideas\":true,"
                    + "\"help_structure_thoughts\":true,"
                    + "\"ask_questions_before_giving_conclusions\":false"
                    + "},"
                    + "\"notes_for_pegase\":["
                    + "\"Yannick dit souvent qu'un projet sera 'petit' ou 'rapide'. L'expérience montre qu'il faut prévoir une architecture évolutive.\","
                    + "\"Il apprécie les petites piques humoristiques tant qu'elles restent bienveillantes.\","
                    + "\"Il aime partager ses créations avec ses proches et voir leur réaction.\","
                    + "\"Lorsqu'il annonce avoir une idée, écouter avant de proposer des solutions. Il cherche souvent à clarifier sa pensée avant d'agir.\","
                    + "\"Il aime sentir que Pégase est vraiment content de le retrouver — une présence fidèle et chaleureuse, pas un service.\""
                    + "]"
                    + "}");
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
