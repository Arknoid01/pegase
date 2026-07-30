package com.pegasuscorp.orbe.iface;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.ui.OrbeTokens;
import com.pegasuscorp.orbe.ui.PegaseSheets;

/** Helpers UI partagés entre les onglets Pégase — boutons à coque unique. */
public final class IfaceUi {

    public static final String C_BG = OrbeTokens.BG;
    public static final String C_CARD = OrbeTokens.CARD;
    public static final String C_CYAN = OrbeTokens.CYAN;
    public static final String C_TEXT = OrbeTokens.TEXT;
    public static final String C_MUTED = OrbeTokens.MUTED;
    public static final String C_SEP = OrbeTokens.SEP;

    /** Fond boutons secondaires (aligné Bureau). */
    public static final String C_BTN = "#1C2430";
    public static final int BTN_HEIGHT_DP = 40;
    public static final int BTN_RADIUS_DP = OrbeTokens.RADIUS_MD;

    private IfaceUi() {}

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams matchWeight() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    /** Icône / glyph — même coque que {@link #secondaryButton}. */
    public static Button makeIconButton(Context ctx, String label) {
        Button b = styledButton(ctx, label, Color.parseColor(C_BTN), Color.WHITE, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, BTN_HEIGHT_DP));
        lp.setMargins(dp(ctx, 4), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setMinWidth(dp(ctx, BTN_HEIGHT_DP));
        b.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0);
        return b;
    }

    /** Bouton secondaire (fond neutre). */
    public static Button secondaryButton(Context ctx, String label) {
        return styledButton(ctx, label, Color.parseColor(C_BTN), Color.WHITE, false);
    }

    /** CTA (cyan). */
    public static Button primaryButton(Context ctx, String label) {
        return styledButton(ctx, label, Color.parseColor(C_CYAN), Color.parseColor(C_BG), false);
    }

    private static Button styledButton(Context ctx, String label, int bgColor, int textColor,
            boolean iconLike) {
        Button b = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconLike ? 14 : 13);
        b.setTypeface(OrbeTokens.typeMedium());
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(ctx, BTN_HEIGHT_DP));
        b.setMinimumHeight(dp(ctx, BTN_HEIGHT_DP));
        b.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        b.setStateListAnimator(null);
        b.setElevation(0f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(ctx, BTN_RADIUS_DP));
        b.setBackground(bg);
        return b;
    }

    public static LinearLayout cardContainer(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(C_CARD));
        bg.setCornerRadius(dp(ctx, OrbeTokens.RADIUS_MD));
        bg.setStroke(dp(ctx, 1), Color.parseColor(C_SEP));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(ctx, 8);
        card.setLayoutParams(lp);
        return card;
    }

    public static EditText inputField(Context ctx, String text, int inputType) {
        EditText field = new EditText(ctx);
        field.setText(text);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.parseColor("#55FFFFFF"));
        field.setInputType(inputType);
        field.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        return field;
    }

    public static LinearLayout padded(Context ctx, EditText field) {
        LinearLayout wrap = new LinearLayout(ctx);
        int pad = dp(ctx, 16);
        wrap.setPadding(pad, dp(ctx, 8), pad, 0);
        wrap.addView(field, matchWrap());
        return wrap;
    }

    public static void showBottomSheet(Context ctx, String title, String[] labels,
                                       java.util.function.IntConsumer onPick) {
        PegaseSheets.show(ctx, title, labels, onPick);
    }

    /** Dialog sombre (lecture fichier, confirmations). */
    public static MaterialAlertDialogBuilder darkDialog(Context ctx) {
        return new MaterialAlertDialogBuilder(ctx, R.style.Theme_Orbe_DarkDialog);
    }

    /** Contenu texte sur fond sombre pour {@link #darkDialog}. */
    public static ScrollView darkTextScroll(Context ctx, CharSequence content) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(Color.parseColor(C_BG));
        TextView tv = new TextView(ctx);
        tv.setText(content);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(OrbeTokens.typeLight());
        tv.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        scroll.addView(tv);
        return scroll;
    }

    public static void attachSwipeLeft(Context ctx, View view, Runnable onSwipeLeft) {
        final float[] startX = {0f};
        final boolean[] swiped = {false};
        int threshold = dp(ctx, 72);
        view.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = ev.getX();
                    swiped[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - startX[0];
                    if (!swiped[0] && dx < -threshold) {
                        swiped[0] = true;
                        PegaseSheets.hapticConfirm(v);
                        if (onSwipeLeft != null) onSwipeLeft.run();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }
}
