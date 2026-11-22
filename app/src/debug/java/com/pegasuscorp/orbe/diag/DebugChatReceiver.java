package com.pegasuscorp.orbe.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.session.SessionObserver;
import com.pegasuscorp.orbe.tools.ToolResult;

/**
 * DEBUG UNIQUEMENT (src/debug) : envoie une phrase à Pégase comme si elle était tapée dans la
 * discussion, et journalise la réponse avec le temps écoulé (tag {@code PegaseDebug}).
 *
 * <pre>
 * adb shell "am broadcast -n com.pegasuscorp.orbe/.diag.DebugChatReceiver --es text 'lance un minuteur de 2 minutes'"
 * adb logcat -s PegaseDebug
 * </pre>
 */
public class DebugChatReceiver extends BroadcastReceiver {

    private static final String TAG = "PegaseDebug";

    @Override
    public void onReceive(Context context, Intent intent) {
        String text = intent.getStringExtra("text");
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "extra « text » manquant");
            return;
        }
        final Context app = context.getApplicationContext();
        final long t0 = System.currentTimeMillis();
        new Handler(Looper.getMainLooper()).post(() -> {
            PegaseSession session = PegaseSession.get(app);
            session.init(new SessionContext(Channel.TEXT, false));
            Log.i(TAG, "→ " + text);
            // Écoute globale (les réponses d'outils ne passent pas toujours par l'observateur
            // ponctuel), retirée au premier résultat ou après 2 minutes.
            final SessionObserver[] holder = new SessionObserver[1];
            final Runnable detach = () -> { if (holder[0] != null) session.removeObserver(holder[0]); };
            holder[0] = new SessionObserver() {
                private String ms() { return (System.currentTimeMillis() - t0) + " ms"; }

                @Override public void onReply(String reply, boolean toolFired) {
                    if (!toolFired) session.removeObserver(this);
                    Log.i(TAG, "← [" + ms() + "] réponse" + (toolFired ? " (outil)" : "") + " : " + reply);
                }

                @Override public void onToolResult(ToolResult result) {
                    session.removeObserver(this);
                    Log.i(TAG, "← [" + ms() + "] résultat outil : "
                            + (result != null ? result.text : ""));
                }

                @Override public void onError(String message) {
                    session.removeObserver(this);
                    Log.i(TAG, "← [" + ms() + "] erreur : " + message);
                }

                @Override public void onToolStart(String toolId) {
                    Log.i(TAG, "   [" + ms() + "] outil " + toolId);
                }
            };
            session.addObserver(holder[0]);
            new Handler(Looper.getMainLooper()).postDelayed(detach, 120_000);
            session.send(text.trim(), null);
        });
    }
}
