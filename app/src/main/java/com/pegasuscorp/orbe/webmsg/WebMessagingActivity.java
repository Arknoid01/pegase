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

import com.pegasuscorp.orbe.inbox.InboxItem;
import com.pegasuscorp.orbe.inbox.InboxListView;
import com.pegasuscorp.orbe.inbox.SmsThreads;
import com.pegasuscorp.orbe.ui.OrbeTokens;

/**
 * Messagerie : onglet « Tout » (SMS + WhatsApp + Messenger, {@link InboxListView}) et un
 * onglet par site web (WhatsApp Web, Messenger).
 *
 * Les deux WebViews sont créées à l'ouverture et restent chargées sous la liste, pour que
 * les relevés soient à jour même sans passer par leur onglet.
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
    private static final String TAB_ALL = "tout";
    private static final int REQ_SMS = 7321;
    private static final long SCRAPE_EVERY_MS = 20_000L;
    private static final long SCRAPE_AFTER_LOAD_MS = 4_000L;

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.widget.FrameLayout stack;
    private WebView whatsapp;
    private WebView messenger;
    private WebView web;
    private InboxListView inbox;
    private TextView allChip;
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
        allChip = chip("Tout", TAB_ALL);
        whatsappChip = chip("WhatsApp", WebMsgSnapshotStore.WHATSAPP);
        messengerChip = chip("Messenger", WebMsgSnapshotStore.MESSENGER);
        bar.addView(allChip);
        for (TextView c : new TextView[]{whatsappChip, messengerChip}) {
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.leftMargin = dp(8);
            bar.addView(c, gap);
        }
        page.addView(bar);

        stack = new android.widget.FrameLayout(this);
        page.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        whatsapp = newWebView(WHATSAPP);
        messenger = newWebView(MESSENGER);
        // Au-dessus des WebViews : elles continuent de se charger (et d'être relevées) dessous.
        inbox = new InboxListView(this, source -> show(source == InboxItem.Source.MESSENGER
                ? WebMsgSnapshotStore.MESSENGER : WebMsgSnapshotStore.WHATSAPP));
        stack.addView(inbox, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web == null) finish();
                else if (web.canGoBack()) web.goBack();
                else show(TAB_ALL);
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(page, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(page);
        String wanted = getIntent() != null ? getIntent().getStringExtra(EXTRA_SERVICE) : null;
        show(wanted != null ? wanted : TAB_ALL);
        if (!SmsThreads.canRead(this)) {
            androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_CONTACTS}, REQ_SMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_SMS) inbox.reload();
    }

    /**
     * Affiche l'onglet demandé. Les WebViews cachées restent INVISIBLE (pas GONE) : elles
     * gardent leur taille, sinon les listes virtualisées des sites ne se dessinent pas.
     */
    private void show(String tab) {
        boolean all = !WebMsgSnapshotStore.WHATSAPP.equals(tab)
                && !WebMsgSnapshotStore.MESSENGER.equals(tab);
        boolean wa = WebMsgSnapshotStore.WHATSAPP.equals(tab);
        web = all ? null : wa ? whatsapp : messenger;
        inbox.setVisibility(all ? android.view.View.VISIBLE : android.view.View.GONE);
        whatsapp.setVisibility(all || wa ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        messenger.setVisibility(all || !wa ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        if (!wa && !all) messenger.bringToFront();
        if (wa) whatsapp.bringToFront();
        if (all) inbox.bringToFront();
        styleChip(allChip, all);
        styleChip(whatsappChip, wa);
        styleChip(messengerChip, !all && !wa);
        if (all) inbox.reload();
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
            if (web == null) inbox.reload();
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
        if (web == null) inbox.reload();
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
        if (inbox != null) inbox.release();
        if (whatsapp != null) whatsapp.destroy();
        if (messenger != null) messenger.destroy();
        super.onDestroy();
    }
}
