package com.pegasuscorp.orbe.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.llm.PegasePrompt;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte si une réponse LLM est un appel d'outil et l'exécute.
 */
public final class ToolDispatcher {

    private final ToolRegistry registry;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * Retourne true si le texte contient un JSON d'outil valide et exécutable.
     */
    public static boolean isToolCall(String text) {
        return extractJson(text) != null;
    }

    /**
     * Le modèle a tenté un outil (mot-clé tool/params) mais le JSON n'est pas valide.
     */
    public static boolean looksLikeToolAttempt(String text) {
        if (text == null || text.isEmpty()) return false;
        if (isToolCall(text)) return false;
        String fold = text.toLowerCase(Locale.ROOT);
        return fold.contains("\"tool\"")
                || (fold.contains("{") && fold.contains("tool") && fold.contains("params"));
    }

    /** Extrait le premier bloc JSON d'outil valide, même entouré de prose ou de markdown. */
    public static String extractJson(String text) {
        if (text == null) return null;
        String trimmed = stripCodeFences(text.trim());
        int searchFrom = 0;
        while (searchFrom < trimmed.length()) {
            int start = trimmed.indexOf('{', searchFrom);
            if (start < 0) break;
            int end = findMatchingBrace(trimmed, start);
            if (end < 0) break;
            String candidate = trimmed.substring(start, end + 1).trim();
            if (isValidToolJson(candidate)) {
                return candidate;
            }
            searchFrom = start + 1;
        }
        return null;
    }

    /** Retire fences markdown et blocs JSON d'outil d'une réponse mixte. */
    public static String stripToolCall(String text) {
        if (text == null) return "";
        String json = extractJson(text);
        String stripped = text;
        if (json != null) {
            stripped = stripped.replace(json, "");
        }
        stripped = stripCodeFences(stripped);
        stripped = stripped.replaceAll("\\s{2,}", " ").trim();
        return stripped;
    }

    /** Texte prêt pour l'interface et la mémoire (sans JSON ni protocoles internes). */
    public static String cleanForDisplay(String text) {
        if (text == null) return "";
        String out = stripToolCall(text);
        ToolResult parsed = ToolResult.fromWire(out);
        if (parsed.kind == ToolResult.Kind.IMAGE_URL) {
            return "(Photo NASA du jour)";
        }
        if (out.contains("NASA APOD du jour")) {
            int idx = out.indexOf("Titre :");
            if (idx >= 0) {
                String title = out.substring(idx + 7).trim();
                int nl = title.indexOf('\n');
                if (nl > 0) title = title.substring(0, nl).trim();
                return title.isEmpty() ? "(Photo NASA du jour)" : title;
            }
            return "(Photo NASA du jour)";
        }
        return PegasePrompt.sanitizeForDisplay(out);
    }

    /**
     * Parse le JSON et exécute l'outil correspondant.
     * Le callback est toujours appelé sur le thread principal.
     */
    public void dispatch(Context ctx, String text, ToolCallback cb) {
        String cleaned = extractJson(text);
        if (cleaned == null) {
            if (looksLikeToolAttempt(text)) {
                Trace.toolHesitation(guessToolId(text), "malformed_tool",
                        "JSON d'outil malformé — dispatch refusé",
                        null);
            }
            String msg = looksLikeToolAttempt(text)
                    ? "Format d'outil invalide — réessaie ou reformule ta demande."
                    : "Format d'outil invalide.";
            post(cb, () -> cb.onError(msg));
            return;
        }

        JSONObject json;
        try {
            json = new JSONObject(cleaned);
        } catch (Exception e) {
            Trace.toolHesitation(guessToolId(text), "malformed_tool",
                    "JSON d'outil illisible", null);
            post(cb, () -> cb.onError("Format d'outil invalide — JSON illisible."));
            return;
        }

        String toolId = json.optString("tool", "").trim();
        if (toolId.isEmpty()) {
            post(cb, () -> cb.onError("Format d'outil invalide — identifiant manquant."));
            return;
        }

        JSONObject params = json.optJSONObject("params");
        if (params == null) params = new JSONObject();

        Tool tool = registry.findById(toolId);
        if (tool == null) {
            final String id = toolId;
            post(cb, () -> cb.onError("Outil inconnu : " + id));
            return;
        }

        Trace.toolStart(toolId, params, false);
        final long startedAt = System.currentTimeMillis();
        final String tid = toolId;
        ToolCallback traced = new ToolCallback() {
            @Override public void onSuccess(ToolResult result) {
                String wire = result != null ? result.wireText() : "";
                Trace.toolEnd(tid, true, System.currentTimeMillis() - startedAt, wire, null);
                cb.onSuccess(result != null ? result : ToolResult.text(""));
            }
            @Override public void onSuccessAndExit(ToolResult result) {
                String wire = result != null ? result.wireText() : "";
                Trace.toolEnd(tid, true, System.currentTimeMillis() - startedAt, wire, null);
                cb.onSuccessAndExit(result != null ? result : ToolResult.text(""));
            }
            @Override public void onConfirmNeeded(String q, Runnable ok, Runnable no) {
                cb.onConfirmNeeded(q, ok, no);
            }
            @Override public void onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                cb.onChoiceNeeded(title, labels, onChosen, onCancel);
            }
            @Override public void onProgress(String message) {
                cb.onProgress(message);
            }
            @Override public void onError(String error) {
                Trace.toolEnd(tid, false, System.currentTimeMillis() - startedAt, null, error);
                Trace.toolFailureContext(tid, "tool_execute_error",
                        error != null ? error : "", null);
                cb.onError(error);
            }
        };

        final JSONObject finalParams = params;
        final Tool finalTool = tool;
        final Context finalCtx = ctx;

        post(cb, () -> finalTool.execute(finalCtx, finalParams, traced));
    }

    private static String stripCodeFences(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)```[a-z]*\\s*", "")
                .replaceAll("```", "")
                .trim();
    }

    private static final Pattern TOOL_ID = Pattern.compile("\"tool\"\\s*:\\s*\"([^\"]+)\"");

    /** Extrait un id d'outil depuis un JSON partiel / prose / erreur HTTP. */
    public static String guessToolId(String text) {
        if (text == null || text.isEmpty()) return "unknown";
        Matcher m = TOOL_ID.matcher(text);
        if (m.find()) {
            String id = m.group(1).trim();
            if (!id.isEmpty()) return id;
        }
        String fold = text.toLowerCase(Locale.ROOT);
        if (fold.contains("notepad")) return "notepad";
        if (fold.contains("weather")) return "weather";
        if (fold.contains("calculator") || fold.contains("\"calc\"")) return "calculator";
        if (fold.contains("spotify")) return "spotify";
        if (fold.contains("search") || fold.contains("tavily")) return "search";
        return "unknown";
    }

    private static boolean isValidToolJson(String candidate) {
        if (candidate == null) return false;
        Matcher m = TOOL_ID.matcher(candidate);
        if (!m.find() || m.group(1).trim().isEmpty()) return false;
        try {
            new JSONObject(candidate);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Trouve la accolade fermante correspondant à {@code start} (gestion des chaînes). */
    static int findMatchingBrace(String text, int start) {
        if (text == null || start < 0 || start >= text.length() || text.charAt(start) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void post(ToolCallback cb, Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
        }
    }
}
