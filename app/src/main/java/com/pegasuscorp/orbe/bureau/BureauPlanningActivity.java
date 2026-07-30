package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.ui.ThinkingView;

/**
 * Interview de planification plein écran → matérialisation projet structuré.
 */
public class BureauPlanningActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE_HINT = "title_hint";
    public static final String EXTRA_OPEN_SLUG = "open_slug";

    private static final int BG = Color.parseColor("#0B0E14");
    private static final int SURFACE = Color.parseColor("#141A22");
    private static final int ACCENT = Color.parseColor("#5B8DEF");
    private static final int TEXT = Color.parseColor("#E8EEF7");
    private static final int MUTED = Color.parseColor("#8B9BB4");
    private static final int BTN_SECONDARY = Color.parseColor("#1C2430");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BureauPlanningDraftStore.Draft draft;
    private LinearLayout threadList;
    private ScrollView threadScroll;
    private EditText input;
    private Button createBtn;
    private TextView statusLabel;
    private ThinkingView thinkingView;
    private boolean busy;

    public static void open(Context context) {
        open(context, null);
    }

    public static void open(Context context, String titleHint) {
        Intent i = new Intent(context, BureauPlanningActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (titleHint != null) i.putExtra(EXTRA_TITLE_HINT, titleHint);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        PegaseSession.get(this).init(new SessionContext(Channel.BUREAU, false));

        String hint = getIntent() != null ? getIntent().getStringExtra(EXTRA_TITLE_HINT) : null;
        draft = BureauPlanningDraftStore.create(this, hint);
        Trace.bureauAction("planning_open", draft.id);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button cancel = secondaryButton("← Annuler", d);
        cancel.setOnClickListener(v -> {
            BureauPlanningDraftStore.delete(this, draft.id);
            finish();
        });
        top.addView(cancel);

        TextView title = new TextView(this);
        title.setText("Nouveau projet");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding((int) (10 * d), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        createBtn = primaryButton("Créer le plan", d);
        createBtn.setEnabled(false);
        createBtn.setAlpha(0.4f);
        createBtn.setOnClickListener(v -> materialize());
        top.addView(createBtn);
        root.addView(top);

        statusLabel = new TextView(this);
        statusLabel.setTextColor(MUTED);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLabel.setPadding(0, (int) (8 * d), 0, (int) (4 * d));
        statusLabel.setText("Décris ton idée, réponds à Pégase — puis « Créer le plan ».");
        root.addView(statusLabel);

        thinkingView = new ThinkingView(this);
        root.addView(thinkingView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        threadScroll = new ScrollView(this);
        threadList = new LinearLayout(this);
        threadList.setOrientation(LinearLayout.VERTICAL);
        threadScroll.addView(threadList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(threadScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, (int) (8 * d), 0, 0);

        input = new EditText(this);
        input.setHint(hint != null && !hint.isEmpty()
                ? "Parle de « " + hint + " »…"
                : "Décris ton projet en une phrase…");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BTN_SECONDARY);
        bg.setCornerRadius(10f * d);
        input.setBackground(bg);
        input.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = primaryButton("Envoyer", d);
        send.setOnClickListener(v -> sendMessage());
        inputRow.addView(send);
        root.addView(inputRow);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bottom);
            return insets;
        });

        setContentView(root);

        // Accueil Pégase local (sans LLM) pour démarrer
        String welcome = draft.titleHint != null && !draft.titleHint.isEmpty()
                ? "Parle-moi de « " + draft.titleHint + " » — en une phrase, c'est quoi l'idée ?"
                : "Parle-moi de ton projet — en une phrase, c'est quoi l'idée ?";
        appendBubble(false, welcome, true);
    }

    private void sendMessage() {
        if (busy) {
            Toast.makeText(this, "Pégase réfléchit…", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = input.getText() != null ? input.getText().toString().trim() : "";
        if (msg.isEmpty()) {
            input.requestFocus();
            return;
        }
        input.setText("");
        appendBubble(true, msg, true);
        updateCreateButton("READY".equals(draft.phase));
        busy = true;
        statusLabel.setText("Pégase…");
        thinkingView.reset();
        thinkingView.onLlmStart();

        PegaseSession.get(this).completeBureauPlanningTurn(
                draft.titleHint, draft.turns, msg, new ChatBackend.OnReply() {
                    @Override
                    public void onLlmReply(LlmReply reply) {
                        ui.post(() -> onAssistant(reply != null ? reply.content : ""));
                    }

                    @Override
                    public void onReply(String text) {
                        ui.post(() -> onAssistant(text));
                    }

                    @Override
                    public void onError(String error) {
                        ui.post(() -> {
                            busy = false;
                            thinkingView.onError();
                            statusLabel.setText("Erreur");
                            appendBubble(false, error != null ? error : "Erreur", true);
                        });
                    }
                });
    }

    private void onAssistant(String raw) {
        busy = false;
        thinkingView.onComplete();
        BureauPlanningBrain.InterviewTurnResult parsed =
                BureauPlanningBrain.parseInterviewReply(raw);
        appendBubble(false, parsed.speakText.isEmpty() ? "(pas de réponse)" : parsed.speakText, true);
        draft.phase = parsed.isReady() ? "READY" : "NEED_INFO";
        draft.summary = parsed.summary;
        BureauPlanningDraftStore.save(this, draft);
        updateCreateButton(parsed.isReady());
    }

    /** Active le bouton dès qu'il y a au moins un message utilisateur (force possible). */
    private void updateCreateButton(boolean llmReady) {
        int userTurns = 0;
        if (draft != null) {
            for (BureauChatStore.Turn t : draft.turns) {
                if (t != null && t.fromUser) userTurns++;
            }
        }
        boolean enable = llmReady || userTurns >= 1;
        createBtn.setEnabled(enable);
        createBtn.setAlpha(enable ? 1f : 0.4f);
        if (llmReady) {
            statusLabel.setText("Prêt — tu peux créer le plan.");
        } else if (enable) {
            statusLabel.setText("Tu peux créer le plan maintenant, ou continuer à préciser.");
        } else {
            statusLabel.setText("Envoie un premier message pour décrire le projet.");
        }
    }

    private void materialize() {
        if (busy) return;
        int userTurns = 0;
        for (BureauChatStore.Turn t : draft.turns) {
            if (t != null && t.fromUser) userTurns++;
        }
        if (userTurns < 1) {
            Toast.makeText(this, "Décris d'abord ton projet.", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        createBtn.setEnabled(false);
        statusLabel.setText("Création du plan…");
        thinkingView.reset();
        thinkingView.onLlmStart();

        PegaseSession.get(this).completeBureauPlanningMaterialize(
                draft.titleHint, draft.turns, new ChatBackend.OnReply() {
                    @Override
                    public void onLlmReply(LlmReply reply) {
                        ui.post(() -> onMaterialize(reply != null ? reply.content : ""));
                    }

                    @Override
                    public void onReply(String text) {
                        ui.post(() -> onMaterialize(text));
                    }

                    @Override
                    public void onError(String error) {
                        ui.post(() -> {
                            busy = false;
                            thinkingView.onError();
                            updateCreateButton("READY".equals(draft.phase));
                            statusLabel.setText("Erreur de création");
                            Toast.makeText(BureauPlanningActivity.this,
                                    error != null ? error : "Erreur", Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void onMaterialize(String raw) {
        busy = false;
        thinkingView.onComplete();
        String fallback = draft.titleHint;
        if (fallback == null || fallback.isEmpty()) fallback = "Nouveau projet";
        BureauProject project = BureauPlanningBrain.parseMaterializeJson(raw, fallback);
        if (project == null || !BureauPlanningBrain.hasSubstance(project)) {
            createBtn.setEnabled(true);
            updateCreateButton("READY".equals(draft.phase));
            statusLabel.setText("Plan incomplet — réessaie ou précise encore");
            Toast.makeText(this,
                    "Le plan généré était vide. Continue l'entretien puis recrée, "
                            + "ou attends READY.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        BureauCommandExecutor.Result r = BureauCommandExecutor.createFromMaterialized(
                this, project, true);
        if (!r.ok || r.project == null) {
            createBtn.setEnabled(true);
            statusLabel.setText("Échec");
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
            return;
        }
        BureauPlanningDraftStore.delete(this, draft.id);
        Trace.bureauAction("planning_created", r.project.slug);
        Intent open = new Intent(this, BureauActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        open.putExtra(EXTRA_OPEN_SLUG, r.project.slug);
        startActivity(open);
        finish();
    }

    private void appendBubble(boolean fromUser, String text, boolean persist) {
        float d = getResources().getDisplayMetrics().density;
        TextView bubble = new TextView(this);
        bubble.setText((fromUser ? "Toi : " : "Pégase : ") + (text == null ? "" : text));
        bubble.setTextColor(TEXT);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bubble.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        bubble.setBackgroundColor(fromUser
                ? Color.parseColor("#1A2A40")
                : Color.parseColor("#1C2430"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * d);
        threadList.addView(bubble, lp);
        if (persist && draft != null) {
            BureauPlanningDraftStore.appendTurn(this, draft, fromUser, text);
        }
        threadScroll.post(() -> threadScroll.fullScroll(View.FOCUS_DOWN));
    }

    private Button primaryButton(String label, float d) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(10f * d);
        b.setBackground(bg);
        b.setMinHeight((int) (40 * d));
        b.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        return b;
    }

    private Button secondaryButton(String label, float d) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BTN_SECONDARY);
        bg.setCornerRadius(10f * d);
        b.setBackground(bg);
        b.setMinHeight((int) (40 * d));
        b.setPadding((int) (10 * d), 0, (int) (10 * d), 0);
        return b;
    }
}
