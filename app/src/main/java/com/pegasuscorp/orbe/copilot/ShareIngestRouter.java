package com.pegasuscorp.orbe.copilot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Route le texte partagé ou du presse-papiers vers mémoire ou contexte nommé.
 * Option privilégiée : Partager → Pégase. Alternative : copier + « retiens ça ».
 */
public final class ShareIngestRouter {

    public enum Target { MEMORY, NAMED_CONTEXT }

    public static final class Result {
        public final boolean ok;
        public final Target target;
        public final String message;
        public final String keyword;

        Result(boolean ok, Target target, String message, String keyword) {
            this.ok = ok;
            this.target = target;
            this.message = message;
            this.keyword = keyword;
        }
    }

    private ShareIngestRouter() {}

    /** Partage Android (ACTION_SEND text/plain). */
    public static Result ingestSharedText(Context ctx, String text, String subjectHint) {
        if (TextUtils.isEmpty(text)) {
            return new Result(false, Target.MEMORY, "Texte vide.", null);
        }
        String trimmed = text.trim();
        if (!TextUtils.isEmpty(subjectHint)) {
            ContextualFileStore store = ContextualFileStore.getInstance(ctx);
            String keyword = subjectHint.trim();
            if (store.contextExists(keyword) || store.resolveKeyword(keyword) != null) {
                boolean ok = appendToContext(ctx, keyword, trimmed);
                return new Result(ok, Target.NAMED_CONTEXT,
                        ok ? "Ajouté au contexte " + keyword + "." : "Échec contexte.",
                        keyword);
            }
        }
        return saveToMemory(ctx, trimmed);
    }

    /**
     * Commande vocale avec presse-papiers :
     * « retiens ça » → mémoire ; « ajoute ça à Orion » → contexte nommé.
     */
    public static Result ingestFromVoice(Context ctx, String utterance) {
        String clip = readClipboard(ctx);
        if (TextUtils.isEmpty(clip)) {
            return new Result(false, Target.MEMORY,
                    "Le presse-papiers est vide — copie d'abord le texte.", null);
        }
        String fold = utterance != null
                ? utterance.toLowerCase(Locale.ROOT).replace('’', '\'') : "";
        String contextName = parseContextName(fold);
        if (contextName != null) {
            boolean ok = appendToContext(ctx, contextName, clip.trim());
            return new Result(ok, Target.NAMED_CONTEXT,
                    ok ? "Ajouté au contexte " + contextName + "." : "Contexte introuvable.",
                    contextName);
        }
        if (looksLikeRemember(fold)) {
            return saveToMemory(ctx, clip.trim());
        }
        return new Result(false, Target.MEMORY,
                "Dis « retiens ça » ou « ajoute ça à [contexte] ».", null);
    }

    public static boolean looksLikeRemember(String fold) {
        if (fold == null) return false;
        return fold.contains("retiens ca") || fold.contains("retiens ça")
                || fold.contains("retenir ca") || fold.contains("retenir ça")
                || fold.contains("souviens toi de ca") || fold.contains("souviens-toi de ca")
                || fold.contains("memorise ca") || fold.contains("mémorise ca")
                || fold.equals("retiens") || fold.equals("retenir");
    }

    public static String parseContextName(String fold) {
        if (fold == null) return null;
        String[] prefixes = {
                "ajoute ca a ", "ajoute ça à ", "ajoute ca dans ",
                "ajoute ça dans ", "mets ca dans ", "mets ça dans ",
                "ajoute a ", "ajoute à "
        };
        for (String p : prefixes) {
            int i = fold.indexOf(p);
            if (i >= 0) {
                String name = fold.substring(i + p.length()).trim();
                int cut = name.indexOf(" du presse");
                if (cut > 0) name = name.substring(0, cut).trim();
                if (!name.isEmpty()) return name;
            }
        }
        return null;
    }

    private static Result saveToMemory(Context ctx, String text) {
        MemoryRepository repo = MemoryRepository.getInstance(ctx);
        repo.addPermanentMemory(new MemoryEntry(
                "web", text, 0.88,
                new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()),
                MemoryEntry.SOURCE_USER));
        return new Result(true, Target.MEMORY, "Je retiens : " + truncate(text, 80), null);
    }

    public static boolean appendToContext(Context ctx, String keyword, String text) {
        if (TextUtils.isEmpty(keyword) || TextUtils.isEmpty(text)) return false;
        ContextualFileStore store = ContextualFileStore.getInstance(ctx);
        String resolved = store.resolveKeyword(keyword);
        if (resolved == null && !store.contextExists(keyword)) return false;
        String existing = store.readByKeyword(keyword);
        if (existing == null) existing = "";
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
        String block = "\n\n## " + stamp + "\n" + text.trim() + "\n";
        store.save(keyword, existing.trim() + block);
        return true;
    }

    private static String readClipboard(Context ctx) {
        ClipboardManager cm = (ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return "";
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence t = clip.getItemAt(0).getText();
        return t != null ? t.toString() : "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
