package com.pegasuscorp.orbe.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ligne de raisonnement en temps réel (outils + « Pégase réfléchit… »).
 * Disparaît quand la réponse arrive.
 */
public class ThinkingView extends LinearLayout {

    private static final int CYAN = Color.parseColor("#35D0DD");
    private static final int MUTED = Color.parseColor("#8A8A8A");
    private static final int ERROR = Color.parseColor("#F44336");
    private static final int BG = Color.parseColor("#1A1A1A");

    private enum SlotState { RUNNING, OK, FAIL }

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView thinkingText;
    private ProgressBar spinner;
    private final LinkedHashMap<String, SlotState> tools = new LinkedHashMap<>();
    private boolean llmPhase;
    private boolean completing;
    private int pulseTick;
    private Runnable pulseLoop;
    private Runnable errorHide;

    public ThinkingView(Context context) {
        super(context);
        init(context);
    }

    public ThinkingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ThinkingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 12);
        setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(context, 12));
        setBackground(bg);

        spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        addView(spinner, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        thinkingText = new TextView(context);
        thinkingText.setTextColor(CYAN);
        thinkingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        thinkingText.setTypeface(thinkingText.getTypeface(), Typeface.ITALIC);
        thinkingText.setMaxLines(2);
        LayoutParams tp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(context, 10));
        addView(thinkingText, tp);

        setVisibility(GONE);
        setAlpha(0f);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** Réinitialise pour un nouveau tour. */
    public void reset() {
        cancelPending();
        tools.clear();
        llmPhase = false;
        completing = false;
        pulseTick = 0;
        if (thinkingText != null) {
            thinkingText.setTextColor(CYAN);
            thinkingText.setAlpha(1f);
            thinkingText.setScaleX(1f);
            thinkingText.setScaleY(1f);
            thinkingText.setText("");
        }
        if (spinner != null) spinner.setVisibility(VISIBLE);
    }

    public void onToolStart(String toolName) {
        runUi(() -> {
            if (completing) return;
            cancelErrorHide();
            String id = normalizeId(toolName);
            llmPhase = false;
            stopPulse();
            tools.put(id, SlotState.RUNNING);
            showFadeIn();
            renderToolsLine();
        });
    }

    public void onToolEnd(String toolName, boolean ok) {
        runUi(() -> {
            if (completing) return;
            String id = normalizeId(toolName);
            tools.put(id, ok ? SlotState.OK : SlotState.FAIL);
            showFadeIn();
            renderToolsLine();
            popCheck();
        });
    }

    public void onLlmStart() {
        runUi(() -> {
            if (completing) return;
            cancelErrorHide();
            llmPhase = true;
            showFadeIn();
            renderLlmLine();
            startPulse();
        });
    }

    public void onComplete() {
        runUi(() -> {
            if (getVisibility() != VISIBLE && getAlpha() < 0.01f) {
                setVisibility(GONE);
                setAlpha(0f);
                reset();
                return;
            }
            completing = true;
            stopPulse();
            cancelErrorHide();
            if (!tools.isEmpty() && thinkingText != null) {
                thinkingText.setTextColor(MUTED);
                thinkingText.setText(buildToolsLine(true) + " · LLM ✅");
            }
            animate().cancel();
            animate().alpha(0f).setDuration(200)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            setVisibility(GONE);
                            setAlpha(0f);
                            reset();
                        }
                    }).start();
        });
    }

    public void onError() {
        runUi(() -> {
            completing = true;
            stopPulse();
            cancelErrorHide();
            showFadeIn();
            if (spinner != null) spinner.setVisibility(GONE);
            if (thinkingText != null) {
                thinkingText.setTextColor(ERROR);
                thinkingText.setText("⟳  Une erreur est survenue");
            }
            errorHide = () -> animate().alpha(0f).setDuration(200)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            setVisibility(GONE);
                            setAlpha(0f);
                            reset();
                        }
                    }).start();
            main.postDelayed(errorHide, 1000);
        });
    }

    public String getDisplayedText() {
        return thinkingText != null && thinkingText.getText() != null
                ? thinkingText.getText().toString() : "";
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE && getAlpha() > 0.05f;
    }

    public static String toolLabel(String toolId) {
        if (toolId == null || toolId.isEmpty()) return "?";
        switch (toolId.trim().toLowerCase(Locale.ROOT)) {
            case "search":
            case "tavily":
            case "web_search":
                return "recherche web";
            case "weather":
                return "météo";
            case "wikipedia":
                return "Wikipedia";
            case "wikidata":
                return "Wikidata";
            case "notepad":
                return "bloc-notes";
            case "alarm":
                return "alarme";
            case "agenda":
            case "calendar":
                return "agenda";
            case "timer":
                return "minuteur";
            case "calculator":
                return "calcul";
            case "memory":
                return "mémoire";
            case "diag":
                return "diagnostic";
            case "brief":
                return "brief";
            case "news":
                return "actus";
            case "orion_manager":
                return "Orion";
            case "orion_code":
                return "Orion code";
            case "orion_project":
                return "projet Orion";
            case "orion_files":
                return "fichiers Orion";
            case "git_commit":
                return "GitHub";
            case "spotify":
                return "Spotify";
            case "device":
                return "téléphone";
            case "notifications":
                return "notifs";
            default:
                return toolId.trim();
        }
    }

    private void renderToolsLine() {
        if (thinkingText == null) return;
        thinkingText.setTextColor(CYAN);
        thinkingText.setAlpha(1f);
        thinkingText.setText(buildToolsLine(false));
        if (spinner != null) spinner.setVisibility(VISIBLE);
    }

    private String buildToolsLine(boolean forceOkRunning) {
        StringBuilder sb = new StringBuilder("⟳  ");
        boolean first = true;
        for (Map.Entry<String, SlotState> e : tools.entrySet()) {
            if (!first) sb.append(" · ");
            first = false;
            String label = toolLabel(e.getKey());
            SlotState st = e.getValue();
            if (forceOkRunning && st == SlotState.RUNNING) st = SlotState.OK;
            switch (st) {
                case RUNNING:
                    sb.append(label).append(" 🔄");
                    break;
                case OK:
                    sb.append(label).append(" ✅");
                    break;
                case FAIL:
                    sb.append(label).append(" ❌");
                    break;
            }
        }
        return sb.toString();
    }

    private void renderLlmLine() {
        if (thinkingText == null) return;
        thinkingText.setTextColor(CYAN);
        thinkingText.setAlpha(1f);
        if (!tools.isEmpty()) {
            thinkingText.setText(buildToolsLine(true) + " · Pégase réfléchit  · · ·");
        } else {
            thinkingText.setText(pulseText(0));
        }
        if (spinner != null) spinner.setVisibility(VISIBLE);
    }

    private String pulseText(int tick) {
        String[] frames = {
                "⟳  Pégase réfléchit  ● · ·",
                "⟳  Pégase réfléchit  · ● ·",
                "⟳  Pégase réfléchit  · · ●"
        };
        return frames[Math.floorMod(tick, 3)];
    }

    private void startPulse() {
        stopPulse();
        pulseTick = 0;
        pulseLoop = new Runnable() {
            @Override
            public void run() {
                if (!llmPhase || completing || getVisibility() != VISIBLE) return;
                if (thinkingText == null) return;
                if (tools.isEmpty()) {
                    thinkingText.setText(pulseText(pulseTick));
                } else {
                    String base = buildToolsLine(true) + " · Pégase réfléchit  ";
                    String[] dots = {"● · ·", "· ● ·", "· · ●"};
                    thinkingText.setText(base + dots[Math.floorMod(pulseTick, 3)]);
                }
                float a = 0.45f + 0.55f * (float) ((Math.sin(pulseTick * 0.9) + 1) / 2.0);
                thinkingText.setAlpha(a);
                pulseTick++;
                main.postDelayed(this, 400);
            }
        };
        main.post(pulseLoop);
    }

    private void stopPulse() {
        if (pulseLoop != null) {
            main.removeCallbacks(pulseLoop);
            pulseLoop = null;
        }
        if (thinkingText != null) thinkingText.setAlpha(1f);
    }

    private void popCheck() {
        if (thinkingText == null) return;
        thinkingText.setScaleX(0.8f);
        thinkingText.setScaleY(0.8f);
        thinkingText.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
    }

    private void showFadeIn() {
        animate().cancel();
        setVisibility(VISIBLE);
        if (getAlpha() < 0.95f) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(150).setListener(null).start();
        } else {
            setAlpha(1f);
        }
    }

    private void cancelPending() {
        stopPulse();
        cancelErrorHide();
        animate().cancel();
    }

    private void cancelErrorHide() {
        if (errorHide != null) {
            main.removeCallbacks(errorHide);
            errorHide = null;
        }
    }

    private void runUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
        }
    }

    private static String normalizeId(String toolName) {
        if (toolName == null) return "";
        return toolName.trim().toLowerCase(Locale.ROOT);
    }
}
