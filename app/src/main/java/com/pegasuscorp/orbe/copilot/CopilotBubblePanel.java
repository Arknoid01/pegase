package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.ui.OrbeTokens;
import com.pegasuscorp.orbe.voice.PttTouchHelper;
import com.pegasuscorp.orbe.voice.VoicePushToTalk;

/**
 * Bulle messenger attachée à l'orbe copilote — messages + saisie + actions rapides.
 */
public final class CopilotBubblePanel extends FrameLayout {

    private static final String TAG_WELCOME = "welcome";

    private static final String TAG_CONFIRM = "confirm";

    public interface Listener {
        void onSend(String text);
        void onCaptureScreen();
        void onRememberScreen();
        void onClose();
        void onOpenPegase();
        /** PTT transcript depuis la bulle. */
        default void onPttTranscript(String text) {}
    }

    private final float density;
    private final LinearLayout messagesHost;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView statusView;
    private Listener listener;
    private boolean sending;

    public CopilotBubblePanel(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setBackground(bubbleBg());
        setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, lpMatchWrap());

        TextView title = new TextView(ctx);
        title.setText(ctx.getString(R.string.copilot_title));
        title.setTextColor(OrbeTokens.COLOR_CYAN);
        title.setTextSize(14);
        title.setTypeface(OrbeTokens.typeMedium());
        header.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = actionChip(ctx, "✕");
        closeBtn.setTextColor(OrbeTokens.COLOR_MUTED);
        closeBtn.setOnClickListener(v -> {
            if (listener != null) listener.onClose();
        });
        header.addView(closeBtn, lpWrap());

        statusView = new TextView(ctx);
        statusView.setTextColor(OrbeTokens.COLOR_MUTED);
        statusView.setTextSize(11);
        statusView.setVisibility(GONE);
        root.addView(statusView, lpMatchWrap());

        scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(6);
        scrollLp.bottomMargin = dp(6);
        root.addView(scroll, scrollLp);

        messagesHost = new LinearLayout(ctx);
        messagesHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messagesHost, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        root.addView(actions, lpMatchWrap());

        TextView capBtn = actionChip(ctx, ctx.getString(R.string.copilot_action_screen));
        capBtn.setOnClickListener(v -> {
            if (listener != null) listener.onCaptureScreen();
        });
        actions.addView(capBtn, actionLp());

        TextView memBtn = actionChip(ctx, ctx.getString(R.string.copilot_action_remember));
        memBtn.setOnClickListener(v -> {
            if (listener != null) listener.onRememberScreen();
        });
        actions.addView(memBtn, actionLp());

        TextView openBtn = actionChip(ctx, ctx.getString(R.string.copilot_action_open_pegase));
        openBtn.setOnClickListener(v -> {
            if (listener != null) listener.onOpenPegase();
        });
        actions.addView(openBtn, actionLp());

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputRowLp = lpMatchWrap();
        inputRowLp.topMargin = dp(6);
        root.addView(inputRow, inputRowLp);

