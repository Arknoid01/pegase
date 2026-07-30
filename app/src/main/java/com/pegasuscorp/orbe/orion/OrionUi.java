package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.ui.OrbeTokens;

/** Helpers UI partagés par les vues Orion (pas un Fragment). */
final class OrionUi {

    static final String CYAN = OrbeTokens.CYAN;
    static final String BG = OrbeTokens.BG;
    private static final String BTN = "#1C2430";
    private static final int HEIGHT_DP = 40;
    private static final int RADIUS_DP = OrbeTokens.RADIUS_MD;

    private OrionUi() {}

    static MaterialAlertDialogBuilder darkDialog(Context ctx) {
        return new MaterialAlertDialogBuilder(ctx, R.style.Theme_Orbe_DarkDialog);
    }

    static ScrollView darkMonoScroll(Context ctx, CharSequence content) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(Color.parseColor(BG));
        TextView tv = new TextView(ctx);
        tv.setText(content != null ? content : "");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        tv.setTextIsSelectable(true);
        scroll.addView(tv);
        return scroll;
    }

    /** Aperçu fichier avec coloration Markwon / Prism4j. */
    static ScrollView darkCodeScroll(Context ctx, String path, String content) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(Color.parseColor(BG));
        TextView tv = new TextView(ctx);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        tv.setTextIsSelectable(true);
        tv.setText(OrionCodePreview.render(ctx, path, content));
        scroll.addView(tv);
        return scroll;
    }

    static Button cyanBtn(Context ctx, String label) {
        return styled(ctx, label, Color.parseColor(CYAN), Color.parseColor(BG));
    }

    static Button outlineBtn(Context ctx, String label) {
        return styled(ctx, label, Color.parseColor(BTN), Color.WHITE);
    }

    private static Button styled(Context ctx, String label, int bgColor, int textColor) {
        Button b = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(OrbeTokens.typeMedium());
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(ctx, HEIGHT_DP));
        b.setMinimumHeight(dp(ctx, HEIGHT_DP));
        b.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        b.setStateListAnimator(null);
        b.setElevation(0f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(ctx, RADIUS_DP));
        b.setBackground(bg);
        return b;
    }

    static TextView hint(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(Color.parseColor("#66FFFFFF"));
        t.setTextSize(12);
        t.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        return t;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(8);
        return lp;
    }
}
