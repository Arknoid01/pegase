package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduction cloud — reçoit uniquement le texte utile (pas l'image ni les positions).
 * L'overlay place le résultat localement via les bounds connus.
 */
public final class CopilotTranslator {

    public interface Callback {
        void onSuccess(List<TranslationOverlayService.TranslatedBlock> blocks);
        void onError(String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern LINE = Pattern.compile("(?m)^\\s*(\\d+)\\s*[|:]\\s*(.+)$");

    private CopilotTranslator() {}

    public static void translateBlocks(Context ctx, List<A11ySnapshot.Node> nodes,
            Callback callback) {
        if (nodes == null || nodes.isEmpty()) {
            if (callback != null) callback.onSuccess(Collections.emptyList());
            return;
        }
        Context app = ctx.getApplicationContext();
        String prompt = buildPrompt(nodes);
        IO.execute(() -> {
            ChatBackend backend = ChatBackendFactory.create(app);
            backend.send(Collections.emptyList(), prompt, new ChatBackend.OnReply() {
                @Override
                public void onReply(String text) {
                    List<TranslationOverlayService.TranslatedBlock> blocks =
                            parseResponse(nodes, text);
                    MAIN.post(() -> {
                        if (callback != null) callback.onSuccess(blocks);
                    });
                }

                @Override
                public void onError(String error) {
                    MAIN.post(() -> {
                        if (callback != null) callback.onError(error);
                    });
                }
            });
        });
    }

    private static String buildPrompt(List<A11ySnapshot.Node> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Traduis chaque ligne en français naturel.\n");
        sb.append("Réponds UNIQUEMENT avec le format N|traduction (un par ligne).\n\n");
        for (int i = 0; i < nodes.size(); i++) {
            sb.append(i + 1).append("|").append(nodes.get(i).text).append('\n');
        }
        return sb.toString();
    }

    static List<TranslationOverlayService.TranslatedBlock> parseResponse(
            List<A11ySnapshot.Node> nodes, String raw) {
        List<TranslationOverlayService.TranslatedBlock> out = new ArrayList<>();
        if (raw == null || nodes.isEmpty()) return out;
        Map<Integer, String> map = new HashMap<>();
        Matcher m = LINE.matcher(raw.trim());
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                String tr = m.group(2).trim();
                if (!tr.isEmpty()) map.put(idx, tr);
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < nodes.size(); i++) {
            String tr = map.get(i + 1);
            if (tr == null || tr.isEmpty()) continue;
            A11ySnapshot.Node n = nodes.get(i);
            out.add(new TranslationOverlayService.TranslatedBlock(
                    n.text, tr, n.left, n.top, n.right, n.bottom));
        }
        return out;
    }
}