        input = new EditText(ctx);
        input.setHint(ctx.getString(R.string.copilot_hint_message));
        input.setHintTextColor(OrbeTokens.COLOR_MUTED);
        input.setTextColor(OrbeTokens.COLOR_TEXT);
        input.setTextSize(14);
        input.setBackground(inputBg());
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        input.setMaxLines(3);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnClickListener(v -> focusInput());
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showKeyboard();
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            // Envoi : bouton « Envoyer » du clavier, ou touche Entrée (sans Maj)
            boolean enterKey = event != null
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && !event.isShiftPressed();
            if (actionId == EditorInfo.IME_ACTION_SEND || enterKey) {
                submitInput();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        inputRow.addView(input, inputLp);

        TextView pttBtn = actionChip(ctx, "🎤");
        pttBtn.setContentDescription(ctx.getString(R.string.voice_ptt_mic));
        PttTouchHelper.attach(pttBtn, ctx, null, VoicePushToTalk.Channel.COPILOT,
                new VoicePushToTalk.Callback() {
                    @Override
                    public void onTranscript(String text) {
                        if (listener != null) listener.onPttTranscript(text);
                    }

                    @Override
                    public void onListeningChanged(boolean listening) {
                        pttBtn.setAlpha(listening ? 1f : 0.6f);
                    }
                });
        LinearLayout.LayoutParams pttLp = actionLp();
        inputRow.addView(pttBtn, pttLp);

        TextView sendBtn = actionChip(ctx, "➤");
        sendBtn.setOnClickListener(v -> submitInput());
        LinearLayout.LayoutParams sendLp = actionLp();
        sendLp.leftMargin = dp(6);
        inputRow.addView(sendBtn, sendLp);

        addWelcome();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Focus + ouverture clavier (bulle overlay focusable). */
    public void focusInput() {
        if (input == null || !input.isEnabled()) return;
        input.requestFocus();
        showKeyboard();
    }

    public void hideKeyboard() {
        if (input == null) return;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        input.clearFocus();
    }

    private void showKeyboard() {
        if (input == null) return;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void setSending(boolean sending) {
        this.sending = sending;
        if (input != null) input.setEnabled(!sending);
    }

    public void addUserMessage(String text) {
        dismissWelcome();
        addBubble(text, true);
    }

    public void showConfirm(String question, Runnable onConfirm, Runnable onCancel) {
        if (TextUtils.isEmpty(question)) return;
        dismissWelcome();
        clearPendingConfirm();
        setStatus(null);

        Context ctx = getContext();
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setTag(TAG_CONFIRM);
        block.setBackground(bubbleBg(false));
        block.setPadding(dp(10), dp(6), dp(10), dp(6));

        TextView q = new TextView(ctx);
        q.setText(question);
        q.setTextColor(OrbeTokens.COLOR_TEXT);
        q.setTextSize(13);
        block.addView(q, lpMatchWrap());

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);

        TextView yes = actionChip(ctx, ctx.getString(R.string.copilot_confirm_yes));
        yes.setOnClickListener(v -> {
            clearPendingConfirm();
            if (onConfirm != null) onConfirm.run();
        });
        actions.addView(yes, actionLp());

        TextView no = actionChip(ctx, ctx.getString(R.string.copilot_confirm_no));
        no.setOnClickListener(v -> {
            clearPendingConfirm();
            if (onCancel != null) onCancel.run();
        });
        actions.addView(no, actionLp());

        block.addView(actions, lpMatchWrap());

        LinearLayout.LayoutParams lp = bubbleLp(false);
        messagesHost.addView(block, lp);
        scrollToBottom();
    }

    private void clearPendingConfirm() {
        for (int i = messagesHost.getChildCount() - 1; i >= 0; i--) {
            if (TAG_CONFIRM.equals(messagesHost.getChildAt(i).getTag())) {
                messagesHost.removeViewAt(i);
            }
        }
    }

    public void addAssistantMessage(String text) {
        setStatus(null);
        dismissWelcome();
        if (TextUtils.isEmpty(text)) return;
        // Forme Eva : texte sans bulle ; le partiel déjà affiché est réutilisé
        if (revealTarget != null) revealReset(null);
        int count = messagesHost.getChildCount();
        TextView target;
        if (count > 0 && Boolean.FALSE.equals(messagesHost.getChildAt(count - 1).getTag())
                && messagesHost.getChildAt(count - 1) instanceof TextView) {
            target = (TextView) messagesHost.getChildAt(count - 1);
        } else {
            target = createBubbleView("", false);
            target.setTag(false);
            messagesHost.addView(target, bubbleLp(false));
        }
        // Écriture simulée : la réponse complète s'écrit mot à mot, à vitesse constante
        revealReset(target);
        revealFull = text;
        revealConstant = true;
        revealArrivals.add(new long[] {System.currentTimeMillis() - REVEAL_DELAY_MS, text.length()});
        target.post(revealTick);
        addDetailsRow(text);
        scrollToBottom();
    }

    /** Bouton « ⋯ » sous la réponse : déplie les sources et les outils (carte du tour). */
    private void addDetailsRow(String replyText) {
        TextView details = new TextView(getContext());
        details.setTextSize(12);
        details.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        details.setPadding(0, dp(2), 0, dp(4));
        details.setVisibility(View.GONE);

        TextView toggle = new TextView(getContext());
        toggle.setText("⋯");
        toggle.setTextSize(14);
        toggle.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        toggle.setPadding(0, 0, dp(6), dp(2));
        toggle.setOnClickListener(v -> {
            if (details.getVisibility() == View.VISIBLE) {
                details.setVisibility(View.GONE);
                return;
            }
            details.setText(detailsText(replyText));
            details.setVisibility(View.VISIBLE);
        });

        messagesHost.addView(toggle, bubbleLp(false));
        messagesHost.addView(details, bubbleLp(false));
    }

    /** Texte des sources et outils d'une réponse, ou un message si rien n'a été noté. */
    private static String detailsText(String replyText) {
        com.pegasuscorp.orbe.diag.ReasoningCard card =
                com.pegasuscorp.orbe.diag.ReasoningStore.findForReply(replyText);
        StringBuilder sb = new StringBuilder();
        if (card != null && card.webSources != null && !card.webSources.isEmpty()) {
            sb.append("Sources\n");
            for (String s : card.webSources) sb.append("• ").append(s).append('\n');
        }
        if (card != null && card.toolsUsed != null && !card.toolsUsed.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Outils\n");
            for (com.pegasuscorp.orbe.diag.ReasoningCard.ToolStep t : card.toolsUsed) {
                sb.append("• ").append(t.name).append(t.ok ? "" : " (échec)").append('\n');
            }
        }
        if (card != null) {
            if (card.memoriesUsed != null && !card.memoriesUsed.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("Souvenirs utilisés\n");
                for (String m : card.memoriesUsed) sb.append("• ").append(m).append('\n');
            }
            if (card.profileUsed != null && !card.profileUsed.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("Profil utilisé\n");
                for (String p : card.profileUsed) sb.append("• ").append(p).append('\n');
            }
            if (card.backend != null && !card.backend.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("Modèle : ").append(card.backend);
                if (card.latencyMs > 0) sb.append(" · ").append(card.latencyMs / 1000).append(" s");
                sb.append('\n');
            }
        }
        if (sb.length() == 0) {
            return card == null
                    ? "Détails indisponibles pour cette réponse."
                    : "Réponse directe : aucune source, aucun outil ni souvenir utilisé.";
        }
        return sb.toString().trim();
    }

    // --- Écriture progressive de la réponse en cours (mots entiers, décalage 500 ms) ---
    private TextView revealTarget;
    private String revealFull = "";
    private int revealLen = 0;
    private float revealF = 0f;
    /** Vrai pour l'écriture simulée de la réponse finale : vitesse constante, pas de rattrapage. */
    private boolean revealConstant = false;
    private final java.util.ArrayList<long[]> revealArrivals = new java.util.ArrayList<>();
    private static final long REVEAL_DELAY_MS = 500L;
    private static final int REVEAL_FRAME_MS = 16;

    private final Runnable revealTick = new Runnable() {
        @Override public void run() {
            TextView t = revealTarget;
            if (t == null) return;
            int mature = matureLength(System.currentTimeMillis());
            int target = Math.min(revealFull.length(), mature);
            if (revealLen < target) {
                float backlog = target - revealF;
                float step = revealConstant
                        ? 1.0f  // ≈ 60 car./s, comme une frappe régulière
                        : Math.max(0.045f * REVEAL_FRAME_MS, backlog * REVEAL_FRAME_MS / 180f);
                revealF = Math.min(target, revealF + step);
                revealLen = (int) revealF;
                t.setText(revealFull.substring(0, revealLen));
                scrollToBottom();
            }
            if (revealLen < revealFull.length()) t.postDelayed(this, REVEAL_FRAME_MS);
        }
    };

    private int matureLength(long now) {
        int len = 0;
        for (long[] a : revealArrivals) {
            if (a[0] <= now - REVEAL_DELAY_MS) len = (int) a[1];
            else break;
        }
        return len;
    }

    /** Repart de zéro sur une nouvelle cible (ou arrête l'écriture si null). */
    private void revealReset(TextView target) {
        if (revealTarget != null) revealTarget.removeCallbacks(revealTick);
        revealTarget = target;
        revealFull = "";
        revealLen = 0;
        revealF = 0f;
        revealConstant = false;
        revealArrivals.clear();
        if (target != null) target.setText("");
    }

    /** Coupe au dernier espace : un mot en cours d'écriture n'est pas montré. */
    private static String wholeWords(String s) {
        if (s.isEmpty() || Character.isWhitespace(s.charAt(s.length() - 1))) return s;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return s.substring(0, i + 1);
        }
        return "";
    }

