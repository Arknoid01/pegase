package com.pegasuscorp.orbe.tools.memory;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import com.pegasuscorp.orbe.notepad.NotepadStore;

import org.json.JSONObject;

/**
 * Bloc-notes : liste des choses à faire.
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
        return "notepad(action:add|list|remove|done|clear, text:string OBLIGATOIRE pour add, "
                + "due_date?:string, priority?:0-2, reminder_at?:number) — "
                + "Bloc-notes personnel (tâches / liste). "
                + "RÈGLE STRICTE add : NE JAMAIS noter une phrase informative ou conversationnelle. "
                + "Exemple à NE PAS noter : « j'ai un buffet avec des courgettes » = information, pas une demande. "
                + "UNIQUEMENT action=add si l'utilisateur utilise EXPLICITEMENT : "
                + "« note », « ajoute », « écris », « rappelle-moi », « mets dans la liste ». "
                + "Si le doute existe → NE PAS appeler add : DEMANDER confirmation avant de noter. "
                + "Pour action=add : text = contenu exact à noter. "
                + "EXEMPLE valide : {\"tool\":\"notepad\",\"params\":{\"action\":\"add\","
                + "\"text\":\"Courgette, huile d'olive\"}} (après « note courgette… »). "
                + "Ne jamais appeler add sans text. "
                + "list pour lire, done pour cocher, remove pour supprimer. "
                + "NE PAS confondre avec memory (mémoire long terme).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        NotepadStore store = NotepadStore.getInstance(ctx);
        String action = params.optString("action", "list").trim().toLowerCase();

        switch (action) {
            case "list":
            case "list_tomorrow": {
                String speech = "list_tomorrow".equals(action)
                        ? store.formatForTomorrow()
                        : store.formatForSpeech();
                cb.onSuccess(ToolResult.text(speech));
                break;
            }
            case "add": {
                String text = params.optString("text", "").trim();
                if (text.isEmpty()) {
                    cb.onError("Je n'ai pas reçu le texte à ajouter — répète ta demande en précisant le contenu.");
                    return;
                }
                String due = params.optString("due_date", "").trim();
                int priority = params.optInt("priority", 0);
                long reminderAt = params.optLong("reminder_at", 0);
                if (store.add(text, due, priority, reminderAt)) {
                    cb.onSuccess(ToolResult.text("C'est noté, j'ai ajouté « " + text + " » à ta liste."));
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
                cb.onSuccess(ToolResult.text(store.formatForSpeech()));
                break;
        }
    }
}
