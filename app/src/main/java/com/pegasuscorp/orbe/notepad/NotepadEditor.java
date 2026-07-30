package com.pegasuscorp.orbe.notepad;

import android.content.Context;

import com.pegasuscorp.orbe.memory.MemoryEditResult;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ajoute, lit et modifie la liste des choses à faire à la voix.
 */
public final class NotepadEditor {

    public interface Callback {
        void onResult(MemoryEditResult result);
    }

    private static final Pattern ADD_TO_LIST = Pattern.compile(
            "(?i)(?:ajoute|ajouter|mets|met|rajoute|note)\\s+(.+?)\\s+"
                    + "(?:dans|à|a)\\s+(?:ma\\s+)?(?:liste|bloc[- ]?notes?)"
                    + "(?:\\s+(?:des\\s+)?(?:choses\\s+)?à\\s+faire)?\\s*$");

    private static final Pattern ADD_TODO_PHRASE = Pattern.compile(
            "(?i)(?:ajoute|ajouter|mets|met)\\s+(?:à|a)\\s+(?:ma\\s+)?(?:liste\\s+)?"
                    + "(?:des\\s+)?choses\\s+à\\s+faire\\s*[,:]?\\s*(.+)");

    private static final Pattern ADD_NOTEPAD = Pattern.compile(
            "(?i)(?:ajoute|ajouter|mets|met|note)\\s+(?:dans\\s+)?(?:mon\\s+)?"
                    + "bloc[- ]?notes?\\s*[,:]?\\s*(.+)");

    private static final Pattern REMIND = Pattern.compile(
            "(?i)rappelle[- ]moi(?:\\s+(?:demain|aujourd'hui|aujourd hui|dans\\s+\\d+\\s*(?:heure|heures|h)))?"
                    + "(?:\\s+à\\s+\\d{1,2}(?:\\s*h(?:\\s*\\d{2})?)?)?\\s+(?:de\\s+)?(.+)");

    private static final Pattern REMOVE = Pattern.compile(
            "(?i)(?:enlève|enleve|supprime|retire|efface)\\s+(.+?)\\s+"
                    + "(?:de (?:ma|la) liste|du bloc[- ]?notes?)");

    private static final Pattern MARK_DONE = Pattern.compile(
            "(?i)(?:coche|marque)\\s+(.+?)\\s+comme\\s+fait");

    private final NotepadStore store;

    public NotepadEditor(Context context) {
        store = NotepadStore.getInstance(context.getApplicationContext());
    }

    public static boolean looksLikeNotepadEdit(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        String fold = fold(t);
        if (ADD_TO_LIST.matcher(t).find()
                || ADD_TODO_PHRASE.matcher(t).find()
                || ADD_NOTEPAD.matcher(t).find()
                || REMIND.matcher(t).find()
                || REMOVE.matcher(t).find()
                || MARK_DONE.matcher(t).find()) {
            return true;
        }
        return looksLikeListRequest(fold)
                || looksLikeTomorrowRequest(fold)
                || looksLikeClearRequest(fold);
    }

    public void process(String userText, Callback callback) {
        MemoryEditResult result = tryEdit(userText);
        if (result == null) {
            callback.onResult(MemoryEditResult.notMemoryEdit());
            return;
        }
        callback.onResult(result);
    }

