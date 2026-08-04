package com.pegasuscorp.orbe.voice.handlers;

import android.content.Context;
import android.widget.Toast;

import com.pegasuscorp.orbe.copilot.ShareIngestRouter;
import com.pegasuscorp.orbe.voice.VoiceIntentRouter.RoutedIntent;
import com.pegasuscorp.orbe.voice.VoiceIntentSupport;

import org.json.JSONObject;

/**
 * Intentions vocales du mode copilote — actions locales et ingestion texte.
 */
public final class CopilotIntentHandler implements IntentHandler {

    @Override
    public RoutedIntent tryHandle(Context context, String text, String fold) {
        if (looksLikeBackCommand(fold)) {
            try {
                return VoiceIntentSupport.routed(context, text,
                        VoiceIntentSupport.toolJson("ui_action",
                                new JSONObject().put("action", "back")),
                        "retour", 0.96);
            } catch (Exception e) {
                return null;
            }
        }
        if (looksLikeYoutubeSubtitles(fold)) {
            try {
                return VoiceIntentSupport.routed(context, text,
                        VoiceIntentSupport.toolJson("copilot_action",
                                new JSONObject().put("action", "youtube_subtitles")),
                        "sous-titres youtube", 0.94);
            } catch (Exception e) {
                return null;
            }
        }
        if (ShareIngestRouter.looksLikeRemember(fold)
                || ShareIngestRouter.parseContextName(fold) != null) {
            ShareIngestRouter.Result r = ShareIngestRouter.ingestFromVoice(context, text);
            Toast.makeText(context, r.message, Toast.LENGTH_LONG).show();
            return RoutedIntent.withHint(text, r.ok ? "copilote — texte enregistré" : "copilote");
        }
        return null;
    }

    static boolean looksLikeBackCommand(String fold) {
        if (fold == null) return false;
        String f = fold.trim();
        return f.equals("retour")
                || f.equals("retour arriere")
                || f.equals("revenir")
                || f.equals("page precedente")
                || f.equals("precedent")
                || f.equals("back")
                || f.equals("go back");
    }

    static boolean looksLikeYoutubeSubtitles(String fold) {
        if (fold == null) return false;
        boolean yt = fold.contains("youtube") || fold.contains("video");
        boolean subs = fold.contains("sous titre") || fold.contains("sous-titre")
                || fold.contains("subtitle") || fold.contains("cc")
                || fold.contains("closed caption");
        if (subs && (yt || fold.contains("active") || fold.contains("met")
                || fold.contains("mets") || fold.contains("lance"))) {
            return true;
        }
        return fold.contains("active les sous titres")
                || fold.contains("active les sous-titres")
                || fold.contains("mets les sous titres")
                || fold.contains("met les sous titres")
                || fold.contains("affiche les sous titres");
    }
}
