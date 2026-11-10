package com.pegasuscorp.orbe.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.pegasuscorp.orbe.R;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Feuilles du bas pour l'appui long dans le tiroir d'apps : en-tête icône + nom,
 * actions en pastilles rondes, renommage, choix d'une app.
 */
public final class AppActionSheet {

    /** Une action ronde de la feuille. */
    public static final class Action {
        final String emoji;
        final String label;
        final boolean danger;
        final Runnable run;

        public Action(String emoji, String label, boolean danger, Runnable run) {
            this.emoji = emoji;
            this.label = label;
            this.danger = danger;
            this.run = run;
        }
    }

    private static final int DANGER = Color.parseColor("#FF6B6B");

    private AppActionSheet() {}

    // ── Feuille principale ───────────────────────────────────────────────────

    public static void showActions(Context ctx, Drawable icon, String title, String subtitle,
                                   int accent, List<Action> actions) {
        float d = ctx.getResources().getDisplayMetrics().density;
        BottomSheetDialog sheet = newSheet(ctx);
        LinearLayout col = column(ctx, d);
        col.addView(header(ctx, d, icon, title, subtitle), matchWrap());

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 18), 0, dp(d, 4));
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            View tile = actionTile(ctx, d, a, accent);
            tile.setOnClickListener(v -> {
                PegaseSheets.hapticConfirm(v);
                sheet.dismiss();
                a.run.run();
            });
            tile.setAlpha(0f);
            tile.setTranslationY(dp(d, 8));
            tile.animate().alpha(1f).translationY(0f).setStartDelay(35L * i).setDuration(170).start();
            row.addView(tile, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(row, matchWrap());
        present(sheet, col);
    }

    /** Liste simple (sous-menus) au même style, avec en-tête. */
    public static void showList(Context ctx, Drawable icon, String title, String subtitle,
                                String[] labels, java.util.function.IntConsumer onPick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        BottomSheetDialog sheet = newSheet(ctx);
        LinearLayout col = column(ctx, d);
        col.addView(header(ctx, d, icon, title, subtitle), matchWrap());
        col.addView(spacer(ctx, dp(d, 10)));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView row = rowText(ctx, d, labels[i]);
            row.setOnClickListener(v -> {
                PegaseSheets.hapticConfirm(v);
                sheet.dismiss();
                onPick.accept(idx);
            });
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(d, 6);
            col.addView(row, lp);
        }
        present(sheet, col);
    }

    // ── Renommer ─────────────────────────────────────────────────────────────

    public static void showRename(Context ctx, Drawable icon, String current, boolean hasCustom,
                                  int accent, Consumer<String> onSave, Runnable onReset) {
        float d = ctx.getResources().getDisplayMetrics().density;
        BottomSheetDialog sheet = newSheet(ctx);
        LinearLayout col = column(ctx, d);
        col.addView(header(ctx, d, icon, "Renommer", "Le nom ne change que dans Orbe"), matchWrap());

        EditText field = new EditText(ctx);
        field.setSingleLine(true);
        field.setText(current);
        field.setSelectAllOnFocus(true);
        field.setTextColor(Color.WHITE);
        field.setTextSize(17);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setCornerRadius(dp(d, 14));
        fbg.setColor(OrbeTokens.COLOR_INPUT_BG);
        fbg.setStroke(dp(d, 1), accent);
        field.setBackground(fbg);
        field.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12));
        LinearLayout.LayoutParams flp = matchWrap();
        flp.topMargin = dp(d, 16);
        col.addView(field, flp);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (hasCustom) {
            TextView reset = pill(ctx, d, "Nom d'origine", Color.TRANSPARENT, OrbeTokens.COLOR_MUTED);
            reset.setOnClickListener(v -> {
                sheet.dismiss();
                onReset.run();
            });
            buttons.addView(reset);
            View grow = new View(ctx);
            buttons.addView(grow, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        TextView save = pill(ctx, d, "Enregistrer", accent, Color.parseColor("#0B0E14"));
        Runnable doSave = () -> {
            PegaseSheets.hapticConfirm(save);
            sheet.dismiss();
            onSave.accept(field.getText().toString());
        };
        save.setOnClickListener(v -> doSave.run());
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doSave.run();
                return true;
            }
            return false;
        });
        buttons.addView(save);
        LinearLayout.LayoutParams blp = matchWrap();
        blp.topMargin = dp(d, 16);
        col.addView(buttons, blp);

        if (sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        present(sheet, col);
        field.requestFocus();
    }

    // ── Choisir une app (icônes) ─────────────────────────────────────────────

    public static <T> void showAppPicker(Context ctx, String title, List<T> apps,
                                         Function<T, String> label, Function<T, Drawable> icon,
                                         Consumer<T> onPick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        BottomSheetDialog sheet = newSheet(ctx);
        LinearLayout col = column(ctx, d);
        col.addView(header(ctx, d, null, title, null), matchWrap());

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);
        final int perRow = 4;
        LinearLayout line = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % perRow == 0) {
                line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(line, matchWrap());
            }
            T app = apps.get(i);
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(d, 4), dp(d, 10), dp(d, 4), dp(d, 10));
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon.apply(app));
            cell.addView(iv, new LinearLayout.LayoutParams(dp(d, 44), dp(d, 44)));
            TextView tv = new TextView(ctx);
            tv.setText(label.apply(app));
            tv.setTextColor(Color.parseColor("#CCFFFFFF"));
            tv.setTextSize(11);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(d, 4), 0, 0);
            cell.addView(tv, matchWrap());
            cell.setOnClickListener(v -> {
                PegaseSheets.hapticConfirm(v);
                sheet.dismiss();
                onPick.accept(app);
            });
            line.addView(cell, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (line != null) {
            for (int k = apps.size() % perRow; k != 0 && k < perRow; k++) {
                line.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        int maxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.6f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH);
        slp.topMargin = dp(d, 8);
        col.addView(scroll, slp);
        present(sheet, col);
    }

    // ── Briques ──────────────────────────────────────────────────────────────

    private static BottomSheetDialog newSheet(Context ctx) {
        return new BottomSheetDialog(ctx, R.style.Theme_Orbe_BottomSheet);
    }

    private static LinearLayout column(Context ctx, float d) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(d, 20), dp(d, 10), dp(d, 20), dp(d, 28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2121820"));
        float r = 24f * d;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        col.setBackground(bg);

        View handle = new View(ctx);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Color.parseColor("#44FFFFFF"));
        hb.setCornerRadius(dp(d, 2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(d, 36), dp(d, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(d, 16);
        col.addView(handle, hlp);
        return col;
    }

    private static View header(Context ctx, float d, Drawable icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(d, 52), dp(d, 52));
            ilp.rightMargin = dp(d, 14);
            row.addView(iv, ilp);
        }
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(19);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextColor(Color.parseColor("#77FFFFFF"));
            s.setTextSize(12);
            s.setMaxLines(1);
            s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static View actionTile(Context ctx, float d, Action a, int accent) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(d, 2), dp(d, 6), dp(d, 2), dp(d, 6));
        int tint = a.danger ? DANGER : accent;

        TextView circle = new TextView(ctx);
        circle.setText(a.emoji);
        circle.setTextSize(22);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(Color.argb(0x26, Color.red(tint), Color.green(tint), Color.blue(tint)));
        cbg.setStroke(dp(d, 1), Color.argb(0x66, Color.red(tint), Color.green(tint), Color.blue(tint)));
        circle.setBackground(cbg);
        tile.addView(circle, new LinearLayout.LayoutParams(dp(d, 56), dp(d, 56)));

        TextView label = new TextView(ctx);
        label.setText(a.label);
        label.setTextColor(a.danger ? DANGER : Color.parseColor("#DDFFFFFF"));
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setPadding(0, dp(d, 8), 0, 0);
        tile.addView(label, matchWrap());
        return tile;
    }

    private static TextView rowText(Context ctx, float d, String text) {
        TextView row = new TextView(ctx);
        row.setText(text);
        row.setTextColor(Color.WHITE);
        row.setTextSize(15);
        row.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(d, 14));
        bg.setColor(Color.parseColor("#14FFFFFF"));
        row.setBackground(bg);
        return row;
    }

    private static TextView pill(Context ctx, float d, String text, int bgColor, int textColor) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(d, 20), dp(d, 11), dp(d, 20), dp(d, 11));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(d, 22));
        bg.setColor(bgColor);
        b.setBackground(bg);
        return b;
    }

    private static View spacer(Context ctx, int h) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private static void present(BottomSheetDialog sheet, View content) {
        sheet.setContentView(content);
        sheet.setOnShowListener(dialog -> {
            FrameLayout bottom = sheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottom != null) {
                bottom.setBackgroundColor(Color.TRANSPARENT);
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottom);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            PegaseSheets.haptic(content);
        });
        sheet.show();
    }

    private static int dp(float d, int v) {
        return (int) (v * d);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
