package com.pegasuscorp.orbe.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.pegasuscorp.orbe.R;

import java.util.function.IntConsumer;

/**
 * BottomSheet Material partagé — coins arrondis, état expansé, haptique légère.
 */
public final class PegaseSheets {

    private PegaseSheets() {}

    public static void haptic(View v) {
        if (v == null) return;
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static void hapticConfirm(View v) {
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    public static void show(Context ctx, String title, String[] labels, IntConsumer onPick) {
        if (ctx == null || labels == null || labels.length == 0) return;
        final float density = ctx.getResources().getDisplayMetrics().density;

        BottomSheetDialog sheet = new BottomSheetDialog(ctx, R.style.Theme_Orbe_BottomSheet);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(density, 16), dp(density, 8), dp(density, 16), dp(density, 28));
        col.setBackground(sheetBackground(density));

        // Poignée visuelle
        View handle = new View(ctx);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#4A4A4A"));
        handleBg.setCornerRadius(dp(density, 2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                dp(density, 36), dp(density, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(density, 12);
        col.addView(handle, handleLp);

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextColor(OrbeTokens.COLOR_CYAN);
            t.setTextSize(14);
            t.setTypeface(null, Typeface.BOLD);
            t.setPadding(0, 0, 0, dp(density, 10));
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t, matchWrap());
        }

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView row = new TextView(ctx);
            row.setText(labels[i]);
            row.setTextColor(Color.WHITE);
            row.setTextSize(15);
            row.setPadding(dp(density, 8), dp(density, 14),
                    dp(density, 8), dp(density, 14));
            row.setAlpha(0f);
            row.setOnClickListener(v -> {
                hapticConfirm(v);
                sheet.dismiss();
                if (onPick != null) onPick.accept(idx);
            });
            col.addView(row, matchWrap());
            // Apparition décalée des lignes
            row.animate().alpha(1f).setStartDelay(40L * i).setDuration(160).start();
            if (i < labels.length - 1) {
                View sep = new View(ctx);
                sep.setBackgroundColor(OrbeTokens.COLOR_SEP);
                col.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 1)));
            }
        }

        sheet.setContentView(col);
        sheet.setOnShowListener(dialog -> {
            FrameLayout bottom = sheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottom != null) {
                bottom.setBackgroundColor(Color.TRANSPARENT);
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottom);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setDraggable(true);
            }
            haptic(col);
        });
        sheet.show();
    }

    private static int dp(float density, int v) {
        return (int) (v * density);
    }

    private static GradientDrawable sheetBackground(float density) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OrbeTokens.COLOR_CARD);
        float r = 18f * density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return bg;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
