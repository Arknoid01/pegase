package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import io.noties.markwon.Markwon;
import io.noties.markwon.syntax.Prism4jThemeDarkula;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;

/**
 * Aperçu code Orion — markdown + coloration Prism4j (Markwon).
 */
public final class OrionCodePreview {

    private static final int MAX_CHARS = 12_000;
    private static volatile Markwon markwon;

    private OrionCodePreview() {}

    @NonNull
    public static CharSequence render(@NonNull Context ctx, @Nullable String path,
            @Nullable String content) {
        String body = content == null ? "" : content;
        if (body.length() > MAX_CHARS) {
            body = body.substring(0, MAX_CHARS) + "\n…";
        }
        String lang = languageFromPath(path);
        String md = wrapAsFence(lang, body);
        try {
            return get(ctx).toMarkdown(md);
        } catch (Exception e) {
            return body;
        }
    }

    @NonNull
    static String languageFromPath(@Nullable String path) {
        if (path == null || path.isEmpty()) return "clike";
        String name = path;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < path.length()) name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return "clike";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "java":
                return "java";
            case "kt":
            case "kts":
                return "kotlin";
            case "js":
            case "mjs":
            case "cjs":
                return "javascript";
            case "ts":
            case "tsx":
                return "javascript";
            case "py":
                return "python";
            case "go":
                return "go";
            case "rs":
                return "clike";
            case "c":
            case "h":
                return "c";
            case "cpp":
            case "cc":
            case "cxx":
            case "hpp":
                return "cpp";
            case "cs":
                return "csharp";
            case "json":
                return "json";
            case "xml":
            case "html":
            case "htm":
            case "svg":
                return "markup";
            case "css":
            case "scss":
                return "css";
            case "sh":
            case "bash":
            case "zsh":
                return "clike";
            case "sql":
                return "sql";
            case "yml":
            case "yaml":
                return "yaml";
            case "md":
            case "markdown":
                return "markdown";
            case "gradle":
            case "groovy":
                return "groovy";
            case "dart":
                return "java";
            default:
                return "clike";
        }
    }

    @NonNull
    static String wrapAsFence(@Nullable String lang, @NonNull String code) {
        String fence = "```";
        while (code.contains(fence)) {
            fence += "`";
        }
        String language = TextUtils.isEmpty(lang) ? "clike" : lang;
        return fence + language + "\n" + code + "\n" + fence;
    }

    @NonNull
    private static Markwon get(@NonNull Context ctx) {
        Markwon local = markwon;
        if (local != null) return local;
        synchronized (OrionCodePreview.class) {
            if (markwon == null) {
                Prism4j prism4j = new Prism4j(new GrammarLocatorOrion());
                markwon = Markwon.builder(ctx.getApplicationContext())
                        .usePlugin(SyntaxHighlightPlugin.create(
                                prism4j, Prism4jThemeDarkula.create()))
                        .build();
            }
            return markwon;
        }
    }
}
