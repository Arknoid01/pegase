package com.pegasuscorp.orbe.mcp;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

/** Serveurs MCP : ajout, actualisation des outils, activation outil par outil, suppression. */
public class McpSettingsActivity extends AppCompatActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");

    private final Handler main = new Handler(Looper.getMainLooper());
    private float density;
    private LinearLayout root;
    private LinearLayout serverList;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Serveurs MCP");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Branche des outils distants (Streamable HTTP, jeton Bearer facultatif). "
                + "Après ajout, « Actualiser » découvre les outils ; ils sont désactivés par "
                + "défaut : active uniquement ceux que Pégase peut utiliser. Leurs résultats "
                + "sont traités comme des données externes non fiables.");
        help.setTextColor(C_MUTED);
        help.setTextSize(13);
        help.setPadding(0, dp(10), 0, dp(12));
        root.addView(help);

        Button add = button("Ajouter un serveur", C_CYAN);
        add.setOnClickListener(v -> showAddDialog());
        root.addView(add, wrapLp());

        serverList = new LinearLayout(this);
        serverList.setOrientation(LinearLayout.VERTICAL);
        root.addView(serverList, wrapLp());

        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        render();
    }

    private void render() {
        serverList.removeAllViews();
        List<McpStore.Server> servers = new McpStore(this).servers();
        if (servers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucun serveur configuré.");
            empty.setTextColor(C_MUTED);
            empty.setPadding(0, dp(12), 0, 0);
            serverList.addView(empty);
            return;
        }
        for (McpStore.Server s : servers) serverList.addView(serverCard(s), wrapLp());
    }

    private LinearLayout serverCard(McpStore.Server s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBg());

        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setTypeface(null, Typeface.BOLD);
        card.addView(name);

        int enabled = 0;
        for (McpStore.ToolEntry t : s.tools) if (t.enabled) enabled++;
        TextView info = new TextView(this);
        info.setText(s.url + "\n" + s.tools.size() + " outil(s) découvert(s), " + enabled
                + " activé(s)");
        info.setTextColor(C_MUTED);
        info.setTextSize(12);
        card.addView(info);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("Actualiser", C_CYAN);
        refresh.setOnClickListener(v -> refresh(s));
        Button tools = button("Outils", Color.WHITE);
        tools.setOnClickListener(v -> showToolsDialog(s));
        Button del = button("Supprimer", C_MUTED);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Supprimer « " + s.name + " » ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    new McpStore(this).remove(s.id);
                    McpStore.reload(this);
                    render();
                })
                .setNegativeButton("Annuler", null).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(refresh, lp);
        row.addView(tools, lp);
        row.addView(del, lp);
        card.addView(row);
        return card;
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = field("Nom (ex. Notes)", InputType.TYPE_CLASS_TEXT);
        EditText url = field("URL (https://…/mcp)", InputType.TYPE_TEXT_VARIATION_URI);
        EditText token = field("Jeton Bearer (facultatif)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(name);
        form.addView(url);
        form.addView(token);
        new AlertDialog.Builder(this)
                .setTitle("Nouveau serveur MCP")
                .setView(form)
                .setPositiveButton("Ajouter", (d, w) -> {
                    try {
                        McpStore.Server s = new McpStore(this).add(
                                name.getText().toString(), url.getText().toString(),
                                token.getText().toString());
                        render();
                        refresh(s);
                    } catch (McpException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annuler", null).show();
    }

    private void refresh(McpStore.Server s) {
        Toast.makeText(this, "Découverte des outils…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                McpClient client = new McpClient(new McpTransport.Http(), s.url, s.token);
                client.initialize();
                List<McpClient.ToolInfo> tools = client.listTools();
                new McpStore(getApplicationContext()).updateTools(s.id, tools);
                McpStore.reload(getApplicationContext());
                message = tools.size() + " outil(s) trouvé(s). Active ceux que tu veux via « Outils ».";
            } catch (McpException e) {
                message = e.getMessage();
            }
            final String toast = message;
            main.post(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
                render();
            });
        }, "mcp-refresh").start();
    }

    private void showToolsDialog(McpStore.Server s) {
        if (s.tools.isEmpty()) {
            Toast.makeText(this, "Aucun outil : appuie d'abord sur « Actualiser ».",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[s.tools.size()];
        boolean[] checked = new boolean[s.tools.size()];
        for (int i = 0; i < labels.length; i++) {
            McpStore.ToolEntry t = s.tools.get(i);
            String d = t.description.replace('\n', ' ');
            labels[i] = t.name + (d.isEmpty() ? "" : " — " + (d.length() > 70
                    ? d.substring(0, 69) + "…" : d));
            checked[i] = t.enabled;
        }
        new AlertDialog.Builder(this)
                .setTitle("Outils de " + s.name)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    McpStore store = new McpStore(this);
                    for (int i = 0; i < checked.length; i++) {
                        store.setToolEnabled(s.id, s.tools.get(i).name, checked[i]);
                    }
                    McpStore.reload(this);
                    render();
                })
                .setNegativeButton("Annuler", null).show();
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(cardBg());
        return b;
    }

    private GradientDrawable cardBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#22FFFFFF"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return bg;
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
