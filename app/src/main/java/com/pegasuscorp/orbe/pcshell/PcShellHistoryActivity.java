package com.pegasuscorp.orbe.pcshell;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Historique des exécutions Terminal PC (50 dernières).
 */
public class PcShellHistoryActivity extends PcShellProtectedActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");
    private static final int C_CARD = Color.parseColor("#18FFFFFF");
    private static final int C_SEP = Color.parseColor("#22FFFFFF");

    private float density;

    @Override
    protected void onCreateProtected(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Historique Terminal PC");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("50 dernières exécutions");
        subtitle.setTextColor(C_MUTED);
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        List<PcShellExecutionLog.Entry> entries = PcShellExecutionLog.listRecent(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucune exécution pour l'instant.");
            empty.setTextColor(C_MUTED);
            root.addView(empty);
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.FRENCH);
            for (PcShellExecutionLog.Entry e : entries) {
                root.addView(makeEntryRow(e, fmt));
            }
        }

        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(scroll);
        scroll.post(() -> ViewCompat.requestApplyInsets(scroll));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View root = findViewById(android.R.id.content);
            if (root != null) {
                ViewCompat.requestApplyInsets(root);
            }
        }
    }

    private LinearLayout makeEntryRow(PcShellExecutionLog.Entry e, SimpleDateFormat fmt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(cardBg());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        TextView header = new TextView(this);
        String status = e.cancelled ? "annulé" : "code " + e.exitCode;
        header.setText(fmt.format(new Date(e.atMs)) + " · " + e.label + " · " + status);
        header.setTextColor(Color.WHITE);
        header.setTextSize(13);
        header.setTypeface(null, Typeface.BOLD);
        box.addView(header);

        TextView cmd = new TextView(this);
        cmd.setText(e.command);
        cmd.setTextColor(C_CYAN);
        cmd.setTextSize(11);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setPadding(0, dp(4), 0, dp(4));
        box.addView(cmd);

        if (!e.error.isEmpty()) {
            TextView err = new TextView(this);
            err.setText(e.error);
            err.setTextColor(Color.parseColor("#FFFF5252"));
            err.setTextSize(11);
            box.addView(err);
        } else if (!e.outputPreview.isEmpty()) {
            TextView out = new TextView(this);
            out.setText(AnsiText.toSpannable(e.outputPreview, C_MUTED));
            out.setTextColor(C_MUTED);
            out.setTextSize(11);
            out.setTypeface(Typeface.MONOSPACE);
            out.setMaxLines(8);
            box.addView(out);
        }

        if (e.hasExpandableOutput()) {
            TextView viewAll = new TextView(this);
            viewAll.setText("▸ Voir tout");
            viewAll.setTextColor(C_CYAN);
            viewAll.setTextSize(12);
            viewAll.setPadding(0, dp(6), 0, 0);
            viewAll.setOnClickListener(v -> openFullOutput(e));
            box.addView(viewAll);
        }

        return box;
    }

    private void openFullOutput(PcShellExecutionLog.Entry e) {
        String title = !TextUtils.isEmpty(e.label) ? e.label : e.command;
        PcShellOutputHolder.put(title, e.displayFullOutput());
        startActivity(new Intent(this, PcShellOutputActivity.class)
                .putExtra(PcShellOutputHolder.EXTRA_TITLE, title));
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), C_SEP);
        return g;
    }
}
