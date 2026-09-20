package com.pegasuscorp.orbe.pcshell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Réglages Terminal PC — hostname Tailscale, port, user SSH, clé publique.
 */
public class PcShellSettingsActivity extends PcShellProtectedActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");
    private static final int C_CARD = Color.parseColor("#18FFFFFF");
    private static final int C_SEP = Color.parseColor("#22FFFFFF");

    private float density;
    private EditText hostField;
    private EditText portField;
    private EditText userField;
    private TextView publicKeyView;

    @Override
    protected void onCreateProtected(Bundle savedInstanceState) {
        density = getResources().getDisplayMetrics().density;
        ensureSshKeysSafe();
        SshKeyManager.KeyMaterial keys = loadKeyMaterialForDisplay();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Terminal PC · réglages");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        root.addView(sectionLabel("Connexion (Tailscale uniquement)"));
        hostField = field(root, "Hostname ou IP Tailscale", PcShellPrefs.getHost(this),
                "pegase-arch ou 100.x.x.x");
        portField = field(root, "Port SSH", String.valueOf(PcShellPrefs.getPort(this)), "22");
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        userField = field(root, "Utilisateur SSH", PcShellPrefs.getUser(this), "pegase-remote");

        Button saveConn = new Button(this);
        saveConn.setText("Enregistrer la connexion");
        saveConn.setAllCaps(false);
        saveConn.setTextColor(C_CYAN);
        saveConn.setBackground(cardBg());
        saveConn.setOnClickListener(v -> saveConnection());
        root.addView(saveConn, wrapLp());

        root.addView(sectionLabel("Appairage SSH (clé publique)"));
        TextView pairingHelp = new TextView(this);
        pairingHelp.setText(
                "1. Copie la clé publique ci-dessous.\n"
                + "2. Sur le PC Arch, ajoute-la dans :\n"
                + "   /home/pegase-remote/.ssh/authorized_keys\n"
                + "3. Voir docs/pc-shell-arch-setup.md pour le script complet.");
        pairingHelp.setTextColor(C_MUTED);
        pairingHelp.setTextSize(12);
        pairingHelp.setPadding(0, dp(4), 0, dp(8));
        root.addView(pairingHelp);

        publicKeyView = new TextView(this);
        String pubLine = keys.publicKeyLine;
        if (TextUtils.isEmpty(pubLine)) {
            pubLine = PcShellPrefs.getPublicKeyLine(this);
        }
        if (TextUtils.isEmpty(pubLine)) {
            pubLine = "(clé absente — appuie sur « Régénérer », copie la nouvelle clé sur le PC, "
                    + "puis réessaie une commande)";
            publicKeyView.setTextColor(C_MUTED);
        } else {
            publicKeyView.setTextColor(C_CYAN);
        }
        publicKeyView.setText(pubLine);
        publicKeyView.setTextSize(11);
        publicKeyView.setTypeface(Typeface.MONOSPACE);
        publicKeyView.setPadding(dp(12), dp(10), dp(12), dp(10));
        publicKeyView.setBackground(cardBg());
        publicKeyView.setTextIsSelectable(true);
        root.addView(publicKeyView, wrapLp());

        TextView fp = new TextView(this);
        String fingerprint = TextUtils.isEmpty(keys.publicKeyLine)
                ? ""
                : SshKeyManager.fingerprintSha256(keys.publicKeyLine);
        fp.setText(TextUtils.isEmpty(fingerprint)
                ? "Empreinte indisponible — régénère la clé si la connexion échoue."
                : "Empreinte (ssh-keygen -lf) :\n" + fingerprint
                        + "\nDoit être identique à : sudo ssh-keygen -lf authorized_keys");
        fp.setTextColor(C_MUTED);
        fp.setTextSize(10);
        fp.setTypeface(Typeface.MONOSPACE);
        fp.setPadding(0, dp(4), 0, dp(8));
        fp.setTextIsSelectable(true);
        root.addView(fp, wrapLp());

        LinearLayout keyActions = new LinearLayout(this);
        keyActions.setOrientation(LinearLayout.HORIZONTAL);
        Button copyKey = new Button(this);
        copyKey.setText("Copier la clé");
        copyKey.setAllCaps(false);
        copyKey.setBackground(cardBg());
        copyKey.setTextColor(Color.WHITE);
        copyKey.setOnClickListener(v -> copy(publicKeyView.getText().toString()));
        keyActions.addView(copyKey, weightLp());
        Button regen = new Button(this);
        regen.setText("Régénérer");
        regen.setAllCaps(false);
        regen.setBackground(cardBg());
        regen.setTextColor(Color.parseColor("#FFFF5252"));
        regen.setOnClickListener(v -> confirmRegenerate());
        keyActions.addView(regen, weightLp());
        root.addView(keyActions, wrapLp());

        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
    }

    private SshKeyManager.KeyMaterial loadKeyMaterialForDisplay() {
        try {
            return SshKeyManager.ensureKeyPair(this);
        } catch (Exception e) {
            return SshKeyManager.emptyKeyMaterial();
        }
    }

    private void saveConnection() {
        String host = hostField.getText().toString().trim();
        String portStr = portField.getText().toString().trim();
        String user = userField.getText().toString().trim();
        int port = PcShellPrefs.DEFAULT_PORT;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {
        }
        PcShellPrefs.setHost(this, host);
        PcShellPrefs.setPort(this, port);
        PcShellPrefs.setUser(this, user);
        Toast.makeText(this, "Connexion enregistrée.", Toast.LENGTH_SHORT).show();
    }

    private void confirmRegenerate() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Régénérer la clé SSH ?")
                .setMessage("L'ancienne clé ne fonctionnera plus. Tu devras remettre la nouvelle clé publique sur le PC.")
                .setPositiveButton("Régénérer", (d, w) -> {
                    SshKeyManager.KeyMaterial k = SshKeyManager.regenerate(this);
                    publicKeyView.setText(k.publicKeyLine);
                    Toast.makeText(this, "Nouvelle clé — mets à jour authorized_keys sur le PC.",
                            Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_CYAN);
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private EditText field(LinearLayout root, String label, String value, String hint) {
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(C_MUTED);
        lab.setTextSize(11);
        lab.setPadding(0, dp(6), 0, dp(2));
        root.addView(lab);
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setText(value);
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(C_MUTED);
        f.setBackground(cardBg());
        f.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(f, wrapLp());
        return f;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ssh-pubkey", text));
            Toast.makeText(this, "Clé publique copiée", Toast.LENGTH_SHORT).show();
        }
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), C_SEP);
        return g;
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(4));
        return lp;
    }
}