    public void updateAssistantPartial(String text) {
        if (TextUtils.isEmpty(text)) return;
        setStatus("…");
        int count = messagesHost.getChildCount();
        TextView target = null;
        if (count > 0) {
            View last = messagesHost.getChildAt(count - 1);
            if (Boolean.FALSE.equals(last.getTag()) && last instanceof TextView) {
                target = (TextView) last;
            }
        }
        if (target == null) {
            target = createBubbleView("", false);
            target.setTag(false);
            messagesHost.addView(target, bubbleLp(false));
        }
        if (target != revealTarget) revealReset(target);
        String cut = wholeWords(text);
        if (!cut.startsWith(revealFull)) revealReset(target);
        revealFull = cut;
        long now = System.currentTimeMillis();
        if (revealArrivals.isEmpty()
                || cut.length() > revealArrivals.get(revealArrivals.size() - 1)[1]) {
            revealArrivals.add(new long[] {now, cut.length()});
        }
        target.removeCallbacks(revealTick);
        target.post(revealTick);
        scrollToBottom();
    }

    public void setStatus(String status) {
        if (TextUtils.isEmpty(status)) {
            statusView.setVisibility(GONE);
            statusView.setText("");
        } else {
            statusView.setVisibility(VISIBLE);
            statusView.setText(status);
        }
    }

