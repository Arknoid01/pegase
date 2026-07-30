package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Fichiers téléphone — lecture/recherche libres ; déplacement confirmé si important ;
 * suppression presque toujours confirmée (corbeille système quand possible).
 */
public final class FilesTool implements Tool {

    @Override
    public String id() {
        return "files";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.FILES;
    }

    @Override
    public String description() {
        return "files(action:\"search\"|\"list\"|\"open\"|\"move\"|\"delete\", "
                + "query?:str, path?:str, folder?:str, destination?:str, permanent?:bool, confirm?:bool) — "
                + "Gère les fichiers du téléphone. "
                + "search/list/open = libres (pas de confirmation). "
                + "move = confirmation si action importante (gros fichier, média, DCIM…). "
                + "delete = confirmation quasi systématique ; corbeille Android (30 j) par défaut, "
                + "permanent:true pour supprimer définitivement. "
                + "folder/destination : downloads|documents|pictures|dcim|movies|music. "
                + "Ex. cherche : {\"tool\":\"files\",\"params\":{\"action\":\"search\",\"query\":\"facture\"}}. "
                + "Ex. déplace : {\"tool\":\"files\",\"params\":{\"action\":\"move\",\"query\":\"recap.pdf\","
                + "\"destination\":\"documents\"}}. "
                + "Ex. supprime : {\"tool\":\"files\",\"params\":{\"action\":\"delete\",\"query\":\"photo.png\"}}.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params != null
                ? params.optString("action", "search").trim().toLowerCase(Locale.ROOT)
                : "search";
        if (action.isEmpty()) action = "search";

        String perm = PhoneFiles.permissionHint(ctx);
        if (perm != null) {
            PhoneFiles.openManageAllFilesSettings(ctx);
            cb.onError(perm);
            return;
        }

        switch (action) {
            case "search":
            case "find":
            case "where":
            case "cherche":
                doSearch(ctx, params, cb);
                break;
            case "list":
            case "ls":
            case "liste":
                doList(ctx, params, cb);
                break;
            case "open":
            case "ouvre":
            case "ouvrir":
                doOpen(ctx, params, cb);
                break;
            case "move":
            case "deplace":
            case "déplace":
            case "rename":
            case "renomme":
                doMove(ctx, params, cb);
                break;
            case "delete":
            case "remove":
            case "supprime":
            case "efface":
            case "trash":
            case "corbeille":
                doDelete(ctx, params, cb);
                break;
            default:
                cb.onError("Action fichiers inconnue : " + action
                        + ". Utilise search, list, open, move ou delete.");
        }
    }

    private static void doSearch(Context ctx, JSONObject params, ToolCallback cb) {
        String query = first(params, "query", "q", "name", "fichier");
        if (TextUtils.isEmpty(query)) {
            cb.onError("Que cherches-tu ? Ex. « facture », « IMG_2024 », « recap.pdf ».");
            return;
        }
        List<PhoneFiles.Entry> hits = PhoneFiles.search(ctx, query, 12);
        if (hits.isEmpty()) {
            cb.onSuccess(ToolResult.text("Je n'ai rien trouvé pour « " + query + " »."));
            return;
        }
        cb.onSuccess(ToolResult.text(PhoneFiles.formatList(hits,
                "Voici ce que j'ai trouvé pour « " + query + " » :")));
    }

    private static void doList(Context ctx, JSONObject params, ToolCallback cb) {
        String folder = first(params, "folder", "dir", "dossier", "destination");
        if (TextUtils.isEmpty(folder)) folder = "downloads";
        String key = PhoneFiles.normalizeFolder(folder);
        List<PhoneFiles.Entry> list = PhoneFiles.listFolder(ctx, key, 20);
        cb.onSuccess(ToolResult.text(PhoneFiles.formatList(list,
                "Contenu de " + key + " :")));
    }

