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
            if (actionId == EditorInfo.IME_ACTION_SEND) {
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
        addBubble(text, false);
    }

    public void updateAssistantPartial(String text) {
        if (TextUtils.isEmpty(text)) return;
        setStatus("…");
        int count = messagesHost.getChildCount();
        if (count > 0) {
            View last = messagesHost.getChildAt(count - 1);
            Object tag = last.getTag();
            if (TAG_WELCOME.equals(tag)) {
                addBubble(text, false);
                scrollToBottom();
                return;
            }
            if (Boolean.FALSE.equals(tag) && last instanceof TextView) {
                ((TextView) last).setText(text);
                scrollToBottom();
                return;
            }
        }
        addBubble(text, false);
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
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        bubble.setBackground(bubbleBg(user));
        return bubble;
    }

    private LinearLayout.LayoutParams bubbleLp(boolean user) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        if (user) lp.leftMargin = dp(24);
        else lp.rightMargin = dp(24);
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
