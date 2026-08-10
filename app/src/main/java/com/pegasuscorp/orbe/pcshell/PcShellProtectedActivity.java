package com.pegasuscorp.orbe.pcshell;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Activité Terminal PC protégée par biométrie (session 30 min, re-auth en arrière-plan).
 */
public abstract class PcShellProtectedActivity extends AppCompatActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");

    private boolean contentReady;
    private boolean authenticating;
    private float density;

    @Override
    protected final void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        onBeforeProtectedCreate(getIntent());
        if (tryEnterProtectedContent(savedInstanceState)) {
            return;
        }
        showLockScreen(false);
        requestUnlock(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        onBeforeProtectedCreate(intent);
        if (contentReady) {
            onProtectedNewIntent(intent);
        }
    }

    /** Deep link / extras avant le gate biométrique. */
    protected void onBeforeProtectedCreate(Intent intent) {
    }

    /** Intent reçu alors que le contenu protégé est déjà affiché. */
    protected void onProtectedNewIntent(Intent intent) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (authenticating) return;
        if (contentReady && !PcShellSession.isUnlocked()) {
            PcShellKeyCache.lock();
            showLockScreen(true);
            requestUnlock(null);
        }
    }

    protected boolean isProtectedContentReady() {
        return contentReady;
    }

    protected abstract void onCreateProtected(Bundle savedInstanceState);

    /** Ré-initialisation clés SSH — ne doit jamais crasher l'activité. */
    protected final void ensureSshKeysSafe() {
        try {
            SshKeyManager.ensureKeyPair(this);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Toast.makeText(this,
                    "Clé SSH : " + msg + " — réessaie après déverrouillage biométrique.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryEnterProtectedContent(Bundle savedInstanceState) {
        if (!PcShellSession.isUnlocked()) {
            return false;
        }
        if (!PcShellKeyCache.isLoaded()) {
            try {
                PcShellKeyCache.unlock(this);
            } catch (Exception ignored) {
            }
        }
        if (!PcShellKeyCache.isLoaded()) {
            return false;
        }
        contentReady = true;
        onCreateProtected(savedInstanceState);
        return true;
    }

    private void showLockScreen(boolean relock) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView icon = new TextView(this);
        icon.setText("🔒");
        icon.setTextSize(36);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView title = new TextView(this);
        title.setText("Terminal PC verrouillé");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(8));
        root.addView(title);

        TextView msg = new TextView(this);
        msg.setText(relock
                ? "Session expirée ou app en arrière-plan.\nRé-authentifie-toi pour continuer."
                : "Accès au shell distant et à la clé SSH protégés par biométrie.\nSession valide 30 minutes.");
        msg.setTextColor(C_MUTED);
        msg.setTextSize(13);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg);

        Button unlock = new Button(this);
        unlock.setText("Déverrouiller");
        unlock.setAllCaps(false);
        unlock.setTextColor(C_CYAN);
        unlock.setOnClickListener(v -> requestUnlock(null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        unlock.setLayoutParams(lp);
        root.addView(unlock);

        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(24) + bars.left, dp(24) + bars.top,
                    dp(24) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void requestUnlock(Bundle savedInstanceState) {
        if (authenticating) return;
        if (!PcShellBiometricGate.canAuthenticate(this)) {
            Toast.makeText(this, PcShellBiometricGate.unavailableMessage(this), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        authenticating = true;
        PcShellBiometricGate.authenticate(this, new PcShellBiometricGate.Callback() {
            @Override
            public void onUnlocked() {
                authenticating = false;
                contentReady = true;
                getWindow().getDecorView().post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    onCreateProtected(savedInstanceState);
                });
            }

            @Override
            public void onFailed(String message) {
                authenticating = false;
                Toast.makeText(PcShellProtectedActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onCancelled() {
                authenticating = false;
                if (!contentReady) {
                    finish();
                }
            }
        });
    }

    protected int dp(int v) {
        return Math.round(v * density);
    }
}
