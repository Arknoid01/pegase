package com.pegasuscorp.orbe.memory;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.chat.ProviderTraceSink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte et applique les corrections de mémoire demandées à la voix.
 */
public final class MemoryEditor {

    public interface Callback {
        void onResult(MemoryEditResult result);
    }

    private static final Pattern RETIENS = Pattern.compile(
            "(?i)(?:retiens|souviens[- ]toi|note)\\s+que\\s+(.+)");
    private static final Pattern OUBLIE = Pattern.compile(
            "(?i)(?:oublie|supprime|efface)\\s+(?:que\\s+)?(.+)");

    private final Context appContext;
    private final MemoryRepository memory;
    private final UserProfileStore profile;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public MemoryEditor(Context context) {
        appContext = context.getApplicationContext();
        memory = MemoryRepository.getInstance(appContext);
        profile = UserProfileStore.getInstance(appContext);
    }

    public static boolean looksLikeMemoryEdit(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return RETIENS.matcher(text).find()
                || OUBLIE.matcher(text).find()
                || t.contains("corrige")
                || t.contains("modifie")
                || t.contains("mets à jour")
                || t.contains("met a jour")
                || t.contains("mets dans ma mémoire")
                || t.contains("met dans ma memoire")
                || t.contains("dans ma mémoire")
                || t.contains("dans ma memoire")
                || t.contains("ce n'est pas")
                || t.contains("n'est pas un")
                || t.contains("n'est pas une");
    }

    public void process(String userText, Callback callback) {
        io.execute(() -> {
            MemoryEditResult quick = tryQuickEdit(userText);
            if (quick != null) {
                main.post(() -> callback.onResult(quick));
                return;
            }
            resolveWithLlm(userText, callback);
        });
    }

    private MemoryEditResult tryQuickEdit(String text) {
        Matcher m = RETIENS.matcher(text.trim());
        if (m.find()) {
            String fact = m.group(1).trim();
            if (fact.isEmpty()) return null;
            memory.addPermanentMemory(new MemoryEntry(
                    "user", fact, 0.85, today()));
            String shortFact = truncate(fact, 50);
            return MemoryEditResult.applied(
                    "Mémoire : " + shortFact,
                    "C'est noté, je retiens que " + fact);
        }

        m = OUBLIE.matcher(text.trim());
        if (m.find()) {
            String query = m.group(1).trim();
            if (query.isEmpty()) return null;
            int removed = memory.removePermanentContaining(query);
            if (removed > 0) {
                return MemoryEditResult.applied(
                        removed + " souvenir(s) supprimé(s)",
                        "D'accord, j'ai oublié ça.");
            }
            return MemoryEditResult.failed(
                    "Je n'ai rien trouvé à supprimer pour ça.");
        }
        return null;
    }

    private void resolveWithLlm(String userText, Callback callback) {
        String prompt =
                "L'utilisateur veut corriger ou mettre à jour sa mémoire personnelle.\n"
                + "Mémoire actuelle :\n"
                + buildMemorySnapshot()
                + "\nDemande : \"" + userText + "\"\n\n"
                + "Réponds UNIQUEMENT en JSON valide, sans markdown, avec :\n"
                + "- action : add_permanent | remove_permanent | replace_text | add_note | add_project | none\n"
                + "- summary : phrase courte pour confirmation à l'écran (max 60 car.)\n"
                + "- spoken : réponse orale courte et naturelle en français\n"
                + "- category : catégorie si add_permanent (ex. user, project, preference)\n"
                + "- content : texte à ajouter en souvenir permanent\n"
                + "- search : texte exact ou fragment à chercher pour supprimer/remplacer\n"
                + "- replacement : nouveau texte si replace_text\n"
                + "- note : texte à ajouter dans notes_for_pegase si add_note\n"
                + "- project : nom de projet à ajouter si add_project\n"
                + "Utilise replace_text pour corriger une info erronée dans le profil ou les souvenirs.\n"
                + "Utilise none si ce n'est pas une demande de modification mémoire.";

        ChatBackend backend = ChatBackendFactory.create(appContext);
        backend.send(java.util.Collections.emptyList(), prompt, new ChatBackend.OnReply() {
            @Override
            public void onReply(String text) {
                consumeProviderTrace(backend);
                MemoryEditResult result = applyLlmPlan(text);
                main.post(() -> callback.onResult(result));
            }

            @Override
            public void onError(String error) {
                discardProviderTrace(backend);
                main.post(() -> callback.onResult(
                        MemoryEditResult.failed("Je n'ai pas pu modifier la mémoire.")));
            }
        });
    }

