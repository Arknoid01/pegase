package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolResult;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;

import java.util.ArrayList;

/** Traduction/résumé français du texte NASA APOD (anglais). */
public final class NasaReplyHelper {

    public interface TranslateCallback {
        void onTranslated(String french);
        void onError(String error);
    }

    private NasaReplyHelper() {}

    public static String extractImageUrl(String reply) {
        ToolResult result = ToolResult.fromWire(reply);
        return result.kind == ToolResult.Kind.IMAGE_URL && result.imageUrl != null
                ? result.imageUrl : "";
    }

    public static String extractEnglishText(String reply) {
        return ToolResult.fromWire(reply).text;
    }

    public static void translate(Context ctx, String englishText, TranslateCallback cb) {
        if (englishText == null || englishText.isEmpty()) {
            cb.onError("Description NASA vide.");
            return;
        }
        ChatBackendFactory.create(ctx).send(new ArrayList<>(), englishText,
                new ChatBackend.OnReply() {
                    @Override
                    public void onReply(String translated) {
                        cb.onTranslated(translated);
                    }

                    @Override
                    public void onError(String error) {
                        cb.onError(error);
                    }
                });
    }
}
