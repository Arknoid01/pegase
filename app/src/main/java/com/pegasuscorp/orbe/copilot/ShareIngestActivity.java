package com.pegasuscorp.orbe.copilot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.pegasuscorp.orbe.R;

/**
 * Reçoit un partage texte (ACTION_SEND) et l'envoie en mémoire ou contexte nommé.
 */
public final class ShareIngestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)) {
            finish();
            return;
        }
        String type = intent.getType();
        if (type == null || !type.startsWith("text/")) {
            toast(getString(R.string.copilot_share_text_only));
            finish();
            return;
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        ShareIngestRouter.Result result = ShareIngestRouter.ingestSharedText(this, text, subject);
        toast(result.message);
        getWindow().getDecorView().postDelayed(this::finish, 1_200L);
    }

    private void toast(String msg) {
        if (msg != null && !msg.isEmpty()) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }
}
