package com.pegasuscorp.orbe.webmsg;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.ui.OrbeTokens;

/**
 * Mini navigateur de messageries web (WhatsApp Web, Messenger).
 *
 * Une WebView par site, gardées en vie pour passer de l'une à l'autre sans recharger.
 * Tant que l'écran est ouvert, la liste des discussions est relevée régulièrement
 * ({@link WebMsgScrapers}) et rangée dans {@link WebMsgSnapshotStore} pour l'outil
 * {@code messageries}. Aucun pont JavaScript n'est exposé aux sites : on lit seulement
 * le résultat de {@code evaluateJavascript}. Seuls deux sites fixes ; l'intent n'apporte
 * que le choix de l'onglet.
 */
public class WebMessagingActivity extends AppCompatActivity {

    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String MESSENGER = "https://www.messenger.com/";
    private static final String WHATSAPP = "https://web.whatsapp.com/";
    /** Largeur CSS de bureau imposée aux sites, puis réduite pour tenir dans l'écran. */
    private static final int DESKTOP_WIDTH_CSS = 1100;
    /**
     * Les sites de bureau ignorent la largeur du téléphone et débordent : on réécrit
     * leur viewport en largeur fixe, à l'échelle de l'écran.
     */
    private static final String FIT_VIEWPORT_JS = "(function(){"
            + "var w=" + DESKTOP_WIDTH_CSS + ",s=Math.min(1,screen.width/w);"
            + "var c='width='+w+',initial-scale='+s+',minimum-scale='+s+',user-scalable=yes';"
            + "var m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.name='viewport';"
            + "(document.head||document.documentElement).appendChild(m);}"
            + "if(m.content!==c)m.content=c;})();";

    /** Extra optionnel : onglet à ouvrir ({@link WebMsgSnapshotStore#WHATSAPP} ou MESSENGER). */
    public static final String EXTRA_SERVICE = "service";
    private static final long SCRAPE_EVERY_MS = 20_000L;
    private static final long SCRAPE_AFTER_LOAD_MS = 4_000L;

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.widget.FrameLayout stack;
    private WebView whatsapp;
    private WebView messenger;
    private WebView web;
    private TextView whatsappChip;
    private TextView messengerChip;
    private final Runnable scrapeTick = new Runnable() {
        @Override
        public void run() {
            scrapeAll();
            main.postDelayed(this, SCRAPE_EVERY_MS);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(OrbeTokens.COLOR_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        whatsappChip = chip("WhatsApp", WebMsgSnapshotStore.WHATSAPP);
        messengerChip = chip("Messenger", WebMsgSnapshotStore.MESSENGER);
        bar.addView(whatsappChip);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(8);
        bar.addView(messengerChip, gap);
        page.addView(bar);

        stack = new android.widget.FrameLayout(this);
        page.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web != null && web.canGoBack()) web.goBack();
                else finish();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(page, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(page);
        String wanted = getIntent() != null ? getIntent().getStringExtra(EXTRA_SERVICE) : null;
        show(WebMsgSnapshotStore.MESSENGER.equals(wanted)
                ? WebMsgSnapshotStore.MESSENGER : WebMsgSnapshotStore.WHATSAPP);
    }

    /** Affiche l'onglet demandé ; crée et charge sa WebView à la première ouverture. */
    private void show(String service) {
        boolean wa = WebMsgSnapshotStore.WHATSAPP.equals(service);
        if (wa && whatsapp == null) whatsapp = newWebView(WHATSAPP);
        if (!wa && messenger == null) messenger = newWebView(MESSENGER);
        web = wa ? whatsapp : messenger;
        if (whatsapp != null) whatsapp.setVisibility(wa ? android.view.View.VISIBLE : android.view.View.GONE);
        if (messenger != null) messenger.setVisibility(wa ? android.view.View.GONE : android.view.View.VISIBLE);
        styleChip(whatsappChip, wa);
        styleChip(messengerChip, !wa);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView newWebView(String url) {
        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(DESKTOP_UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                view.evaluateJavascript(FIT_VIEWPORT_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(FIT_VIEWPORT_JS, null);
                main.postDelayed(WebMessagingActivity.this::scrapeAll, SCRAPE_AFTER_LOAD_MS);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String host = req.getUrl().getHost();
                if (host != null && (host.endsWith("messenger.com") || host.endsWith("facebook.com")
                        || host.endsWith("whatsapp.com") || host.endsWith("fbcdn.net"))) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                return true;
            }
        });
        stack.addView(web, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(url);
        return web;
    }

    /** Relève la liste des discussions de chaque site chargé (même en arrière-plan de l'écran). */
    private void scrapeAll() {
        scrape(whatsapp, WebMsgSnapshotStore.WHATSAPP, WebMsgScrapers.WHATSAPP_JS);
        scrape(messenger, WebMsgSnapshotStore.MESSENGER, WebMsgScrapers.MESSENGER_JS);
    }

    private void scrape(WebView view, String service, String js) {
        if (view == null || js == null) return;
        view.evaluateJavascript(js, raw -> {
            java.util.List<WebMsgSnapshotStore.Chat> chats =
                    WebMsgSnapshotStore.parseChats(WebMsgScrapers.unquote(raw));
            if (chats == null || chats.isEmpty()) return;
            WebMsgSnapshotStore.save(getApplicationContext(), service, chats,
                    System.currentTimeMillis());
        });
    }

    private void styleChip(TextView t, boolean active) {
        GradientDrawable d = (GradientDrawable) t.getBackground();
        d.setStroke(dp(1), active ? OrbeTokens.COLOR_CYAN : OrbeTokens.COLOR_CHIP_STROKE);
        t.setAlpha(active ? 1f : 0.7f);
    }

    private TextView chip(String label, String service) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTypeface(OrbeTokens.typeMedium());
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(OrbeTokens.RADIUS_LG));
        d.setColor(OrbeTokens.COLOR_CHIP_BG);
        d.setStroke(dp(1), OrbeTokens.COLOR_CHIP_STROKE);
        t.setBackground(d);
        t.setOnClickListener(v -> show(service));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(scrapeTick);
        main.postDelayed(scrapeTick, SCRAPE_AFTER_LOAD_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(scrapeTick);
        scrapeAll();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (whatsapp != null) whatsapp.destroy();
        if (messenger != null) messenger.destroy();
        super.onDestroy();
    }
}