    public void showError(String message) {
        setStatus(null);
        if (!TextUtils.isEmpty(message)) {
            addBubble("⚠ " + message, false);
        }
    }

    private void submitInput() {
        if (sending) return;
        String text = input.getText() != null ? input.getText().toString().trim() : "";
        if (text.isEmpty()) return;
        input.setText("");
        if (listener != null) listener.onSend(text);
    }

    private void addWelcome() {
        TextView bubble = createBubbleView(
                getContext().getString(R.string.copilot_welcome), false);
        bubble.setTag(TAG_WELCOME);
        LinearLayout.LayoutParams lp = bubbleLp(false);
        messagesHost.addView(bubble, lp);
    }

    private void dismissWelcome() {
        for (int i = 0; i < messagesHost.getChildCount(); i++) {
            View child = messagesHost.getChildAt(i);
            if (TAG_WELCOME.equals(child.getTag())) {
                messagesHost.removeViewAt(i);
                return;
            }
        }
    }

    private void addBubble(String text, boolean user) {
        if (TextUtils.isEmpty(text)) return;
        TextView bubble = createBubbleView(text, user);
        bubble.setTag(user);
        messagesHost.addView(bubble, bubbleLp(user));
        scrollToBottom();
    }

    private TextView createBubbleView(String text, boolean user) {
        TextView bubble = new TextView(getContext());
        bubble.setText(text);
        bubble.setTextSize(13);
        bubble.setTextColor(OrbeTokens.COLOR_TEXT);
        if (user) {
            bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
            bubble.setBackground(bubbleBg(true));
        } else {
            // réponse de Pégase : texte sur le fond, sans bulle (couleurs inchangées)
            bubble.setPadding(0, dp(2), 0, dp(2));
        }
        return bubble;
    }

    private LinearLayout.LayoutParams bubbleLp(boolean user) {
        if (!user) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.topMargin = dp(4);
            lp.bottomMargin = dp(4);
            return lp;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        lp.leftMargin = dp(24);
        return lp;
    }

    public void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams actionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        return lp;
    }

    private TextView actionChip(Context ctx, String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(OrbeTokens.COLOR_CYAN);
        tv.setTextSize(12);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setBackground(chipBg());
        return tv;
    }

    private GradientDrawable bubbleBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(OrbeTokens.COLOR_CARD);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), OrbeTokens.COLOR_SEP);
        return d;
    }

    private GradientDrawable bubbleBg(boolean user) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(user ? OrbeTokens.COLOR_USER_BUBBLE : OrbeTokens.COLOR_CARD_ACTIVE);
        d.setCornerRadius(dp(12));
        return d;
    }

    private GradientDrawable chipBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(OrbeTokens.COLOR_CHIP_BG);
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), OrbeTokens.COLOR_CHIP_STROKE);
        return d;
    }

    private GradientDrawable inputBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(OrbeTokens.COLOR_INPUT_BG);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), OrbeTokens.COLOR_SEP);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
