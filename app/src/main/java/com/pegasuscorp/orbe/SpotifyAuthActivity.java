package com.pegasuscorp.orbe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.pegasuscorp.orbe.spotify.SpotifyAuthHelper;

/**
 * Callback OAuth Spotify : {@code com.pegasuscorp.orbe://spotify-callback?code=...}
 */
public class SpotifyAuthActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            finish();
            return;
        }
        Uri data = intent.getData();
        if (!SpotifyAuthHelper.REDIRECT_URI.equals(data.getScheme() + "://" + data.getHost())) {
            finish();
            return;
        }
        String error = data.getQueryParameter("error");
        if (error != null && !error.isEmpty()) {
            Toast.makeText(this, "Connexion Spotify annulée", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String code = data.getQueryParameter("code");
        if (code == null || code.isEmpty()) {
            Toast.makeText(this, "Code Spotify manquant", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        new Thread(() -> {
            try {
                SpotifyAuthHelper.exchangeAuthorizationCode(getApplicationContext(), code);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, "Spotify connecté ✓", Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, "Échec Spotify : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "SpotifyOAuth").start();
    }
}
