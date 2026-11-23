package com.pegasuscorp.orbe.tools.memory;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.notepad.NotepadDateHelper;
import com.pegasuscorp.orbe.notepad.NotepadStore;
import com.pegasuscorp.orbe.objects.ProjectObjectStore;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONObject;

/**
 * Bloc-notes : notes / rappels (dueDate ≠ reminderAt).
 */
public final class NotepadTool implements Tool {

    @Override
    public String id() {
        return "notepad";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.NOTEPAD;
    }

    @Override
    public String description() {
        return "notepad(action:add|list|list_history|list_tomorrow|remove|done|clear, "
                + "text:string OBLIGATOIRE pour add, due_date?:yyyy-MM-dd, "
                + "reminder_at?:epoch_ms, projet_tag?:str) — "
                + "Bloc-notes personnel. add UNIQUEMENT sur demande explicite (« note », "
                + "« ajoute », « écris », « rappelle-moi », « mets dans la liste »), text = "
                + "contenu exact ; une simple information n'est jamais notée, dans le doute "
                + "demande confirmation. Rappel sans heure : is_reminder:true (ou due_date) — "
                + "défaut dans 1 h, ou 9 h si un jour est donné. list = résumé actif/proche ; "
                + "list_history = historique ; done / remove / clear. "
                + "Pas de mémoire long terme : utilise memory.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        NotepadStore store = NotepadStore.getInstance(ctx);
        String action = params.optString("action", "list").trim().toLowerCase();

        switch (action) {
            case "list":
                cb.onSuccess(ToolResult.text(store.formatSummary()));
                break;
            case "list_tomorrow":
                cb.onSuccess(ToolResult.text(store.formatForTomorrow()));
                break;
            case "list_history":
            case "history":
                cb.onSuccess(ToolResult.text(store.formatHistorySpeech()));
                break;
            case "add": {
                String text = params.optString("text", "").trim();
                if (text.isEmpty()) {
                    cb.onError("Je n'ai pas reçu le texte à ajouter — répète ta demande en précisant le contenu.");
                    return;
                }
                String due = params.optString("due_date", "").trim();
                long reminderAt = params.optLong("reminder_at", 0);
                String fold = SpeechInputNormalizer.fold(text).replace('\'', ' ');
                boolean forceReminder = params.optBoolean("is_reminder", false)
                        || params.optBoolean("reminder", false)
                        || reminderAt > 0
                        || !due.isEmpty();
                NotepadDateHelper.ReminderResolution res = NotepadDateHelper.resolveReminder(
                        text, fold, due, reminderAt, forceReminder);
                if (res.dueDate.isEmpty()) {
                    // Sans date : rangé sur la journée (l'agenda lit le bloc-notes par jour)
                    String day = res.reminderAt > 0
                            ? new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE)
                                    .format(new java.util.Date(res.reminderAt))
                            : NotepadDateHelper.today();
                    res = new NotepadDateHelper.ReminderResolution(day, res.reminderAt,
                            res.appliedDefault, res.spokenWhen);
                }
                String tag = params.optString("projet_tag", "").trim();
                if (tag.isEmpty()) {
                    tag = ProjectObjectStore.getInstance(ctx).bestEffortTagFor(text);
                }
                NotepadStore.Item item = store.add(text, res.dueDate, res.reminderAt, tag, res);
                if (item != null) {
                    StringBuilder reply = new StringBuilder("C'est noté, j'ai ajouté « ")
                            .append(text).append(" »");
                    if (!TextUtils.isEmpty(res.spokenWhen)) {
                        reply.append(" — je te rappelle ").append(res.spokenWhen);
                    } else if (!res.dueDate.isEmpty()
                            && !res.dueDate.equals(NotepadDateHelper.today())) {
                        reply.append(" pour ")
                                .append(NotepadDateHelper.formatDateLabel(res.dueDate));
                    }
                    reply.append(".");
                    cb.onSuccess(ToolResult.text(reply.toString()));
                } else {
                    cb.onError("Je n'ai pas pu ajouter cet élément.");
                }
                break;
            }
            case "remove": {
                String text = params.optString("text", "").trim();
                if (text.isEmpty()) {
                    cb.onError("Précise quoi retirer de la liste.");
                    return;
                }
                int removed = store.removeContaining(text);
                if (removed > 0) {
                    cb.onSuccess(ToolResult.text("J'ai retiré ça de ta liste."));
                } else {
                    cb.onError("Je n'ai rien trouvé dans ta liste pour ça.");
                }
                break;
            }
            case "done": {
                String text = params.optString("text", "").trim();
                if (text.isEmpty()) {
                    cb.onError("Précise quel élément marquer comme fait.");
                    return;
                }
                if (store.markDoneContaining(text)) {
                    cb.onSuccess(ToolResult.text("C'est marqué comme fait."));
                } else {
                    cb.onError("Je n'ai pas trouvé cet élément.");
                }
                break;
            }
            case "clear":
                store.clearActive();
                cb.onSuccess(ToolResult.text("Ta liste des choses à faire est vide."));
                break;
            default:
                cb.onSuccess(ToolResult.text(store.formatSummary()));
                break;
        }
    }
}