    private static void doOpen(Context ctx, JSONObject params, ToolCallback cb) {
        PhoneFiles.Entry entry = resolveEntry(ctx, params);
        if (entry == null) {
            cb.onError("Fichier introuvable — précise le nom ou le chemin.");
            return;
        }
        try {
            PhoneFiles.open(ctx, entry);
            cb.onSuccessAndExit(ToolResult.text("J'ouvre « " + entry.name + " »."));
        } catch (Exception e) {
            cb.onError("Impossible d'ouvrir « " + entry.name + " » : " + msg(e));
        }
    }

    private static void doMove(Context ctx, JSONObject params, ToolCallback cb) {
        PhoneFiles.Entry entry = resolveEntry(ctx, params);
        if (entry == null) {
            cb.onError("Fichier introuvable à déplacer — précise query ou path.");
            return;
        }
        String dest = first(params, "destination", "dest", "folder", "dossier", "to");
        if (TextUtils.isEmpty(dest)) {
            cb.onError("Vers où ? (downloads, documents, pictures, dcim…)");
            return;
        }
        String destKey = PhoneFiles.normalizeFolder(dest);
        boolean confirmed = params != null && params.optBoolean("confirm", false);
        boolean important = PhoneFiles.isImportantMove(entry, destKey);

        Runnable run = () -> {
            try {
                String where = PhoneFiles.move(ctx, entry, destKey);
                cb.onSuccess(ToolResult.text(
                        "« " + entry.name + " » déplacé vers " + destKey + ".\n" + where));
            } catch (Exception e) {
                cb.onError("Déplacement impossible : " + msg(e));
            }
        };

        if (!confirmed && important) {
            String q = "🟠 Déplacer « " + entry.name + " » ("
                    + PhoneFiles.formatSize(entry.size) + ") vers " + destKey + " ?\n"
                    + "Depuis : " + entry.displayLocation();
            cb.onConfirmNeeded(q, run,
                    () -> cb.onSuccess(ToolResult.text("Déplacement annulé.")));
            return;
        }
        run.run();
    }

    private static void doDelete(Context ctx, JSONObject params, ToolCallback cb) {
        PhoneFiles.Entry entry = resolveEntry(ctx, params);
        if (entry == null) {
            cb.onError("Fichier introuvable à supprimer — précise query ou path.");
            return;
        }
        boolean permanent = params != null && (params.optBoolean("permanent", false)
                || "delete".equalsIgnoreCase(params.optString("mode", ""))
                || params.optBoolean("definitive", false));
        // trash / corbeille actions → never permanent
        String action = params != null ? params.optString("action", "") : "";
        if ("trash".equalsIgnoreCase(action) || "corbeille".equalsIgnoreCase(action)) {
            permanent = false;
        }
        boolean confirmed = params != null && params.optBoolean("confirm", false);

        final boolean fPermanent = permanent;
        Runnable run = () -> {
            try {
                String msg = PhoneFiles.trashOrDelete(ctx, entry, fPermanent);
                cb.onSuccess(ToolResult.text(msg));
            } catch (Exception e) {
                cb.onError("Suppression impossible : " + msg(e));
            }
        };

        // 🔴 Confirmation quasi systématique (sauf confirm:true déjà validé)
        if (!confirmed) {
            String q;
            if (fPermanent) {
                q = "🔴 Supprimer DÉFINITIVEMENT « " + entry.name + " » ("
                        + PhoneFiles.formatSize(entry.size) + ") ?\n"
                        + entry.displayLocation() + "\nCette action est irréversible.";
            } else {
                q = "🔴 Mettre « " + entry.name + " » ("
                        + PhoneFiles.formatSize(entry.size) + ") à la corbeille ?\n"
                        + entry.displayLocation()
                        + "\n(Récupérable ~30 jours via la corbeille Android.)";
            }
            cb.onConfirmNeeded(q, run,
                    () -> cb.onSuccess(ToolResult.text("Suppression annulée.")));
            return;
        }
        run.run();
    }

    private static PhoneFiles.Entry resolveEntry(Context ctx, JSONObject params) {
        String path = first(params, "path", "file", "filepath", "uri");
        String query = first(params, "query", "q", "name", "fichier", "filename");
        return PhoneFiles.resolve(ctx, path, query);
    }

    private static String first(JSONObject params, String... keys) {
        if (params == null) return "";
        for (String k : keys) {
            String v = params.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