    private static void consumeProviderTrace(ChatBackend backend) {
        if (backend instanceof ProviderTraceSink) {
            ((ProviderTraceSink) backend).consumePendingProviderTrace();
        }
    }

    private static void discardProviderTrace(ChatBackend backend) {
        if (backend instanceof ProviderTraceSink) {
            ((ProviderTraceSink) backend).discardPendingProviderTrace();
        }
    }

    private MemoryEditResult applyLlmPlan(String raw) {
        JSONObject plan = extractJson(raw);
        if (plan == null) {
            return MemoryEditResult.notMemoryEdit();
        }

        String action = plan.optString("action", "none");
        String summary = plan.optString("summary", "Mémoire mise à jour");
        String spoken = plan.optString("spoken", "C'est noté.");

        if ("none".equals(action)) {
            return MemoryEditResult.notMemoryEdit();
        }

        switch (action) {
            case "add_permanent": {
                String content = plan.optString("content", "").trim();
                if (content.isEmpty()) return MemoryEditResult.notMemoryEdit();
                String category = plan.optString("category", "user");
                memory.addPermanentMemory(new MemoryEntry(category, content, 0.85, today()));
                return MemoryEditResult.applied(summary, spoken);
            }
            case "remove_permanent": {
                String search = plan.optString("search", "").trim();
                if (search.isEmpty()) return MemoryEditResult.failed("Je n'ai pas compris quoi supprimer.");
                int n = memory.removePermanentContaining(search);
                if (n == 0) return MemoryEditResult.failed("Je n'ai rien trouvé à supprimer.");
                return MemoryEditResult.applied(summary, spoken);
            }
            case "replace_text": {
                String search = plan.optString("search", "").trim();
                String replacement = plan.optString("replacement", "").trim();
                if (search.isEmpty()) return MemoryEditResult.failed("Je n'ai pas compris quoi corriger.");
                boolean profileChanged = profile.replaceText(search, replacement);
                int permChanged = memory.replaceInPermanent(search, replacement);
                if (!profileChanged && permChanged == 0) {
                    return MemoryEditResult.failed("Je n'ai pas trouvé cette information à corriger.");
                }
                return MemoryEditResult.applied(summary, spoken);
            }
            case "add_note": {
                String note = plan.optString("note", plan.optString("content", "")).trim();
                if (note.isEmpty()) return MemoryEditResult.notMemoryEdit();
                profile.addNote(note);
                return MemoryEditResult.applied(summary, spoken);
            }
            case "add_project": {
                String project = plan.optString("project", plan.optString("content", "")).trim();
                if (project.isEmpty()) return MemoryEditResult.notMemoryEdit();
                profile.addProject(project);
                return MemoryEditResult.applied(summary, spoken);
            }
            default:
                return MemoryEditResult.notMemoryEdit();
        }
    }

    private String buildMemorySnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("Profil :\n").append(profile.snapshotForEdit()).append("\n");
        List<MemoryEntry> all = memory.getAllPermanentMemories();
        if (!all.isEmpty()) {
            sb.append("Souvenirs permanents :\n");
            for (MemoryEntry e : all) {
                sb.append("- [").append(e.category).append("] ").append(e.content).append("\n");
            }
        }
        return sb.toString();
    }

    private static JSONObject extractJson(String text) {
        if (text == null) return null;
        String json = text.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(json.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
