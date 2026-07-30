package com.pegasuscorp.orbe.orion;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Aperçu HTML/SVG Orion en plein écran — retour système / ✕ revient à Orion.
 */
public class OrionPagePreviewActivity extends AppCompatActivity {

    @Nullable
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OrionPagePreview.Payload payload = OrionPagePreview.takePending();
        if (payload == null) {
            finish();
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackgroundColor(Color.parseColor("#FF0B0E14"));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(Color.WHITE);
        back.setTextSize(22);
        back.setPadding(0, 0, pad, 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        String name;
        if (payload.isRemote()) {
            name = "🌐 " + (payload.path != null && !payload.path.isEmpty()
                    ? payload.path : "Aperçu live");
        } else {
            name = payload.path != null && !payload.path.isEmpty()
                    ? payload.path : "Aperçu";
        }
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#AAFFFFFF"));
        close.setTextSize(20);
        close.setPadding(pad, 0, 0, 0);
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout preview;
        if (payload.isRemote()) {
            preview = OrionPagePreview.createRemote(this, payload.remoteUrl);
        } else {
            preview = OrionPagePreview.create(
                    this, payload.path, payload.content, payload.siblings);
        }
        preview.setMinimumHeight(0);
        // Récupérer le WebView pour destroy propre
        if (preview.getChildCount() > 0 && preview.getChildAt(0) instanceof WebView) {
            webView = (WebView) preview.getChildAt(0);
        }
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
