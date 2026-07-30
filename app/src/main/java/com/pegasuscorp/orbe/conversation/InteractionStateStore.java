package com.pegasuscorp.orbe.conversation;

import android.content.Context;

import com.pegasuscorp.orbe.memory.UserProfileStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * État d'interaction : humeur, mémoire émotionnelle légère, running gags.
 */
public final class InteractionStateStore {

    private static final String GAG_NEW_IDEA = "new_idea";

    private static InteractionStateStore instance;

    private final File stateFile;
    private final Context appContext;
    private JSONObject root = new JSONObject();

    private InteractionStateStore(Context context) {
        appContext = context.getApplicationContext();
        File memoryDir = new File(appContext.getFilesDir(), "memory");
        if (!memoryDir.exists()) memoryDir.mkdirs();
        stateFile = new File(memoryDir, "interaction_state.json");
        load();
    }

    public static synchronized InteractionStateStore getInstance(Context context) {
        if (instance == null) {
            instance = new InteractionStateStore(context);
        }
        return instance;
    }

    public InteractionMood getMood() {
        return InteractionMood.fromString(root.optString("mood", "NORMAL"));
    }

    public void setMood(InteractionMood mood) {
        try {
            root.put("mood", mood.name());
            save();
        } catch (Exception ignored) {}
    }

    public void onUserMessage(String transcript) {
        if (transcript == null) return;
        String t = transcript.toLowerCase(Locale.ROOT);
        String name = profileFirstName();

        if (containsAny(t, "ça marche", "ca marche", "enfin", "yes", "génial", "genial", "top ")) {
            setMood(InteractionMood.CONTENT);
            setEmotionalNote(name + " vient de réussir quelque chose et semble content.");
        } else if (containsAny(t, "fatigué", "fatigue", "creve", "crevé", "épuisé", "epuise")) {
            setEmotionalNote(name + " semble fatigué aujourd'hui.");
            setMood(InteractionMood.NORMAL);
        } else if (containsAny(t, "idée", "idee", "projet")) {
            if (containsAny(t, "encore une idée", "j'ai une idée", "j ai une idée", "nouvelle idée")) {
                incrementGag(GAG_NEW_IDEA);
            }
            if (getMood() != InteractionMood.CONTENT) {
                setMood(InteractionMood.JOUEUR);
            }
        } else if (containsAny(t, "code", "bug", "debug", "compile")) {
            setMood(InteractionMood.CONCENTRE);
        } else if (t.contains("?")) {
            setMood(InteractionMood.REFLEXION);
        }

        try {
            root.put("lastUserAt", today());
            save();
        } catch (Exception ignored) {}
    }

    public String pickGreeting(String userName) {
        String name = firstName(userName);
        List<String> options = new ArrayList<>();

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 6 && hour < 12) {
            options.add("Bonjour " + name + ".");
            options.add("Belle matinée, " + name + ".");
            options.add("Salut " + name + ", prêt pour la journée ?");
            options.add("Bonjour ! Qu'est-ce qu'on attaque ce matin ?");
        } else if (hour >= 12 && hour < 18) {
            options.add("Salut " + name + ".");
            options.add("Bon après-midi, " + name + ".");
            options.add("Re, " + name + ". Qu'est-ce qu'il te faut ?");
            options.add("Salut ! Je suis là si tu as besoin de moi.");
        } else if (hour >= 18 && hour < 23) {
            options.add("Bonsoir " + name + ".");
            options.add("Salut " + name + ", belle soirée ?");
            options.add("Bonsoir ! On fait quoi ce soir ?");
            options.add("Re, " + name + ". Je t'écoute.");
        } else {
            options.add("Encore debout, " + name + " ?");
            options.add("Salut " + name + ", c'est tard non ?");
            options.add("Je suis là, même à cette heure.");
            options.add("Bonsoir " + name + ".");
        }

        switch (getMood()) {
            case JOUEUR:
                options.add("Alors " + name + ", quelle idée en tête ?");
                options.add("Tiens, te revoilà. Qu'est-ce qu'on invente ?");
                options.add("Salut ! Prêt pour un petit délire ?");
                break;
            case CONCENTRE:
                options.add("Salut " + name + ". On code, on debug, ou autre chose ?");
                options.add("Je suis prêt. Dis-moi ce qu'il faut.");
                options.add("Ok, mode efficacité. Vas-y.");
                break;
            case REFLEXION:
                options.add("Salut " + name + ". Une question en tête ?");
                options.add("Je t'écoute, prends ton temps.");
                options.add("On réfléchit ensemble ?");
                break;
            case CONTENT:
                options.add("Content de te revoir, " + name + " !");
                options.add("Salut " + name + " ! Belle énergie aujourd'hui.");
                options.add("Hey ! Ça a l'air d'aller, non ?");
                break;
            case NORMAL:
            default:
                options.add("Salut " + name + ", qu'est-ce qu'on fait ?");
                options.add("Rebonjour " + name + ".");
                options.add("Tiens, te voilà. Je t'écoute.");
                options.add("Hello " + name + " !");
                options.add("Oui, je suis là. Vas-y.");
                options.add("Alors, " + name + " ?");
                options.add("Je t'écoute, " + name + ".");
                break;
        }

