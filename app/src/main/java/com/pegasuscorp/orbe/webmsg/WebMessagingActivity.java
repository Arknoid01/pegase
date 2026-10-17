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
 * Mini navigateur de messageries web (Messenger, WhatsApp Web) — ÉTAPE TEST.
 *
 * Objectif du test : vérifier que les sites acceptent une WebView qui se présente
 * comme Chrome de bureau. Aucune lecture des messages pour l'instant.
 * Seuls deux sites fixes sont chargés ; l'intent n'apporte aucune URL.
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

    private WebView web;

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
        bar.addView(chip("Messenger", MESSENGER));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(8);
        bar.addView(chip("WhatsApp", WHATSAPP), gap);
        page.addView(bar);

        web = new WebView(this);
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
        page.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(page, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(page);
        web.loadUrl(MESSENGER);
    }

    private TextView chip(String label, String url) {
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
        t.setOnClickListener(v -> web.loadUrl(url));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
