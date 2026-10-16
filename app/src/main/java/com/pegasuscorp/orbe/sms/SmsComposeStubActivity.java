package com.pegasuscorp.orbe.sms;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Exigé par Android pour devenir app SMS par défaut (écrire un SMS). Pégase ne
 * l'est que le temps du ménage : on renvoie vers l'écran pour rendre la main.
 */
public class SmsComposeStubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Toast.makeText(this, "Pégase n'écrit pas de SMS : remets Messages par défaut.",
                Toast.LENGTH_LONG).show();
        startActivity(SmsInbox.restoreDefaultIntent(this));
        finish();
    }
}
