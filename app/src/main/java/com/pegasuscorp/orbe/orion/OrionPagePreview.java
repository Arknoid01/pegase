package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Aperçu « page » Orion — WebView comme si on ouvrait le fichier dans le navigateur.
 */
public final class OrionPagePreview {

    private static final String BASE = "https://orion.preview.local/";

    /** Payload pour {@link OrionPagePreviewActivity} (évite la limite Intent Binder). */
    public static final class Payload {
        public final String path;
        public final String content;
        public final Map<String, String> siblings;
        /** Si non vide → WebView charge cette URL (preview live pod). */
        public final String remoteUrl;

        public Payload(String path, String content, Map<String, String> siblings) {
            this(path, content, siblings, null);
        }

        public Payload(String path, String content, Map<String, String> siblings,
                String remoteUrl) {
            this.path = path;
            this.content = content != null ? content : "";
            this.siblings = siblings != null
                    ? new HashMap<>(siblings)
                    : Collections.emptyMap();
            this.remoteUrl = remoteUrl != null ? remoteUrl.trim() : "";
        }

        public boolean isRemote() {
            return !TextUtils.isEmpty(remoteUrl);
        }
    }

    private static volatile Payload pendingPayload;

    private OrionPagePreview() {}

    /** Ouvre l'aperçu page en plein écran ; retour système → Orion. */
    public static void openFullscreen(@NonNull android.app.Activity activity,
            @Nullable String path, @Nullable String content,
            @Nullable Map<String, String> siblings) {
        if (activity == null) return;
        pendingPayload = new Payload(path, content, siblings);
        android.content.Intent i = new android.content.Intent(
                activity, OrionPagePreviewActivity.class);
        activity.startActivity(i);
    }

    /** Aperçu live : WebView sur l'URL du fileserver pod. */
    public static void openLiveUrl(@NonNull android.app.Activity activity,
            @Nullable String title, @NonNull String url) {
        if (activity == null || TextUtils.isEmpty(url)) return;
        pendingPayload = new Payload(title != null ? title : "Aperçu live", "",
                Collections.emptyMap(), url);
        android.content.Intent i = new android.content.Intent(
                activity, OrionPagePreviewActivity.class);
        activity.startActivity(i);
    }

    @Nullable
    static Payload takePending() {
        Payload p = pendingPayload;
        pendingPayload = null;
        return p;
    }

    /** HTML / SVG (ou contenu qui en a l'air). */
    public static boolean isPage(@Nullable String path, @Nullable String content) {
        String p = path != null ? path.toLowerCase(Locale.ROOT) : "";
        if (p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".svg")) return true;
        if (content == null || content.isEmpty()) return false;
        String head = content.length() > 800 ? content.substring(0, 800) : content;
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("<!doctype html")
                || lower.contains("<html")
                || lower.contains("<svg");
    }

    /**
     * @param siblings fichiers compagnons (css/js/images) indexés par chemin relatif / nom
     */
    @NonNull
    public static FrameLayout create(@NonNull Context ctx, @Nullable String path,
            @Nullable String content, @Nullable Map<String, String> siblings) {
        String body = content != null ? content : "";
        Map<String, String> files = indexFiles(path, body, siblings);

        FrameLayout host = new FrameLayout(ctx);
        host.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        host.setMinimumHeight(OrionUi.dp(ctx, 360));
        host.setBackgroundColor(Color.WHITE);

        WebView web = new WebView(ctx);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return super.shouldInterceptRequest(view, request);
                }
                WebResourceResponse local = resolveLocal(request.getUrl(), files);
                return local != null ? local : super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url == null) return super.shouldInterceptRequest(view, url);
                WebResourceResponse local = resolveLocal(Uri.parse(url), files);
                return local != null ? local : super.shouldInterceptRequest(view, url);
            }
        });

        String entry = entryHtml(path, body);
        String mime = looksLikeSvg(path, body) ? "image/svg+xml" : "text/html";
        web.loadDataWithBaseURL(BASE, entry, mime, "UTF-8", null);

        host.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return host;
    }

    /** WebView branchée sur l'URL fileserver pod (preview live). */
    @NonNull
    public static FrameLayout createRemote(@NonNull Context ctx, @NonNull String url) {
        FrameLayout host = new FrameLayout(ctx);
        host.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        host.setBackgroundColor(Color.WHITE);

        WebView web = new WebView(ctx);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // Mixed content si besoin (assets locaux)
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new WebViewClient());
        web.loadUrl(url);

        host.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return host;
    }

    @NonNull
    static Map<String, String> indexFiles(@Nullable String path, @NonNull String body,
            @Nullable Map<String, String> siblings) {
        Map<String, String> out = new HashMap<>();
        if (siblings != null) {
            for (Map.Entry<String, String> e : siblings.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                putAliases(out, e.getKey(), e.getValue());
            }
        }
        if (!TextUtils.isEmpty(path)) {
            putAliases(out, path, body);
        }
        return out;
    }

    private static void putAliases(Map<String, String> out, String path, String content) {
        String norm = normalizePath(path);
        out.put(norm, content);
        int slash = norm.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < norm.length()) {
            out.put(norm.substring(slash + 1), content);
        } else {
            out.put(norm, content);
        }
    }

    @NonNull
    static String normalizePath(@Nullable String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    @Nullable
    static WebResourceResponse resolveLocal(@NonNull Uri uri,
            @NonNull Map<String, String> files) {
        String host = uri.getHost();
        if (host == null || !host.contains("orion.preview.local")) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) return null;
        if (path.startsWith("/")) path = path.substring(1);
        path = normalizePath(path);
        String content = files.get(path);
        if (content == null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) content = files.get(path.substring(slash + 1));
        }
        if (content == null) return null;
        String mime = mimeFor(path, content);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse(mime, "UTF-8",
                new ByteArrayInputStream(bytes));
    }

    @NonNull
    static String mimeFor(@Nullable String path, @Nullable String content) {
        String p = path != null ? path.toLowerCase(Locale.ROOT) : "";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg") || looksLikeSvg(path, content)) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (content != null && content.startsWith("data:")) return "text/plain";
        return "text/plain";
    }

    static boolean looksLikeSvg(@Nullable String path, @Nullable String content) {
        String p = path != null ? path.toLowerCase(Locale.ROOT) : "";
        if (p.endsWith(".svg")) return true;
        if (content == null) return false;
        String head = content.length() > 400 ? content.substring(0, 400) : content;
        return head.toLowerCase(Locale.ROOT).contains("<svg");
    }

    @NonNull
    static String entryHtml(@Nullable String path, @NonNull String body) {
        if (looksLikeSvg(path, body) && !body.toLowerCase(Locale.ROOT).contains("<html")) {
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>html,body{margin:0;background:#fff;}"
                    + "svg{max-width:100%;height:auto;display:block;margin:12px auto;}</style>"
                    + "</head><body>" + body + "</body></html>";
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype")) {
            return body;
        }
        // Fragment HTML → page minimale
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "</head><body>" + body + "</body></html>";
    }

    /** Pour tests — encode data-uri image (non utilisé en prod pour l'instant). */
    @NonNull
    static String dataUri(@NonNull String mime, @NonNull byte[] bytes) {
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    @NonNull
    public static Map<String, String> emptySiblings() {
        return Collections.emptyMap();
    }
}