    private MemoryEditResult tryEdit(String text) {
        String trimmed = text.trim();
        String fold = fold(trimmed);

        if (looksLikeClearRequest(fold)) {
            int count = store.getActiveItems().size();
            store.clearActive();
            if (count == 0) {
                return MemoryEditResult.applied(null, "Ta liste était déjà vide.");
            }
            return MemoryEditResult.applied(
                    "Liste vidée",
                    "D'accord, j'ai vidé ta liste des choses à faire.");
        }

        if (looksLikeTomorrowRequest(fold)) {
            return MemoryEditResult.applied(
                    "Liste demain",
                    store.formatForTomorrow());
        }

        if (looksLikeListRequest(fold)) {
            return MemoryEditResult.applied(
                    store.getActiveItems().size() + " élément(s)",
                    store.formatForSpeech());
        }

        Matcher m = REMOVE.matcher(trimmed);
        if (m.find()) {
            String query = cleanTail(m.group(1));
            int removed = store.removeContaining(query);
            if (removed > 0) {
                return MemoryEditResult.applied(
                        removed + " élément(s) retiré(s)",
                        "C'est fait, j'ai retiré ça de ta liste.");
            }
            return MemoryEditResult.failed("Je n'ai rien trouvé dans ta liste pour ça.");
        }

        m = MARK_DONE.matcher(trimmed);
        if (m.find()) {
            String query = cleanTail(m.group(1));
            if (store.markDoneContaining(query)) {
                return MemoryEditResult.applied(
                        "Élément coché",
                        "Très bien, je l'ai marqué comme fait.");
            }
            return MemoryEditResult.failed("Je n'ai pas trouvé cet élément dans ta liste.");
        }

        String toAdd = extractAddText(trimmed);
        if (toAdd != null) {
            String due = NotepadDateHelper.parseDueDate(fold);
            int priority = NotepadDateHelper.parsePriority(fold);
            long reminderAt = NotepadDateHelper.parseReminderAtMillis(trimmed, fold);
            if (store.add(toAdd, due, priority, reminderAt)) {
                String shortText = truncate(toAdd, 45);
                StringBuilder reply = new StringBuilder("C'est noté, j'ai ajouté « ")
                        .append(toAdd).append(" »");
                if (!due.isEmpty()) reply.append(" pour ").append(NotepadDateHelper.formatDateLabel(due));
                if (priority > 0) reply.append(" (").append(NotepadDateHelper.priorityLabel(priority)).append(")");
                if (reminderAt > System.currentTimeMillis()) reply.append(", avec rappel");
                reply.append(".");
                return MemoryEditResult.applied("Ajouté : " + shortText, reply.toString());
            }
            return MemoryEditResult.failed("Je n'ai pas compris quoi ajouter.");
        }

        return null;
    }

    private static String extractAddText(String text) {
        Matcher m = ADD_TO_LIST.matcher(text);
        if (m.find()) return cleanTail(m.group(1));
        m = ADD_TODO_PHRASE.matcher(text);
        if (m.find()) return cleanTail(m.group(1));
        m = ADD_NOTEPAD.matcher(text);
        if (m.find()) return cleanTail(m.group(1));
        m = REMIND.matcher(text);
        if (m.find()) return cleanTail(m.group(1));
        return null;
    }

    private static boolean looksLikeTomorrowRequest(String fold) {
        return fold.contains("qu est ce que j ai a faire demain")
                || fold.contains("qu est ce que j ai demain")
                || fold.contains("mes taches de demain")
                || fold.contains("mes tâches de demain")
                || fold.contains("ma liste de demain")
                || fold.contains("quoi demain")
                || fold.contains("pour demain");
    }

    private static boolean looksLikeListRequest(String fold) {
        return fold.contains("ma liste des choses a faire")
                || fold.contains("liste des choses a faire")
                || fold.contains("qu est ce que j ai a faire")
                || fold.contains("qu est ce qu il y a dans ma liste")
                || fold.contains("qu est ce qu il y a dans la liste")
                || fold.contains("lis ma liste")
                || fold.contains("donne ma liste")
                || fold.contains("montre ma liste")
                || fold.contains("affiche ma liste")
                || fold.contains("redis ma liste")
                || fold.contains("repete ma liste")
                || fold.equals("ma liste")
                || fold.equals("mon bloc note")
                || fold.equals("mon bloc-notes")
                || fold.startsWith("quoi dans ma liste");
    }

    private static boolean looksLikeClearRequest(String fold) {
        return fold.contains("vide ma liste")
                || fold.contains("vide la liste")
                || fold.contains("efface ma liste")
                || fold.contains("efface la liste")
                || fold.contains("supprime toute ma liste")
                || fold.contains("supprime tout de ma liste");
    }

    private static String cleanTail(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("[?.!]+$", "").trim();
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).trim() + "…";
    }

    private static String fold(String text) {
        if (text == null) return "";
        String n = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}", "");
        n = n.replace('\'', ' ').replace('’', ' ');
        return n.replaceAll("\\s+", " ").trim();
    }
}
