package com.pegasuscorp.orbe.bureau;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.voice.AssistantVolumeGuard;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;
import com.pegasuscorp.orbe.voice.VoiceManager;
import com.pegasuscorp.orbe.voice.WakeWordMatcher;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bureau canvas tablette — PARKÉ.
 * <p>
 * Conservé pour réactivation future. Le bureau téléphone actif est
 * {@link BureauActivity} (Markdown). Ne pas lancer depuis MainActivity pour l'instant.
 */
public class BureauCanvasActivity extends AppCompatActivity
        implements BureauCanvasView.Listener, BureauHost {

    private static final int REQ_MIC = 901;

    private static final int[] PEN_COLORS = {
            Color.parseColor("#D8F8F4"),   // cyan clair
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#8AB4FF"),
            Color.parseColor("#FF9F7A"),
    };

    private BureauCanvasView canvas;
    private TextView statusPill;
    private TextView micButton;
    private TextView penBtn, eraserBtn, undoBtn, autoBtn, keyboardBtn, moveBtn;
    private LinearLayout inputBar;
    private EditText inputField;

    private VoiceManager voiceManager;
    private BureauMic bureauMic;
    private boolean listening;
    private boolean brainBusy;
    private boolean autoSolve = true;
    private int penIndex;

    private String sceneHint = "";
    private final Set<String> processedExprKeys = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Runnable hideStatus;
    private Runnable autoSolveRunnable;
    private GradientDrawable pillNormal, pillProblem, pillThinking;
    private float density;

    public static void open(Context context) {
        context.startActivity(new Intent(context, BureauCanvasActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Trace.init(this);
        PegaseSession.get(this).init(new SessionContext(Channel.BUREAU, false));
        Trace.bureauAction("open", "canvas");
        density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0E14"));
        setContentView(root);

        canvas = new BureauCanvasView(this);
        canvas.setListener(this);
        root.addView(canvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- barre du haut (verre)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(glass("#5A0E1420", 0f));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        root.addView(topBar, topLp);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topBar.addView(chip("⤴", "Partager", v -> exportSheet()));
        topBar.addView(chip("🧹", "Effacer", v -> confirmClearAll()));
        topBar.addView(chip("✕", "Fermer", v -> finish()));

        // --- pastille de statut
        statusPill = new TextView(this);
        statusPill.setTextColor(Color.parseColor("#F0FFFFFF"));
        statusPill.setTextSize(14);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(18), dp(11), dp(18), dp(11));
        statusPill.setVisibility(View.GONE);
        pillNormal = glass("#DD141B28", 22f);
        pillNormal.setStroke(dp(1), Color.parseColor("#3335D0DD"));
        pillThinking = glass("#DD14212A", 22f);
        pillThinking.setStroke(dp(1), Color.parseColor("#7735D0DD"));
        pillProblem = glass("#DD3A2028", 22f);
        pillProblem.setStroke(dp(1), Color.parseColor("#88FF8A80"));
        statusPill.setBackground(pillNormal);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pillLp.topMargin = dp(64);
        root.addView(statusPill, pillLp);

        // --- dock d'outils (gauche, flottant)
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackground(glass("#CC121824", 26f));
        dock.setPadding(dp(6), dp(8), dp(6), dp(8));
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        dockLp.leftMargin = dp(10);
        root.addView(dock, dockLp);

        penBtn = tool("✏️", v -> selectPen());
        eraserBtn = tool("🩹", v -> selectEraser());
        undoBtn = tool("↩︎", v -> {
            if (!canvas.undo()) status("Rien à annuler.", pillNormal, 1200);
            else scheduleAutoSolve();
            persist();
        });
        autoBtn = tool("⚡", v -> {
            autoSolve = !autoSolve;
            refreshTools();
            status(autoSolve ? "Réponse auto activée" : "Réponse auto coupée", pillNormal, 1600);
        });
        moveBtn = tool("✋", v -> selectMove());
        dock.addView(penBtn);
        dock.addView(moveBtn);
        dock.addView(eraserBtn);
        dock.addView(undoBtn);
        dock.addView(tool("🎨", v -> cyclePenColor()));
        dock.addView(autoBtn);
        keyboardBtn = tool("⌨️", v -> toggleKeyboard());
        dock.addView(keyboardBtn);

        // --- barre de saisie clavier (cachée par défaut)
        inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackground(glass("#EE121824", 26f));
        inputBar.setPadding(dp(14), dp(6), dp(6), dp(6));
        inputBar.setVisibility(View.GONE);

        inputField = new EditText(this);
        inputField.setHint("Écris ton calcul ou ta demande…");
        inputField.setHintTextColor(Color.parseColor("#66FFFFFF"));
        inputField.setTextColor(Color.parseColor("#F0FFFFFF"));
        inputField.setTextSize(15);
        inputField.setBackground(null);
        inputField.setMaxLines(2);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            boolean send = actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
            if (send) { submitTyped(); return true; }
            return false;
        });
        inputBar.addView(inputField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sendBtn = new TextView(this);
        sendBtn.setText("➤");
        sendBtn.setTextSize(20);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextColor(Color.parseColor("#F5D78E"));
        sendBtn.setOnClickListener(v -> submitTyped());
        inputBar.addView(sendBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout.LayoutParams inputLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.gravity = Gravity.BOTTOM;
        inputLp.leftMargin = dp(16);
        inputLp.rightMargin = dp(92);   // on laisse la place au micro
        inputLp.bottomMargin = dp(28);
        root.addView(inputBar, inputLp);

        // --- micro (push-to-talk)
        micButton = new TextView(this);
        micButton.setText("🎤");
        micButton.setTextSize(24);
        micButton.setGravity(Gravity.CENTER);
        int micSize = dp(60);
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(micSize, micSize);
        micLp.gravity = Gravity.BOTTOM | Gravity.END;
        micLp.bottomMargin = dp(28);
        micLp.rightMargin = dp(20);
        micButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (listening) {
                stopListening();
                status("Micro coupé.", pillNormal, 1200);
            } else {
                status("🎤 Parle…", pillNormal, 0);
                startListening();
            }
        });
        root.addView(micButton, micLp);
        refreshMic();
        refreshTools();

        // --- insets
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, dp(10));
            pillLp.topMargin = dp(58) + bars.top;
            statusPill.setLayoutParams(pillLp);
            micLp.bottomMargin = dp(28) + bars.bottom;
            micLp.rightMargin = dp(20) + bars.right;
            micButton.setLayoutParams(micLp);
            dockLp.leftMargin = dp(10) + bars.left;
            dock.setLayoutParams(dockLp);

            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            inputLp.bottomMargin = dp(28) + Math.max(bars.bottom, ime.bottom);
            inputLp.leftMargin = dp(16) + bars.left;
            inputBar.setLayoutParams(inputLp);
            return WindowInsetsCompat.CONSUMED;
        });

        // --- session
        BureauStore.Session session = BureauStore.load(this);
        canvas.restore(session.snapshot);
        processedExprKeys.addAll(session.processedExprKeys);

        voiceManager = ChatVoiceBridge.getSharedVoice(this);
        if (voiceManager != null) {
            voiceManager.cancelScheduledListening();
            voiceManager.stopListening();
        }

        bureauMic = new BureauMic(this, new BureauMic.Callback() {
            @Override public void onTranscript(String t) { handleBureauVoice(t); }
            @Override public void onListeningReady() { listening = true; refreshMic(); }
            @Override public void onListenFailed(int code, String message) {
                listening = false;
                refreshMic();
                if (message != null && !message.isEmpty()) status(message, pillProblem, 3000);
                else hideStatusNow();
            }
        });

        ChatVoiceBridge.registerBureau(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                persist();
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        status("🪽 Écris un calcul, je pose la réponse à côté.", pillNormal, 3200);
    }

    private void selectPen() {
        canvas.setEraserMode(false);
        canvas.setMoveMode(false);
        refreshTools();
    }

    private void selectEraser() {
        canvas.setEraserMode(true);
        canvas.setMoveMode(false);
        refreshTools();
        status("Gomme : touche un trait pour l'effacer.", pillNormal, 1800);
    }

    private void selectMove() {
        boolean on = !canvas.isMoveMode();
        canvas.setMoveMode(on);
        refreshTools();
        status(on ? "Déplace ce que Pégase a écrit." : "Retour au crayon.", pillNormal, 1800);
    }

    private void cyclePenColor() {
        penIndex = (penIndex + 1) % PEN_COLORS.length;
        canvas.setPen(PEN_COLORS[penIndex], 5f);
        canvas.setEraserMode(false);
        refreshTools();
    }

    private void refreshTools() {
        boolean eraser = canvas.isEraserMode();
        boolean move = canvas.isMoveMode();
        boolean pen = !eraser && !move;
        penBtn.setBackground(pen ? toolActiveBg(PEN_COLORS[penIndex]) : null);
        penBtn.setAlpha(pen ? 1f : 0.5f);
        eraserBtn.setBackground(eraser ? toolActiveBg(Color.parseColor("#FF8A80")) : null);
        eraserBtn.setAlpha(eraser ? 1f : 0.5f);
        moveBtn.setBackground(move ? toolActiveBg(Color.parseColor("#8AB4FF")) : null);
        moveBtn.setAlpha(move ? 1f : 0.5f);
        undoBtn.setAlpha(canvas.hasUserInk() ? 1f : 0.35f);
        autoBtn.setAlpha(autoSolve ? 1f : 0.35f);
        autoBtn.setBackground(autoSolve ? toolActiveBg(Color.parseColor("#F5D78E")) : null);
    }

    private void refreshMic() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor(listening ? "#DD35D0DD" : "#55222B3A"));
        bg.setStroke(dp(listening ? 2 : 1),
                Color.parseColor(listening ? "#EEFFFFFF" : "#5535D0DD"));
        micButton.setBackground(bg);
        micButton.setAlpha(listening ? 1f : 0.85f);
        micButton.animate().scaleX(listening ? 1.08f : 1f)
                .scaleY(listening ? 1.08f : 1f).setDuration(160).start();
    }

    private TextView tool(String glyph, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(19);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            click.onClick(v);
        });
        return t;
    }

    private GradientDrawable toolActiveBg(int accent) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.parseColor("#22FFFFFF"));
        d.setStroke(dp(2), accent);
        return d;
    }

    private TextView chip(String glyph, String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(glyph + "  " + label);
        t.setTextColor(Color.parseColor("#E8EEF7"));
        t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.NORMAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setMinHeight(dp(40));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1C2430"));
        bg.setCornerRadius(10f * density);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(click);
        return t;
    }

    private GradientDrawable glass(String color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        if (radiusDp > 0) d.setCornerRadius(radiusDp * density);
        return d;
    }

    private int dp(float v) { return (int) (v * density); }

    // ---------------------------------------------------------------- cycle

    @Override
    protected void onStart() {
        super.onStart();
        VoiceWakeClient.get().stopListening(this);
        AssistantVolumeGuard.activate(this);
        if (voiceManager != null) {
            voiceManager.cancelScheduledListening();
            voiceManager.stopListening();
        }
        // plus d'écoute permanente : le micro est en push-to-talk
    }

    @Override
    protected void onStop() {
        persist();
        stopListening();
        AssistantVolumeGuard.deactivate(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (bureauMic != null) bureauMic.release();
        ChatVoiceBridge.unregisterBureau(this);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void persist() {
        BureauStore.Session session = new BureauStore.Session();
        session.snapshot = canvas.snapshot();
        session.processedExprKeys.addAll(processedExprKeys);
        BureauStore.saveAsync(this, session);   // sérialisation hors thread UI
    }

    // ---------------------------------------------------------------- écriture → réponse auto

    @Override
    public void onStrokeFinished() {
        refreshTools();
        scheduleAutoSolve();
    }

    @Override
    public void onItemMoved() {
        persist();   // la nouvelle position est sauvegardée
    }

    /**
     * Cœur du bureau : quelques instants après que tu as arrêté d'écrire,
     * on relit la dernière ligne. Si c'est une expression, la réponse
     * se pose toute seule à côté du « = ». Aucun mot à prononcer.
     */
    private void scheduleAutoSolve() {
        if (!autoSolve) return;
        if (autoSolveRunnable != null) main.removeCallbacks(autoSolveRunnable);
        autoSolveRunnable = this::runAutoSolve;
        main.postDelayed(autoSolveRunnable, 1100);
    }

    private void runAutoSolve() {
        if (brainBusy || !canvas.hasUserInk()) return;
        BureauCanvasView.Snapshot snap = canvas.snapshot();
        worker.execute(() -> {
            BureauSheetReader.SheetContext sheet = BureauSheetReader.read(snap);
            String pending = sheet.lastPendingText();
            if (pending.isEmpty()) return;
            BureauCalcHelper.Result calc = BureauCalcHelper.trySolve(pending, processedExprKeys);
            if (calc == null) return;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                applyCalc(calc, false);
            });
        });
    }

    // ---------------------------------------------------------------- voix

    private void startListening() {
        if (brainBusy || bureauMic == null) return;
        if (voiceManager != null && voiceManager.isSpeaking()) {
            main.postDelayed(this::startListening, 400);
            return;
        }
        bureauMic.startListening();
    }

    private void stopListening() {
        listening = false;
        if (bureauMic != null) bureauMic.stopListening();
        refreshMic();
    }

    public void handleBureauVoice(String transcript) {
        stopListening();
        if (transcript == null || transcript.trim().isEmpty()) return;
        if (brainBusy) {
            status("Patiente, j'analyse encore.", pillProblem, 2000);
            return;
        }

        String normalized = SpeechInputNormalizer.normalize(this, transcript.trim());
        String raw = WakeWordMatcher.containsWakeWord(normalized)
                ? WakeWordMatcher.stripWakePrefix(normalized) : normalized;
        if (raw.isEmpty()) { hideStatusNow(); return; }

        String fold = raw.toLowerCase(Locale.ROOT);

        if (handleCorrection(raw, fold)) return;

        if (fold.contains("ferme le bureau") || fold.equals("ferme")
                || fold.contains("quitte le bureau")) {
            speak("Je ferme le bureau.", this::finish);
            return;
        }
        if (fold.contains("télécharge") || fold.contains("telecharge")
                || fold.contains("partage") || fold.contains("exporte")) {
            exportSheet();
            return;
        }
        if (isEraseAll(fold)) { clearAll(); speak("Feuille effacée.", null); return; }
        if (isEraseUser(fold)) {
            canvas.clearUserLayer();
            canvas.unmarkAllStrokesProcessed();
            persist();
            refreshTools();
            speak("Ton calque est effacé.", null);
            return;
        }
        if (isErasePegase(fold)) {
            canvas.clearPegaseLayer();
            persist();
            speak("J'ai enlevé mes ajouts.", null);
            return;
        }

        // "enlève la flèche" → cible précise, testé AVANT le "efface" générique
        String target = BureauBrain.extractRemoveTarget(raw);
        if (target != null) {
            if (canvas.removePegaseMatching(target)) {
                persist();
                speak("C'est enlevé.", null);
            } else {
                status("Je ne trouve pas « " + truncate(target, 22) + " ».", pillProblem, 2600);
            }
            return;
        }

        if (fold.contains("annule") || fold.equals("supprime")) {
            if (canvas.removeLastPegaseItem()) { persist(); speak("Dernier ajout enlevé.", null); }
            else status("Rien à enlever de mon côté.", pillProblem, 2000);
            return;
        }

        if (wantsZone(fold)) { analyzeZone(); return; }

        BureauCalcHelper.Result calc = BureauCalcHelper.trySolve(raw, processedExprKeys);
        if (calc != null) { applyCalc(calc, true); return; }

        runBrainAnalysis(raw);
    }

    private boolean handleCorrection(String raw, String fold) {
        BureauCorrectionHelper.Intent intent = BureauCorrectionHelper.parse(this, raw);
        if (intent.type == BureauCorrectionHelper.Type.NONE) return false;

        if (intent.type == BureauCorrectionHelper.Type.REMOVE_ANSWER) {
            boolean removed = canvas.removeLastCalcAnswer();
            if (!removed && intent.removeTarget != null) {
                removed = canvas.removePegaseMatching(intent.removeTarget);
            }
            canvas.unmarkLastProcessedRow();
            processedExprKeys.clear();
            persist();
            if (removed) {
                speak("J'ai enlevé la réponse.", null);
            } else {
                status("Je ne vois pas de réponse à enlever.", pillProblem, 2600);
            }
            return true;
        }

        canvas.removeLastCalcAnswer();
        canvas.unmarkLastProcessedRow();
        processedExprKeys.clear();

        String expr = intent.expression;
        if (expr.isEmpty()) {
            BureauCalcHelper.Result fromVoice =
                    BureauCalcHelper.trySolve(raw, processedExprKeys, true);
            if (fromVoice != null) {
                applyCalc(fromVoice, true);
                return true;
            }
        }
        if (expr.isEmpty()) {
            BureauSheetReader.SheetContext sheet =
                    BureauSheetReader.read(canvas.snapshot());
            expr = sheet.lastPendingText();
        }

        if (!expr.isEmpty()) {
            BureauCalcHelper.Result calc =
                    BureauCalcHelper.trySolve(expr, processedExprKeys, true);
            if (calc != null) {
                applyCalc(calc, true);
                return true;
            }
        }

        if (intent.type == BureauCorrectionHelper.Type.RECALC
                || intent.type == BureauCorrectionHelper.Type.CORRECTED_EXPRESSION) {
            scheduleAutoSolve();
            status("Je n'ai pas pu recalculer — réécris ou dicte les bons chiffres.",
                    pillProblem, 3200);
            speak("Dis-moi les bons chiffres, ou réécris la ligne.", null);
            return true;
        }
        return false;
    }

    private static boolean isEraseAll(String f) {
        return f.contains("efface tout") || f.contains("tout effacer") || f.equals("tout efface");
    }

    private static boolean isEraseUser(String f) {
        return !isEraseAll(f) && (f.contains("efface mon") || f.contains("efface ma")
                || f.contains("efface moi"));
    }

    private static boolean isErasePegase(String f) {
        if (isEraseAll(f) || isEraseUser(f)) return false;
        return f.equals("efface") || f.equals("effacer")
                || f.contains("efface pégase") || f.contains("efface pegase")
                || f.contains("efface tes ajouts");
    }

    // ---------------------------------------------------------------- actions

    private void applyCalc(BureauCalcHelper.Result calc, boolean spoken) {
        if (calc.exprKey != null) processedExprKeys.add(calc.exprKey);
        canvas.addCalcResult(calc);   // ← Pégase écrit sur SON calque, près du "="
        persist();
        status("🪽 " + String.join(" · ", calc.detailLines.isEmpty()
                ? calc.sketchLines : calc.detailLines), pillNormal, 3200);
        sceneHint = calc.speak;
        if (spoken) speak(calc.speak, null);
        else if (voiceManager != null) voiceManager.speak(calc.speak, null);
    }

    // ---------------------------------------------------------------- clavier

    private void toggleKeyboard() {
        boolean show = inputBar.getVisibility() != View.VISIBLE;
        inputBar.setVisibility(show ? View.VISIBLE : View.GONE);
        keyboardBtn.setAlpha(show ? 1f : 0.5f);
        keyboardBtn.setBackground(show ? toolActiveBg(Color.parseColor("#8AB4FF")) : null);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (show) {
            stopListening();                 // clavier et micro ne se marchent pas dessus
            inputField.requestFocus();
            if (imm != null) imm.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(inputField.getWindowToken(), 0);
        }
    }

    /**
     * Le texte tapé emprunte EXACTEMENT le même chemin que la voix.
     * Bénéfice direct : ça saute ML Kit, donc si ça marche au clavier
     * mais pas à la main, le problème est la reconnaissance d'encre, pas le reste.
     */
    private void submitTyped() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;
        inputField.setText("");
        handleTyped(text);
    }

    private void handleTyped(String text) {
        if (brainBusy) {
            status("Patiente, j'analyse encore.", pillProblem, 2000);
            return;
        }
        text = SpeechInputNormalizer.normalize(this, text.trim());
        String fold = text.toLowerCase(Locale.ROOT);

        if (wantsZone(fold)) { analyzeZone(); return; }

        BureauCorrectionHelper.Intent intent = BureauCorrectionHelper.parse(this, text);
        if (intent.type != BureauCorrectionHelper.Type.NONE) {
            handleCorrection(text, fold);
            return;
        }

        BureauCalcHelper.Result calc = BureauCalcHelper.trySolve(text, processedExprKeys);
        if (calc != null) {
            // s'il y a un cadre, on répond dans le cadre ; sinon près de la dernière ligne
            BureauGeometryReader.Scene scene = canvas.scene();
            BureauGeometryReader.Shape zone = scene.lastZone();
            if (calc.exprKey != null) processedExprKeys.add(calc.exprKey);
            if (zone != null) {
                canvas.addAnswerForZone(BureauCanvasView.inlineAnswer(calc), zone.box);
                canvas.markZoneProcessed(scene, zone.box);
            } else {
                canvas.addCalcResult(calc);
            }
            persist();
            sceneHint = calc.speak;
            status("🪽 " + calc.speak, pillNormal, 3200);
            if (voiceManager != null) voiceManager.speak(calc.speak, null);
            return;
        }

        handleBureauVoice(text);   // même pipeline que la voix
    }

    private static boolean wantsZone(String f) {
        return f.contains("cette zone") || f.contains("ce cadre") || f.contains("cette case")
                || f.contains("dans le cadre") || f.contains("dans le carré")
                || f.contains("dans le carre") || f.contains("ici")
                || f.contains("ce que j'ai encadré") || f.contains("ce que j'ai encadre");
    }

    /**
     * Tu encadres, tu dis « analyse cette zone » : plus de devinette,
     * on ne lit QUE l'encre du cadre et on répond juste à côté du cadre.
     */
    private void analyzeZone() {
        BureauCanvasView.Snapshot snap = canvas.snapshot();
        BureauGeometryReader.Scene scene = BureauGeometryReader.read(
                snap.userStrokes, snap.canvasW, snap.canvasH);
        BureauGeometryReader.Shape zone = scene.lastZone();
        if (zone == null) {
            status("Encadre d'abord la zone à analyser.", pillProblem, 3000);
            speak("Trace un cadre autour de ce que je dois regarder.", null);
            return;
        }

        brainBusy = true;
        status("🪽 Je lis le cadre…", pillThinking, 0);
        worker.execute(() -> {
            String text = BureauSheetReader.readZone(snap, scene, zone.box);
            BureauCalcHelper.Result calc =
                    text.isEmpty() ? null : BureauCalcHelper.trySolve(text, processedExprKeys);
            main.post(() -> {
                brainBusy = false;
                if (isFinishing() || isDestroyed()) return;
                if (calc != null) {
                    if (calc.exprKey != null) processedExprKeys.add(calc.exprKey);
                    canvas.addAnswerForZone(
                            BureauCanvasView.inlineAnswer(calc), zone.box);
                    canvas.markZoneProcessed(scene, zone.box);
                    persist();
                    sceneHint = calc.speak;
                    speak(calc.speak, null);
                } else if (!text.isEmpty()) {
                    canvas.addAnswerForZone(text, zone.box);
                    persist();
                    speak("Dans le cadre je lis : " + text, null);
                } else {
                    status("Rien de lisible dans le cadre.", pillProblem, 3000);
                    speak("Je ne lis rien dans ce cadre.", null);
                }
            });
        });
    }

    private void runBrainAnalysis(String userPhrase) {
        brainBusy = true;
        status("🪽 Je lis ta feuille…", pillThinking, 0);
        BureauCanvasView.Snapshot snapshot = canvas.snapshot();

        BureauBrain.analyze(this, snapshot, userPhrase, sceneHint, processedExprKeys,
                new BureauBrain.Callback() {
                    @Override public void onResult(BureauBrain.Result result) {
                        brainBusy = false;
                        if (isFinishing() || isDestroyed()) return;
                        applyBrainResult(result);
                    }

                    @Override public void onError(String message) {
                        brainBusy = false;
                        if (isFinishing() || isDestroyed()) return;
                        status("Je n'arrive pas à analyser la feuille.", pillProblem, 3000);
                    }
                });
    }

    private void applyBrainResult(BureauBrain.Result result) {
        if (result == null) {
            status("Je n'ai rien à dire sur cette feuille.", pillProblem, 2600);
            return;
        }
        if (result.calcResult != null) { applyCalc(result.calcResult, true); return; }

        if (!result.lines.isEmpty()) canvas.addPegaseLines(result.lines);   // elle écrit
        if (!result.boxes.isEmpty()) canvas.addRelativeBoxes(result.boxes); // elle encadre
        canvas.markAllProcessed();
        sceneHint = result.speak;
        persist();

        String say = result.speak.isEmpty() ? "Voilà." : result.speak;
        status("🪽 " + truncate(say, 70), pillNormal, 3400);
        speak(say, null);
    }

    private void clearAll() {
        canvas.clearUserLayer();
        canvas.clearPegaseLayer();
        processedExprKeys.clear();
        sceneHint = "";
        persist();
        refreshTools();
    }

    private void confirmClearAll() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Effacer toute la feuille ?")
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Ses ajouts", (d, w) -> {
                    canvas.clearPegaseLayer();
                    persist();
                })
                .setPositiveButton("Tout", (d, w) -> {
                    clearAll();
                    status("🪽 Feuille vierge.", pillNormal, 1800);
                })
                .show();
    }

    private void exportSheet() {
        canvas.post(() -> {
            android.graphics.Bitmap bmp = canvas.captureBitmap();
            boolean ok = BureauExporter.shareSheet(this, bmp);
            bmp.recycle();
            if (!ok) status("Export impossible.", pillProblem, 2600);
        });
    }

    // ---------------------------------------------------------------- statut / voix

    private void speak(String text, Runnable after) {
        status("🪽 " + truncate(text, 70), pillNormal, 3000);
        if (voiceManager != null) voiceManager.speak(text, after);
        else if (after != null) after.run();
    }

    private void status(String text, GradientDrawable bg, long hideAfterMs) {
        if (hideStatus != null) main.removeCallbacks(hideStatus);
        statusPill.setBackground(bg);
        statusPill.setText(text);
        if (statusPill.getVisibility() != View.VISIBLE) {
            statusPill.setAlpha(0f);
            statusPill.setTranslationY(-dp(8));
            statusPill.setVisibility(View.VISIBLE);
        }
        statusPill.animate().alpha(1f).translationY(0f).setDuration(180).start();
        if (hideAfterMs > 0) {
            hideStatus = this::hideStatusNow;
            main.postDelayed(hideStatus, hideAfterMs);
        }
    }

    private void hideStatusNow() {
        statusPill.animate().alpha(0f).translationY(-dp(8)).setDuration(200)
                .withEndAction(() -> statusPill.setVisibility(View.GONE)).start();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                status("Micro requis pour parler au bureau.", pillProblem, 3000);
            }
        }
    }
}
