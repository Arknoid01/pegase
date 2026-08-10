package com.pegasuscorp.orbe.pcshell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Sortie SSH complète — scrollback intégral d'une exécution.
 */
public final class PcShellOutputActivity extends AppCompatActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_OUT = Color.parseColor("#E8E8E8");

    private String body = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        String title = getIntent().getStringExtra(PcShellOutputHolder.EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) {
            title = PcShellOutputHolder.takeTitle();
        }
        body = PcShellOutputHolder.takeBody();
        if (TextUtils.isEmpty(body)) {
            body = "";
        }
        if (TextUtils.isEmpty(title)) {
            title = "Sortie complète";
        }

        float density = getResources().getDisplayMetrics().density;
        int dp = (int) (density + 0.5f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);

        TextView back = new TextView(this);
        back.setText("← Retour");
        back.setTextColor(C_CYAN);
        back.setTextSize(14);
        back.setPadding(dp * 4, dp * 8, dp * 12, dp * 8);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = new TextView(this);
        copy.setText("Copier");
        copy.setTextColor(C_CYAN);
        copy.setTextSize(14);
        copy.setPadding(dp * 12, dp * 8, dp * 4, dp * 8);
        copy.setOnClickListener(v -> copyBody());
        toolbar.addView(copy);

        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        TextView output = new TextView(this);
        CharSequence display = body.isEmpty()
                ? "(aucune sortie)"
                : AnsiText.toSpannable(body, C_OUT);
        output.setText(display);
        output.setTextColor(C_OUT);
        output.setTextSize(11);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(dp * 10, dp * 8, dp * 10, dp * 8);
        scroll.addView(output, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(dp * 12 + bars.left, dp * 8 + bars.top,
                    dp * 12 + bars.right, dp * 8);
            output.setPadding(dp * 10 + bars.left, dp * 8,
                    dp * 10 + bars.right, dp * 8 + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void copyBody() {
        if (body.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("pc-shell-output", AnsiText.strip(body)));
            Toast.makeText(this, "Sortie copiée", Toast.LENGTH_SHORT).show();
        }
    }
}