        return pickAvoidingRepeat(options, "lastGreetingIndex");
    }

    /** Réponse courte après le mot d'éveil, avant d'ouvrir le micro. */
    public String pickWakeAck(boolean locked) {
        List<String> options = new ArrayList<>();
        if (locked) {
            options.add("Oui, je t'écoute.");
            options.add("Je suis là.");
            options.add("Oui, vas-y.");
            options.add("D'accord, je t'écoute.");
        } else {
            options.add("Oui ?");
            options.add("Je t'écoute.");
            options.add("Dis-moi.");
            options.add("Oui, vas-y.");
            options.add("Je suis là.");
        }
        return pickAvoidingRepeat(options, "lastWakeAckIndex");
    }

    private String pickAvoidingRepeat(List<String> options, String indexKey) {
        if (options.isEmpty()) return "Je t'écoute.";
        int last = root.optInt(indexKey, -1);
        int idx;
        do {
            idx = ThreadLocalRandom.current().nextInt(options.size());
        } while (idx == last && options.size() > 1);
        try {
            root.put(indexKey, idx);
            save();
        } catch (Exception ignored) {}
        return options.get(idx);
    }

    private static String firstName(String userName) {
        if (userName == null || userName.trim().isEmpty()) return "toi";
        String trimmed = userName.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    public void appendPromptSection(StringBuilder sb) {
        String name = profileFirstName();
        sb.append("\n--- État d'interaction ---\n");
        sb.append("Humeur actuelle : ").append(moodLabel(getMood())).append("\n");

        String note = root.optString("lastEmotionalNote", "");
        String noteDate = root.optString("lastEmotionalAt", "");
        if (!note.isEmpty()) {
            sb.append("Contexte émotionnel récent");
            if (!noteDate.isEmpty()) sb.append(" (").append(noteDate).append(")");
            sb.append(" : ").append(note).append("\n");
        }

        int ideaGag = getGagCount(GAG_NEW_IDEA);
        if (ideaGag > 0) {
            sb.append("Running gag — ").append(name)
                    .append(" annonce souvent une nouvelle idée (")
                    .append(ideaGag).append(" fois). ");
            if (ideaGag >= 12) {
                sb.append("Tu peux répondre très court et complice, par ex. « …évidemment. » "
                        + "ou une référence à une « catégorie spéciale » pour ses idées.\n");
            } else if (ideaGag >= 5) {
                sb.append("Tu peux glisser une petite référence humoristique à ses idées en rafale.\n");
            } else {
                sb.append("Reste naturel pour l'instant.\n");
            }
        }

        sb.append("Objectif principal : rendre la vie de ").append(name)
                .append(" plus agréable — pas seulement répondre.\n");
        sb.append("Tu peux parfois proposer de noter une idée, rappeler un projet, "
                + "ou faire une remarque bienveillante non demandée.\n");
        sb.append("Reste une présence attachante : chaleureux sans être sirupeux, "
                + "complice sans être collant.\n");
    }

    private String profileFirstName() {
        return firstName(UserProfileStore.getInstance(appContext).getUserName());
    }

    private void setEmotionalNote(String note) {
        try {
            root.put("lastEmotionalNote", note);
            root.put("lastEmotionalAt", today());
            save();
        } catch (Exception ignored) {}
    }

    private int getGagCount(String key) {
        JSONObject gags = root.optJSONObject("gags");
        if (gags == null) return 0;
        return gags.optInt(key, 0);
    }

    private void incrementGag(String key) {
        try {
            JSONObject gags = root.optJSONObject("gags");
            if (gags == null) gags = new JSONObject();
            gags.put(key, gags.optInt(key, 0) + 1);
            root.put("gags", gags);
            save();
        } catch (Exception ignored) {}
    }

    private static String moodLabel(InteractionMood mood) {
        switch (mood) {
            case JOUEUR: return "joueur (taquin, léger)";
            case CONCENTRE: return "concentré (précis, efficace)";
            case REFLEXION: return "réflexion (pose des questions avant de conclure)";
            case CONTENT: return "content (chaleureux, célèbre les petites victoires)";
            case NORMAL:
            default: return "normal (naturel, complice)";
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    private void load() {
        if (!stateFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(stateFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            root = new JSONObject(sb.toString());
        } catch (Exception e) {
            root = new JSONObject();
        }
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(stateFile)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
