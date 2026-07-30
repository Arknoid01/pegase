package com.pegasuscorp.orbe.orion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.pegasuscorp.orbe.OrionSettingsActivity;
import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.fs.UriDisplayNames;
import com.pegasuscorp.orbe.iface.PegaseInterfaceHost;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.prompt.ClarificationManager;
import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.PromptCompiler;
import com.pegasuscorp.orbe.orion.prompt.PromptReadiness;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.qa.OrionQaChecker;
import com.pegasuscorp.orbe.orion.qa.OrionQaLoop;
import com.pegasuscorp.orbe.orion.qa.OrionQaReport;
import com.pegasuscorp.orbe.orion.search.FileLocation;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.orion.OrionCodeTool;
import com.pegasuscorp.orbe.ui.PegaseSheets;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zone chat / streaming Orion : bulles, Dicter / Taper / Stop / ⋮, pièces jointes.
 */
public final class OrionStreamView {

    public interface Listener {
        void onNeedLaunch();
        void onGenerationFinished(String code);
        void onSaveToProjectRequested(List<OrionFileSession.OrionFile> files);
        /** Statut prêt (READY) depuis le coordinateur / store. */
        boolean isReady();
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private View topStrip;
    private View mainColumn;
    private TextView attachmentsLine;
    private TextView modeBadge;
    private TextView generatingLabel;
    private ScrollView chatScroll;
    private LinearLayout chatColumn;
    private TextView streamingAssistantView;
    private LinearLayout actionRow;
    private LinearLayout inputBar;
    private Button copyBtn;
    private Button saveBtn;
    private Button shareBtn;
    private Button codeServerBtn;
    private Button stopGenBtn;
    private Button dictateBtn;
    private Button typeBtn;
    private Button attachBtn;
    private EditText typeField;
    private Button sendTypeBtn;
    private Button rewriteBtn;
    private LinearLayout typeRow;
    private boolean rewriting;
    /** Demande d'origine pendant le cycle questions → Mission. */
    private String pendingCompileDemand;
    /**
     * Saisie utilisateur figée avant toute réécriture — seul texte autorisé
     * pour {@link OrionMode#detect}.
     */
    private String frozenRawDemand;
    private final ClarificationManager clarificationManager = new ClarificationManager();
    private String pendingLearnCandidateId;
    /** Mission active pour QA auto (si ↗ avec Mission :). */
    private String activeQaMission;
    private ResolvedTask pendingResolvedTask;
    private boolean criticalMissionConfirmed;
    private int qaAttempt;
    private boolean qaEnabled = true;
    private boolean chunkEnabled = true;
    private OrionChunkSession activeChunkSession;
    private boolean chunkingInProgress;
    private boolean planBuildingInProgress;

    private String lastFullCode = "";
    private boolean generating;
    /** Prompt Bureau en attente d'auto-envoi dès READY. */
    private String pendingBureauPrompt;
    private boolean pendingBureauLaunchAsked;
    private final LinkedHashMap<String, String> pendingAttachments = new LinkedHashMap<>();
    private final List<String> pendingLoadKeywords = new ArrayList<>();

    public OrionStreamView(Activity activity) {
        this.activity = activity;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Construit les vues. Le coordinateur compose dans l'ordre d'origine :
     * {@link #getTopStrip()} (pièces jointes + label) puis projet, puis
     * {@link #getMainColumn()} (chat + actions + saisie, poids 1).
     */
    public View build() {
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.VERTICAL);

        attachmentsLine = OrionUi.hint(activity, "");
        attachmentsLine.setTextSize(11);
        attachmentsLine.setTextColor(Color.parseColor("#88FFFFFF"));
        attachmentsLine.setVisibility(View.GONE);
        top.addView(attachmentsLine);

        modeBadge = new TextView(activity);
        modeBadge.setTextSize(11);
        modeBadge.setTypeface(null, Typeface.BOLD);
        modeBadge.setPadding(0, OrionUi.dp(activity, 2), 0, OrionUi.dp(activity, 2));
        modeBadge.setVisibility(View.GONE);
        top.addView(modeBadge);

        generatingLabel = new TextView(activity);
        generatingLabel.setTextColor(Color.parseColor(OrionUi.CYAN));
        generatingLabel.setTextSize(12);
        generatingLabel.setVisibility(View.GONE);
        top.addView(generatingLabel);
        topStrip = top;

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        chatScroll = new ScrollView(activity);
        chatScroll.setFillViewport(true);
        chatColumn = new LinearLayout(activity);
        chatColumn.setOrientation(LinearLayout.VERTICAL);
        chatColumn.setPadding(0, OrionUi.dp(activity, 4), 0, OrionUi.dp(activity, 8));
        chatScroll.addView(chatColumn, OrionUi.matchWrap());
        col.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        actionRow = new LinearLayout(activity);
        actionRow.setVisibility(View.GONE);
        copyBtn = OrionUi.outlineBtn(activity, "Copier");
        copyBtn.setOnClickListener(v -> copyCode());
        copyBtn.setEnabled(false);
        actionRow.addView(copyBtn);
        saveBtn = OrionUi.outlineBtn(activity, "Fichiers");
        saveBtn.setOnClickListener(v -> saveCode(false));
        saveBtn.setEnabled(false);
        actionRow.addView(saveBtn);
        shareBtn = OrionUi.outlineBtn(activity, "Partager");
        shareBtn.setOnClickListener(v -> saveCode(true));
        shareBtn.setEnabled(false);
        actionRow.addView(shareBtn);
        codeServerBtn = OrionUi.outlineBtn(activity, "VS");
        codeServerBtn.setOnClickListener(v -> openCodeServer());
        codeServerBtn.setEnabled(false);
        actionRow.addView(codeServerBtn);
        attachBtn = OrionUi.outlineBtn(activity, "Joindre");
        attachBtn.setOnClickListener(v -> showAttachMenu());
        actionRow.addView(attachBtn);
        col.addView(actionRow);

        inputBar = new LinearLayout(activity);
        inputBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, OrionUi.dp(activity, 4), 0, 0);
        dictateBtn = OrionUi.cyanBtn(activity, "Dicter");
        dictateBtn.setTextSize(12);
        dictateBtn.setOnClickListener(v -> startDictate());
        inputRow.addView(dictateBtn, OrionUi.weight());
        typeBtn = OrionUi.outlineBtn(activity, "Taper");
        typeBtn.setTextSize(12);
        typeBtn.setOnClickListener(v -> toggleTypeField());
        inputRow.addView(typeBtn, OrionUi.weight());
        stopGenBtn = OrionUi.outlineBtn(activity, "Stop");
        stopGenBtn.setTextSize(12);
        stopGenBtn.setOnClickListener(v -> stopGeneration());
        stopGenBtn.setVisibility(View.GONE);
        inputRow.addView(stopGenBtn);
        Button actionsMenu = OrionUi.outlineBtn(activity, "⋮");
        actionsMenu.setTextSize(14);
        actionsMenu.setMinWidth(OrionUi.dp(activity, 44));
        actionsMenu.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            showOrionActionsMenu();
        });
        inputRow.addView(actionsMenu);
        inputBar.addView(inputRow, OrionUi.matchWrap());

        typeRow = new LinearLayout(activity);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setVisibility(View.GONE);
        typeRow.setPadding(0, OrionUi.dp(activity, 4), 0, 0);
        typeField = new EditText(activity);
        typeField.setHint("Demande à Orion…");
        typeField.setHintTextColor(Color.parseColor("#55FFFFFF"));
        typeField.setTextColor(Color.WHITE);
        typeField.setBackgroundColor(Color.parseColor("#1A1A1A"));
        typeField.setPadding(OrionUi.dp(activity, 10), OrionUi.dp(activity, 8),
                OrionUi.dp(activity, 10), OrionUi.dp(activity, 8));
        typeField.setMinHeight(OrionUi.dp(activity, 40));
        typeField.setMaxLines(4);
        typeField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        typeRow.addView(typeField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rewriteBtn = OrionUi.outlineBtn(activity, "✨");
        rewriteBtn.setTextSize(14);
        rewriteBtn.setContentDescription("PromptCompiler — Pégase reformule strictement");
        rewriteBtn.setOnClickListener(v -> rewritePromptWithPegase());
        typeRow.addView(rewriteBtn);
        sendTypeBtn = OrionUi.cyanBtn(activity, "↗");
        sendTypeBtn.setTextSize(14);
        sendTypeBtn.setOnClickListener(v -> {
            String t = typeField.getText() != null ? typeField.getText().toString().trim() : "";
            if (!t.isEmpty()) {
                typeField.setText("");
                submitPrompt(t);
            }
        });
        typeRow.addView(sendTypeBtn);
        inputBar.addView(typeRow, OrionUi.matchWrap());
        col.addView(inputBar, OrionUi.matchWrap());

        mainColumn = col;
        rebuildChatFromHistory();
        return col;
    }

    /** Pièces jointes + label génération (au-dessus du panneau projet). */
    public View getTopStrip() {
        return topStrip;
    }

    /** Chat + barre d'actions + saisie (poids 1 dans le coordinateur). */
    public View getMainColumn() {
        return mainColumn;
    }

    public View getRoot() {
        return mainColumn;
    }

    public boolean isGenerating() {
        return generating;
    }

    public void onKeyboardVisible() {
        scrollChatToEnd();
        if (typeRow != null && typeRow.getVisibility() == View.VISIBLE && typeField != null) {
            if (chatScroll != null) {
                chatScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            typeField.requestFocus();
        }
    }

    /** Cache / défocalise le champ Orion pour ne pas bloquer Discussion. */
    public void releaseInputFocus() {
        if (typeField != null && typeField.hasFocus()) {
            typeField.clearFocus();
        }
        if (typeRow != null && typeRow.getVisibility() == View.VISIBLE) {
            typeRow.setVisibility(View.GONE);
        }
        if (chatScroll != null) {
            chatScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (activity != null && typeField != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(typeField.getWindowToken(), 0);
            }
        }
    }

    public void onDictateResult(String transcript) {
        if (transcript != null && !transcript.trim().isEmpty()) {
            submitPrompt(transcript.trim());
        }
    }

    public void onPickMdResult(Uri uri) {
        if (uri == null) return;
        try {
            String name = UriDisplayNames.fromUri(activity, uri, "import.md");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                name = name + ".md";
            }
            String content = readUriUtf8(uri);
            if (TextUtils.isEmpty(content)) {
                Toast.makeText(activity, "Fichier vide", Toast.LENGTH_SHORT).show();
                return;
            }
            final String label = name;
            final String body = content;
            final String keyword = name.replace("-context.md", "")
                    .replace(".md", "")
                    .replace('_', '-')
                    .toLowerCase(Locale.ROOT);
            new AlertDialog.Builder(activity)
                    .setTitle("Joindre " + label)
                    .setMessage("Attacher au prochain envoi Orion ?\n"
                            + "Tu peux aussi l'enregistrer dans tes contextes Pégase.")
                    .setPositiveButton("Joindre", (d, w) -> {
                        pendingAttachments.put(label, body);
                        refreshAttachmentsLine();
                    })
                    .setNeutralButton("Joindre + sauver", (d, w) -> {
                        pendingAttachments.put(label, body);
                        try {
                            ContextualFileStore.getInstance(activity).save(keyword, body);
                            pendingLoadKeywords.add(keyword);
                            Toast.makeText(activity, "Sauvé dans contextes : " + keyword,
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(activity, "Import joint, sauver contextes échoué",
                                    Toast.LENGTH_SHORT).show();
                        }
                        refreshAttachmentsLine();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(activity, "Lecture impossible : "
                    + (e.getMessage() == null ? "erreur" : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    public void prefillPrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) return;
        String trimmed = prompt.trim();
        if (typeRow != null) typeRow.setVisibility(View.VISIBLE);
        if (typeField != null) {
            typeField.setText(trimmed);
            typeField.setSelection(typeField.getText().length());
            typeField.requestFocus();
        }
        pendingBureauPrompt = trimmed;
        pendingBureauLaunchAsked = false;
        OrionBureauBridge.Action action = flushPendingBureauPrompt(false);
        if (action == OrionBureauBridge.Action.SUBMIT) {
            Toast.makeText(activity, "Plan Bureau → Orion génère…", Toast.LENGTH_SHORT).show();
        } else if (action == OrionBureauBridge.Action.LAUNCH_AND_WAIT
                || action == OrionBureauBridge.Action.WAIT) {
            Toast.makeText(activity, "Plan en file — Orion démarre puis génère",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Tente d'envoyer le prompt Bureau en attente.
     * @param fromStatusChange true si appelé depuis un tick d'état pod
     */
    public OrionBureauBridge.Action flushPendingBureauPrompt(boolean fromStatusChange) {
        OrionStatus st = OrionStateStore.get().getStatus();
        OrionBureauBridge.Action action = OrionBureauBridge.decide(
                st, pendingBureauPrompt != null && !pendingBureauPrompt.isEmpty(), generating);
        if (action == OrionBureauBridge.Action.SUBMIT) {
            String p = pendingBureauPrompt;
            pendingBureauPrompt = null;
            pendingBureauLaunchAsked = false;
            if (typeField != null) typeField.setText("");
            submitPrompt(p);
            return action;
        }
        if (action == OrionBureauBridge.Action.LAUNCH_AND_WAIT && !pendingBureauLaunchAsked) {
            pendingBureauLaunchAsked = true;
            requestLaunch();
        }
        return action;
    }

    public void setInputEnabled(boolean on) {
        if (dictateBtn != null) {
            dictateBtn.setEnabled(on || OrionStateStore.get().getStatus() == OrionStatus.OFFLINE);
        }
        if (typeBtn != null) typeBtn.setEnabled(true);
    }

    public void setGenerating(boolean generating) {
        this.generating = generating;
        if (stopGenBtn != null) {
            stopGenBtn.setVisibility(generating ? View.VISIBLE : View.GONE);
        }
    }

    public void applyStatusGate(OrionStatus status) {
        boolean ready = status == OrionStatus.READY;
        setInputEnabled(ready);
        if (stopGenBtn != null) {
            stopGenBtn.setVisibility(generating ? View.VISIBLE : View.GONE);
        }
        if (codeServerBtn != null) {
            codeServerBtn.setEnabled(!TextUtils.isEmpty(OrionStateStore.get().getPodId()));
        }
        flushPendingBureauPrompt(true);
    }

    public void rebuildChatFromHistory() {
        if (chatColumn == null) return;
        chatColumn.removeAllViews();
        streamingAssistantView = null;
        for (OrionChatHistory.Turn t : OrionChatHistory.get().snapshot()) {
            appendChatBubble(t.fromUser, t.text);
        }
        lastFullCode = OrionChatHistory.get().lastAssistantText();
        setOutputActionsEnabled(!TextUtils.isEmpty(lastFullCode));
        scrollChatToEnd();
    }

    private void toggleTypeField() {
        OrionStatus st = OrionStateStore.get().getStatus();
        if (st != OrionStatus.READY && st != OrionStatus.BUSY) {
            requestLaunch();
            return;
        }
        boolean show = typeRow.getVisibility() != View.VISIBLE;
        typeRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (chatScroll != null) {
            chatScroll.setDescendantFocusability(show
                    ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (show) {
            typeField.setFocusable(true);
            typeField.setFocusableInTouchMode(true);
            typeField.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(typeField,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
            main.postDelayed(this::scrollChatToEnd, 120);
        }
    }

    private void startDictate() {
        OrionStatus st = OrionStateStore.get().getStatus();
        if (st != OrionStatus.READY) {
            requestLaunch();
            return;
        }
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle à Orion…");
            activity.startActivityForResult(intent, OrionFragment.REQ_DICTATE);
        } catch (Exception e) {
            Toast.makeText(activity, "Reconnaissance vocale indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestLaunch() {
        if (listener != null) listener.onNeedLaunch();
    }

    /** Pégase : readiness → interprétation / questions / Mission. */
    private void rewritePromptWithPegase() {
        if (typeField == null) return;
        String field = typeField.getText() != null ? typeField.getText().toString().trim() : "";
        if (field.isEmpty()) {
            Toast.makeText(activity, "Écris d'abord ta demande", Toast.LENGTH_SHORT).show();
            return;
        }
        if (rewriting || generating) {
            Toast.makeText(activity, "Attends un instant…", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean secondPass = clarificationManager.looksLikeClarificationField(field)
                || clarificationManager.getPhase() == ClarificationManager.Phase.AWAITING_VALIDATION
                || clarificationManager.getPhase() == ClarificationManager.Phase.AWAITING_ANSWERS;

        String demand;
        String priorQa = "";
        if (secondPass) {
            demand = ClarificationManager.extractDemand(field, pendingCompileDemand);
            if (TextUtils.isEmpty(demand)) demand = clarificationManager.getOriginalDemand();
            priorQa = clarificationManager.extractUserReply(field);
        } else {
            demand = field;
            if (field.toLowerCase(Locale.ROOT).startsWith("mission :")) {
                // déjà une mission — resserrer seulement
                demand = field;
            }
            clarificationManager.begin(demand);
            pendingCompileDemand = demand;
            // Figer AVANT le rewriter — detect() ne verra jamais la sortie enrichie
            if (!OrionQaLoop.looksLikeMission(demand)
                    && !PromptCompiler.looksLikeCompiledMission(demand)) {
                frozenRawDemand = demand;
            }
        }

        if (TextUtils.isEmpty(demand)) {
            Toast.makeText(activity, "Demande vide", Toast.LENGTH_SHORT).show();
            return;
        }

        final String fDemand = demand;
        final String fPrior = priorQa;
        rewriting = true;
        if (rewriteBtn != null) rewriteBtn.setEnabled(false);
        if (sendTypeBtn != null) sendTypeBtn.setEnabled(false);
        Toast.makeText(activity,
                secondPass ? "Compile la mission…" : "Analyse…",
                Toast.LENGTH_SHORT).show();
        PegaseSession.get(activity).rewriteOrionPrompt(fDemand, fPrior,
                new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                applyCompileResult(fDemand, fPrior, reply != null ? reply.content : null);
            }

            @Override
            public void onReply(String text) {
                applyCompileResult(fDemand, fPrior, text);
            }

            @Override
            public void onError(String error) {
                main.post(() -> {
                    rewriting = false;
                    if (rewriteBtn != null) rewriteBtn.setEnabled(true);
                    if (sendTypeBtn != null) sendTypeBtn.setEnabled(true);
                    Toast.makeText(activity,
                            error != null ? error : "Reformulation impossible",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyCompileResult(String demand, String priorQa, String text) {
        main.post(() -> {
            rewriting = false;
            if (rewriteBtn != null) rewriteBtn.setEnabled(true);
            if (sendTypeBtn != null) sendTypeBtn.setEnabled(true);
            OrionPromptRewriter.CompileResult result =
                    OrionPromptRewriter.parseCompileResult(activity, text, demand);
            if (result.text.isEmpty()) {
                Toast.makeText(activity, "Pégase n'a rien renvoyé", Toast.LENGTH_SHORT).show();
                return;
            }

            // 2ᵉ passe forcée mission, ou READY
            boolean forceMission = !TextUtils.isEmpty(priorQa);
            if (!forceMission && CodeLearnStore.isEnabled(activity)
                    && result.kind == OrionPromptRewriter.CompileKind.INTERPRETATION) {
                pendingCompileDemand = demand;
                String ui = OrionPromptRewriter.buildInterpretationUi(demand, result.text);
                clarificationManager.buildUiPrompt(
                        new com.pegasuscorp.orbe.orion.prompt.PromptAmbiguityAnalyzer.Analysis(
                                PromptReadiness.CLARIFICATION_RECOMMENDED,
                                result.text, java.util.Collections.emptyList(), "",
                                result.learnCandidate));
                if (typeField != null) {
                    typeField.setText(ui);
                    int pos = ui.toLowerCase(Locale.ROOT).lastIndexOf("oui");
                    typeField.setSelection(pos >= 0 ? pos : ui.length());
                    typeField.requestFocus();
                }
                maybeQueueLearnCandidate(demand, result.learnCandidate);
                Toast.makeText(activity,
                        "Interprétation — oui / corrige / fais au mieux, puis ✨",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (!forceMission && CodeLearnStore.isEnabled(activity)
                    && result.kind == OrionPromptRewriter.CompileKind.NEED_INFO) {
                pendingCompileDemand = demand;
                String ui = OrionPromptRewriter.buildAnswersTemplate(demand, result.text);
                java.util.List<String> qs = OrionPromptRewriter.questionsFromBlock(result.text);
                clarificationManager.buildUiPrompt(
                        new com.pegasuscorp.orbe.orion.prompt.PromptAmbiguityAnalyzer.Analysis(
                                PromptReadiness.CLARIFICATION_REQUIRED,
                                "", qs, "", result.learnCandidate));
                if (typeField != null) {
                    typeField.setText(ui);
                    int pos = ui.indexOf("1. ");
                    typeField.setSelection(pos >= 0 ? pos + 3 : ui.length());
                    typeField.requestFocus();
                }
                maybeQueueLearnCandidate(demand, result.learnCandidate);
                Toast.makeText(activity,
                        "2 questions max — réponds ou « fais au mieux », puis ✨",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // Mission
            if (!TextUtils.isEmpty(priorQa)) {
                CodeLearnStore.remember(activity, demand, priorQa);
            }
            clarificationManager.clear();
            pendingCompileDemand = null;
            // Garder frozenRawDemand : la mission affichée n'est pas la demande brute
            activeQaMission = result.text;
            qaAttempt = 0;
            pendingResolvedTask = result.task;
            if (pendingResolvedTask != null
                    && !TextUtils.isEmpty(pendingResolvedTask.rawInput)
                    && !PromptCompiler.looksLikeCompiledMission(pendingResolvedTask.rawInput)) {
                frozenRawDemand = pendingResolvedTask.rawInput;
            }
            criticalMissionConfirmed = pendingResolvedTask == null
                    || pendingResolvedTask.risk != TaskRisk.CRITICAL;
            if (typeField != null) {
                typeField.setText(result.text);
                typeField.setSelection(result.text.length());
                typeField.requestFocus();
            }
            maybeQueueLearnCandidate(demand, result.learnCandidate);
            offerLearnCandidateIfAny();
            handleMissionRiskUi(pendingResolvedTask);
        });
    }

    private void showModeBadge(OrionMode mode) {
        if (modeBadge == null) return;
        OrionMode m = mode != null ? mode : OrionMode.PATCH;
        modeBadge.setVisibility(View.VISIBLE);
        modeBadge.setText(m.badgeLabel());
        if (m == OrionMode.FEATURE) {
            modeBadge.setTextColor(Color.parseColor("#69F0AE"));
        } else {
            modeBadge.setTextColor(Color.parseColor("#FFD54F"));
        }
    }

    private void handleMissionRiskUi(ResolvedTask task) {
        showModeBadge(task != null ? task.mode : null);
        if (task == null) {
            Toast.makeText(activity, "Mission prête — aperçu puis ↗ (QA auto)",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (task.complexity == TaskComplexity.LARGE || task.complexity == TaskComplexity.MASSIVE) {
            Trace.orionLargeTask(task.rawInput, task.complexity.name());
        }
        if (task.risk == TaskRisk.CRITICAL) {
            criticalMissionConfirmed = false;
            new AlertDialog.Builder(activity)
                    .setTitle("Fichier critique")
                    .setMessage("⚠️ Ce fichier est critique pour Pégase.\n"
                            + "Je vais être très prudente. Tu confirmes ?")
                    .setPositiveButton("Oui, envoyer à Orion", (d, w) -> {
                        criticalMissionConfirmed = true;
                        Toast.makeText(activity,
                                "Confirmé — ↗ quand tu es prêt (QA strict)",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Annuler", (d, w) -> criticalMissionConfirmed = false)
                    .show();
            return;
        }
        if (task.risk == TaskRisk.HIGH) {
            Toast.makeText(activity,
                    "Modification importante — je peux découper si tu veux, ou ↗ direct",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Mission prête — aperçu puis ↗ (QA auto)",
                Toast.LENGTH_SHORT).show();
    }

    private boolean ensureCriticalConfirmed() {
        if (pendingResolvedTask == null || pendingResolvedTask.risk != TaskRisk.CRITICAL) {
            return true;
        }
        if (criticalMissionConfirmed) return true;
        new AlertDialog.Builder(activity)
                .setTitle("Confirmation requise")
                .setMessage("⚠️ Fichier critique Pégase — confirmer avant d'envoyer à Orion ?")
                .setPositiveButton("Oui", (d, w) -> {
                    criticalMissionConfirmed = true;
                    if (typeField != null) {
                        String t = typeField.getText() != null
                                ? typeField.getText().toString().trim() : "";
                        if (!t.isEmpty()) submitPrompt(t);
                    }
                })
                .setNegativeButton("Non", null)
                .show();
        return false;
    }

    private void maybeQueueLearnCandidate(String demand, String summary) {
        if (TextUtils.isEmpty(summary) || !CodeLearnStore.isEnabled(activity)) return;
        CodeLearnStore.proposeCandidate(activity, demand, summary);
    }

    private void offerLearnCandidateIfAny() {
        CodeLearnStore.Candidate c = CodeLearnStore.peekLatestCandidate(activity);
        if (c == null || TextUtils.isEmpty(c.summary)) return;
        pendingLearnCandidateId = c.id;
        new AlertDialog.Builder(activity)
                .setTitle("Préférence code ?")
                .setMessage("Je remarque : « " + c.summary + " »\n\nJe le retiens ?")
                .setPositiveButton("Oui, retiens", (d, w) -> {
                    CodeLearnStore.acceptCandidate(activity, c.id);
                    pendingLearnCandidateId = null;
                    Toast.makeText(activity, "Préférence enregistrée", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Non", (d, w) -> {
                    CodeLearnStore.refuseCandidate(activity, c.id);
                    pendingLearnCandidateId = null;
                })
                .setNeutralButton("Plus tard", null)
                .show();
    }

    private void submitPrompt(String prompt) {
        submitPrompt(prompt, false);
    }

    /**
     * Résout une mission affichée en figeant le mode sur la demande d'origine.
     * Jamais {@link OrionMode#detect} sur le texte enrichi du rewriter.
     */
    private ResolvedTask resolveMissionPreservingRawMode(String missionPrompt) {
        String raw = frozenRawDemand;
        if (TextUtils.isEmpty(raw) && pendingResolvedTask != null) {
            raw = pendingResolvedTask.rawInput;
        }
        if (!TextUtils.isEmpty(raw) && !PromptCompiler.looksLikeCompiledMission(raw)) {
            return PromptCompiler.resolve(activity, missionPrompt, raw);
        }
        OrionMode mode = pendingResolvedTask != null && pendingResolvedTask.mode != null
                ? pendingResolvedTask.mode
                : OrionMode.PATCH;
        String source = !TextUtils.isEmpty(raw) ? raw : missionPrompt;
        return PromptCompiler.resolve(activity, missionPrompt, source, mode);
    }

    private void submitPrompt(String prompt, boolean skipChunkCheck) {
        OrionStatus st = OrionStateStore.get().getStatus();
        if (st != OrionStatus.READY && st != OrionStatus.BUSY) {
            requestLaunch();
            return;
        }
        if (generating) {
            Toast.makeText(activity, "Génération en cours…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (OrionQaLoop.looksLikeMission(prompt)) {
            // Ne pas re-détecter sur un compilé (chunks : pending déjà posé)
            if (!(skipChunkCheck && pendingResolvedTask != null)) {
                pendingResolvedTask = resolveMissionPreservingRawMode(prompt);
            }
            showModeBadge(pendingResolvedTask != null ? pendingResolvedTask.mode : null);
            if (pendingResolvedTask != null && pendingResolvedTask.risk == TaskRisk.CRITICAL) {
                criticalMissionConfirmed = false;
            }
        } else if (!skipChunkCheck) {
            frozenRawDemand = prompt;
            showModeBadge(OrionMode.detect(prompt));
        }
        if (!ensureCriticalConfirmed()) return;
        if (!skipChunkCheck && chunkEnabled && qaAttempt <= 1 && activeChunkSession == null) {
            ResolvedTask task = pendingResolvedTask;
            if (task == null && OrionQaLoop.looksLikeMission(prompt)) {
                task = resolveMissionPreservingRawMode(prompt);
                pendingResolvedTask = task;
                showModeBadge(task != null ? task.mode : null);
            }
            if (task != null && task.complexity == TaskComplexity.MASSIVE) {
                offerExecutionPlan(task, prompt);
                return;
            }
            if (task != null && task.complexity == TaskComplexity.LARGE) {
                offerChunkPlan(task, prompt);
                return;
            }
        }
        if (activeChunkSession != null) {
            updateChunkProgress(activeChunkSession.current());
        }
        // QA : mémoriser la mission si présente
        if (OrionQaLoop.looksLikeMission(prompt)) {
            activeQaMission = prompt;
            if (qaAttempt <= 0) qaAttempt = 1;
        } else if (qaAttempt <= 0) {
            activeQaMission = null;
        }
        generating = true;
        lastFullCode = "";
        OrionChatHistory.get().addUser(prompt);
        OrionChatHistory.get().beginAssistant();
        appendChatBubble(true, prompt);
        streamingAssistantView = appendChatBubble(false, "");
        generatingLabel.setVisibility(View.VISIBLE);
        generatingLabel.setText(qaAttempt > 1
                ? "⚡ Orion corrige (QA " + qaAttempt + ")…"
                : "⚡ Orion génère…");
        setOutputActionsEnabled(false);
        stopGenBtn.setVisibility(View.VISIBLE);
        typeRow.setVisibility(View.VISIBLE);
        if (chatScroll != null) {
            chatScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        scrollChatToEnd();

        try {
            ContextualFileStore store = ContextualFileStore.getInstance(activity);
            if (!pendingLoadKeywords.isEmpty()) {
                store.loadMultiple(new ArrayList<>(pendingLoadKeywords));
                pendingLoadKeywords.clear();
            }
            JSONObject params = new JSONObject().put("prompt", prompt);
            if (pendingResolvedTask != null) {
                if (pendingResolvedTask.mode != null) {
                    params.put("orion_mode", pendingResolvedTask.mode.name());
                }
                String raw = !TextUtils.isEmpty(frozenRawDemand)
                        ? frozenRawDemand
                        : pendingResolvedTask.rawInput;
                if (!TextUtils.isEmpty(raw)
                        && !PromptCompiler.looksLikeCompiledMission(raw)) {
                    params.put("raw_demand", raw);
                }
            } else if (!TextUtils.isEmpty(frozenRawDemand)
                    && !PromptCompiler.looksLikeCompiledMission(frozenRawDemand)) {
                params.put("raw_demand", frozenRawDemand);
            }
            // Propager le fileLocation du plan — build() ne re-résout pas
            putPropagatedFileLocation(params);
            String extra = joinAttachments();
            if (!TextUtils.isEmpty(extra)) {
                params.put("context", extra);
            }
            new OrionCodeTool().execute(activity, params, new ToolCallback() {
                @Override
                public void onSuccess(ToolResult result) {
                    main.post(() -> {
                        generating = false;
                        generatingLabel.setVisibility(View.GONE);
                        stopGenBtn.setVisibility(View.GONE);
                        String full = result != null ? result.contextForSynthesis() : "";
                        if (TextUtils.isEmpty(full) && streamingAssistantView != null) {
                            full = streamingAssistantView.getText().toString();
                        }
                        if (TextUtils.isEmpty(full)) {
                            full = OrionChatHistory.get().lastAssistantText();
                        }
                        OrionChatHistory.get().finishAssistant(full);
                        if (streamingAssistantView != null && !TextUtils.isEmpty(full)
                                && streamingAssistantView.getText().length() == 0) {
                            streamingAssistantView.setText(full);
                        }
                        lastFullCode = full != null ? full : "";
                        streamingAssistantView = null;
                        setOutputActionsEnabled(!TextUtils.isEmpty(lastFullCode));
                        pendingAttachments.clear();
                        refreshAttachmentsLine();
                        autoSaveGeneration(lastFullCode);
                        OrionStateStore.get().pingActivity();
                        if (listener != null) listener.onGenerationFinished(lastFullCode);
                        scrollChatToEnd();
                        maybeRunQa(lastFullCode);
                    });
                }

                @Override
                public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}

                @Override
                public void onProgress(String token) {
                    main.post(() -> {
                        if (token == null || token.isEmpty()) return;
                        OrionChatHistory.get().appendAssistant(token);
                        if (streamingAssistantView != null) {
                            streamingAssistantView.append(token);
                        }
                        scrollChatToEnd();
                    });
                }

                @Override
                public void onError(String error) {
                    main.post(() -> {
                        generating = false;
                        generatingLabel.setVisibility(View.GONE);
                        stopGenBtn.setVisibility(View.GONE);
                        String msg = error != null ? error : "Erreur Orion";
                        OrionChatHistory.get().finishAssistant(msg);
                        if (streamingAssistantView != null) {
                            streamingAssistantView.setText(msg);
                            streamingAssistantView.setTextColor(Color.parseColor("#FF6B6B"));
                        }
                        streamingAssistantView = null;
                        lastFullCode = OrionChatHistory.get().lastAssistantText();
                        setOutputActionsEnabled(!TextUtils.isEmpty(lastFullCode));
                        showError(error);
                        qaAttempt = 0;
                        activeQaMission = null;
                        activeChunkSession = null;
                        if (listener != null) listener.onGenerationFinished(lastFullCode);
                        scrollChatToEnd();
                    });
                }
            });
        } catch (Exception e) {
            generating = false;
            qaAttempt = 0;
            activeQaMission = null;
            showError(e.getMessage());
        }
    }

    private void offerExecutionPlan(ResolvedTask parentTask, String originalPrompt) {
        if (planBuildingInProgress || chunkingInProgress) return;
        planBuildingInProgress = true;
        generatingLabel.setVisibility(View.VISIBLE);
        generatingLabel.setText("📋 Construction du plan…");
        new Thread(() -> {
            try {
                PlanBuilder builder = PlanBuilder.create(activity);
                ExecutionPlan plan = builder.build(activity, parentTask, prompt ->
                        PegaseSession.get(activity).completeOrionPlanSync(prompt));
                main.post(() -> {
                    planBuildingInProgress = false;
                    generatingLabel.setVisibility(View.GONE);
                    showExecutionPlanDialog(plan, parentTask);
                });
            } catch (Exception e) {
                main.post(() -> {
                    planBuildingInProgress = false;
                    generatingLabel.setVisibility(View.GONE);
                    Trace.orionPlanError(e.getMessage());
                    Toast.makeText(activity,
                            "Plan impossible — envoi direct",
                            Toast.LENGTH_SHORT).show();
                    submitPrompt(originalPrompt, true);
                });
            }
        }, "orion-plan").start();
    }

    private void showExecutionPlanDialog(ExecutionPlan plan, ResolvedTask parentTask) {
        new AlertDialog.Builder(activity)
                .setTitle("📋 Plan — " + plan.title)
                .setMessage(plan.toReadableText())
                .setPositiveButton("On y va", (d, w) -> {
                    plan.status = ExecutionPlan.PlanStatus.APPROVED;
                    startApprovedPlan(plan, parentTask);
                })
                .setNeutralButton("Modifier", (d, w) -> {
                    plan.status = ExecutionPlan.PlanStatus.PENDING;
                    appendChatBubble(false,
                            "Quelle étape tu veux modifier ou supprimer ?");
                    scrollChatToEnd();
                    if (typeRow != null) typeRow.setVisibility(View.VISIBLE);
                    if (typeField != null) {
                        typeField.requestFocus();
                        typeField.setHint("Ex. supprimer étape 3, ou changer le header…");
                    }
                })
                .setNegativeButton("Annuler", (d, w) -> {
                    plan.status = ExecutionPlan.PlanStatus.REJECTED;
                    appendChatBubble(false, "Plan annulé.");
                    scrollChatToEnd();
                })
                .show();
    }

    private void startApprovedPlan(ExecutionPlan plan, ResolvedTask parentTask) {
        PlanBuilder builder = PlanBuilder.create(activity);
        List<TaskChunk> chunks = builder.toTaskChunks(activity, plan, parentTask);
        if (chunks.isEmpty()) {
            Toast.makeText(activity, "Plan vide — annulé", Toast.LENGTH_SHORT).show();
            return;
        }
        activeChunkSession = new OrionChunkSession(chunks);
        executeCurrentChunk();
    }

    private void offerChunkPlan(ResolvedTask parentTask, String originalPrompt) {
        if (chunkingInProgress) return;
        chunkingInProgress = true;
        generatingLabel.setVisibility(View.VISIBLE);
        generatingLabel.setText("📋 Découpage mission…");
        new Thread(() -> {
            try {
                TaskChunker chunker = TaskChunker.create(activity);
                List<TaskChunk> chunks = chunker.chunk(activity, parentTask, prompt ->
                        PegaseSession.get(activity).completeOrionChunkSync(prompt));
                main.post(() -> {
                    chunkingInProgress = false;
                    generatingLabel.setVisibility(View.GONE);
                    if (chunks.size() <= 1) {
                        submitPrompt(originalPrompt, true);
                        return;
                    }
                    showChunkPlanDialog(chunks);
                });
            } catch (Exception e) {
                main.post(() -> {
                    chunkingInProgress = false;
                    generatingLabel.setVisibility(View.GONE);
                    Trace.orionChunkError(e.getMessage());
                    Toast.makeText(activity,
                            "Découpage impossible — envoi direct",
                            Toast.LENGTH_SHORT).show();
                    submitPrompt(originalPrompt, true);
                });
            }
        }, "orion-chunk").start();
    }

    private void showChunkPlanDialog(List<TaskChunk> chunks) {
        StringBuilder plan = new StringBuilder();
        plan.append("C'est une grosse mission — je découpe en ")
                .append(chunks.size()).append(" étapes :\n\n");
        for (TaskChunk c : chunks) {
            plan.append(c.index).append(". ").append(c.summary).append('\n');
        }
        plan.append("\nOn commence par l'étape 1 ?");
        new AlertDialog.Builder(activity)
                .setTitle("Plan Orion")
                .setMessage(plan.toString())
                .setPositiveButton("Oui", (d, w) -> {
                    activeChunkSession = new OrionChunkSession(chunks);
                    executeCurrentChunk();
                })
                .setNegativeButton("Non", (d, w) -> {
                    appendChatBubble(false, "Découpage annulé.");
                    scrollChatToEnd();
                })
                .show();
    }

    private void executeCurrentChunk() {
        if (activeChunkSession == null) return;
        TaskChunk chunk = activeChunkSession.current();
        updateChunkProgress(chunk);
        // Garder le mode parent — ne pas re-resolve sur le compilé (contient « feature »)
        pendingResolvedTask = chunk.task;
        showModeBadge(chunk.task != null ? chunk.task.mode : null);
        submitPrompt(PromptCompiler.compile(chunk.task), true);
    }

    private void updateChunkProgress(TaskChunk chunk) {
        if (chunk == null || generatingLabel == null) return;
        int pct = (chunk.index * 100) / chunk.total;
        generatingLabel.setVisibility(View.VISIBLE);
        generatingLabel.setText("📋 " + chunk.toProgressLabel() + "\n"
                + chunkProgressBar(pct));
    }

    private static String chunkProgressBar(int pct) {
        int clamped = Math.max(0, Math.min(100, pct));
        int filled = clamped / 5;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(i < filled ? '█' : '░');
        }
        sb.append("  ").append(clamped).append('%');
        return sb.toString();
    }

    private void maybeRunQa(String generated) {
        if (!qaEnabled || TextUtils.isEmpty(activeQaMission)
                || TextUtils.isEmpty(generated)
                || !OrionQaLoop.looksLikeMission(activeQaMission)) {
            qaAttempt = 0;
            return;
        }
        final String mission = activeQaMission;
        final int attempt = qaAttempt <= 0 ? 1 : qaAttempt;
        final ResolvedTask qaTask = pendingResolvedTask;
        generatingLabel.setVisibility(View.VISIBLE);
        generatingLabel.setText("🔍 QA Pégase…");
        OrionQaLoop.evaluate(activity, qaTask, mission, generated, attempt,
                (missionBlock, diff) -> {
                    try {
                        // Phase 6 : OrionQaChecker passe déjà le prompt de vérification complet
                        return PegaseSession.get(activity)
                                .completeOrionQaSync(missionBlock, diff);
                    } catch (Exception e) {
                        return "CONFORME";
                    }
                },
                new OrionQaLoop.Callback() {
                    @Override
                    public void onCompliant(OrionQaReport report, int att) {
                        main.post(() -> {
                            generatingLabel.setVisibility(View.GONE);
                            qaAttempt = 0;
                            if (activeChunkSession != null) {
                                TaskChunk chunk = activeChunkSession.current();
                                if (activeChunkSession.hasNext()) {
                                    new AlertDialog.Builder(activity)
                                            .setTitle("Étape terminée")
                                            .setMessage("✅ " + chunk.toProgressLabel()
                                                    + " — continuer ?")
                                            .setPositiveButton("Oui", (d, w) -> {
                                                activeChunkSession.next();
                                                executeCurrentChunk();
                                            })
                                            .setNegativeButton("Non", (d, w) -> {
                                                appendChatBubble(false,
                                                        "Session arrêtée après étape "
                                                                + chunk.index + ".");
                                                activeChunkSession = null;
                                                activeQaMission = null;
                                                scrollChatToEnd();
                                            })
                                            .show();
                                } else {
                                    activeChunkSession = null;
                                    activeQaMission = null;
                                    appendChatBubble(false,
                                            "✅ Toutes les étapes terminées — push ?");
                                    Toast.makeText(activity,
                                            "Toutes les étapes OK — tu peux push",
                                            Toast.LENGTH_LONG).show();
                                    scrollChatToEnd();
                                }
                                return;
                            }
                            activeQaMission = null;
                            Toast.makeText(activity,
                                    "QA OK — c'est bon, tu peux push",
                                    Toast.LENGTH_LONG).show();
                            appendChatBubble(false, "✅ QA conforme"
                                    + (TextUtils.isEmpty(report.reason) ? "."
                                    : " — " + report.reason));
                            scrollChatToEnd();
                        });
                    }

                    @Override
                    public void onRetry(String augmentedMission, OrionQaReport report, int att) {
                        main.post(() -> {
                            generatingLabel.setVisibility(View.GONE);
                            appendChatBubble(false, "⚠️ QA : " + report.reason
                                    + "\n→ Orion régénère avec contraintes…");
                            scrollChatToEnd();
                            qaAttempt = att + 1;
                            activeQaMission = augmentedMission;
                            Toast.makeText(activity,
                                    "Hors scope détecté — correction auto…",
                                    Toast.LENGTH_SHORT).show();
                            submitPrompt(augmentedMission);
                        });
                    }

                    @Override
                    public void onGiveUp(OrionQaReport report) {
                        main.post(() -> {
                            generatingLabel.setVisibility(View.GONE);
                            qaAttempt = 0;
                            if (activeChunkSession != null) {
                                TaskChunk chunk = activeChunkSession.current();
                                activeChunkSession = null;
                                activeQaMission = null;
                                appendChatBubble(false, "⚠️ Étape " + chunk.index
                                        + " échouée : " + report.reason);
                                scrollChatToEnd();
                                new AlertDialog.Builder(activity)
                                        .setTitle("QA — étape échouée")
                                        .setMessage("⚠️ Étape " + chunk.index
                                                + " échouée : " + report.reason
                                                + "\n\nTu veux voir le diff ?")
                                        .setPositiveButton("Voir le diff", (d, w) ->
                                                showQaDiff(report.diffSummary))
                                        .setNegativeButton("OK", null)
                                        .show();
                                return;
                            }
                            activeQaMission = null;
                            appendChatBubble(false, "❌ QA : après "
                                    + OrionQaLoop.MAX_ATTEMPTS
                                    + " essais, Orion ne respecte pas assez la mission.\n"
                                    + report.reason);
                            scrollChatToEnd();
                            new AlertDialog.Builder(activity)
                                    .setTitle("QA — pas conforme")
                                    .setMessage(report.reason
                                            + "\n\nTu veux voir le diff ?")
                                    .setPositiveButton("Voir le diff", (d, w) ->
                                            showQaDiff(report.diffSummary))
                                    .setNegativeButton("OK", null)
                                    .show();
                        });
                    }

                    @Override
                    public void onProgress(String message) {
                        main.post(() -> {
                            if (generatingLabel != null) {
                                generatingLabel.setVisibility(View.VISIBLE);
                                generatingLabel.setText(message != null ? message : "QA…");
                            }
                        });
                    }
                });
    }

    private void showQaDiff(String diff) {
        if (TextUtils.isEmpty(diff)) {
            Toast.makeText(activity, "Pas de diff", Toast.LENGTH_SHORT).show();
            return;
        }
        OrionUi.darkDialog(activity)
                .setTitle("Diff QA")
                .setView(OrionUi.darkMonoScroll(activity, diff))
                .setPositiveButton("OK", null)
                .show();
    }

    private String joinAttachments() {
        if (pendingAttachments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pendingAttachments.entrySet()) {
            sb.append("--- ").append(e.getKey()).append(" ---\n")
                    .append(e.getValue()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Propage le fileLocation du plan (ou de pendingResolvedTask) vers build().
     * Évite un second FileSearcher fragile à l'exécution.
     */
    private void putPropagatedFileLocation(JSONObject params) {
        if (params == null) return;
        FileLocation loc = null;
        if (pendingResolvedTask != null && pendingResolvedTask.fileLocation != null
                && !TextUtils.isEmpty(pendingResolvedTask.fileLocation.filename)) {
            loc = pendingResolvedTask.fileLocation;
        }
        if (loc == null) {
            try {
                loc = PegaseSession.get(activity).getLastOrionPlanFileLocation();
            } catch (Exception ignored) {
            }
        }
        if (loc == null || TextUtils.isEmpty(loc.filename)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("filename", loc.filename);
            o.put("line", loc.line);
            o.put("snippet", loc.snippet != null ? loc.snippet : "");
            params.put("file_location", o);
        } catch (Exception ignored) {
        }
    }

    private void showAttachMenu() {
        CharSequence[] items = new CharSequence[]{
                "Depuis mes contextes Pégase",
                "Importer un .md du téléphone",
                "Effacer les pièces jointes"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Joindre à Orion")
                .setItems(items, (d, which) -> {
                    if (which == 0) pickFromContexts();
                    else if (which == 1) pickMdFromPhone();
                    else {
                        pendingAttachments.clear();
                        pendingLoadKeywords.clear();
                        refreshAttachmentsLine();
                    }
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void pickFromContexts() {
        List<ContextualFileStore.Meta> metas =
                ContextualFileStore.getInstance(activity).listContexts();
        if (metas.isEmpty()) {
            Toast.makeText(activity, "Aucun contexte .md", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[metas.size()];
        boolean[] checked = new boolean[metas.size()];
        for (int i = 0; i < metas.size(); i++) {
            ContextualFileStore.Meta m = metas.get(i);
            labels[i] = m.filename + (m.loaded ? " ✓" : "");
            checked[i] = pendingAttachments.containsKey(m.filename) || m.loaded;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Contextes à joindre")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    ContextualFileStore store = ContextualFileStore.getInstance(activity);
                    for (int i = 0; i < metas.size(); i++) {
                        ContextualFileStore.Meta m = metas.get(i);
                        if (checked[i]) {
                            String content = store.readFile(m.filename);
                            if (!TextUtils.isEmpty(content)) {
                                pendingAttachments.put(m.filename, content);
                                if (!pendingLoadKeywords.contains(m.keyword)) {
                                    pendingLoadKeywords.add(m.keyword);
                                }
                            }
                        } else {
                            pendingAttachments.remove(m.filename);
                            pendingLoadKeywords.remove(m.keyword);
                        }
                    }
                    refreshAttachmentsLine();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void pickMdFromPhone() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/markdown", "text/plain", "text/*", "application/octet-stream"
            });
            activity.startActivityForResult(intent, OrionFragment.REQ_PICK_MD);
        } catch (Exception e) {
            Toast.makeText(activity, "Sélecteur de fichiers indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshAttachmentsLine() {
        if (attachmentsLine == null) return;
        if (pendingAttachments.isEmpty()) {
            attachmentsLine.setVisibility(View.GONE);
            attachmentsLine.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder("📎 ");
        boolean first = true;
        for (String label : pendingAttachments.keySet()) {
            if (!first) sb.append(" · ");
            first = false;
            sb.append(label);
        }
        attachmentsLine.setText(sb.toString());
        attachmentsLine.setVisibility(View.VISIBLE);
    }

    private String readUriUtf8(Uri uri) throws Exception {
        InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("stream null");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
                if (sb.length() > 400_000) break;
            }
        }
        return sb.toString();
    }

    private void autoSaveGeneration(String full) {
        if (GeneratedFiles.isEmpty(full)) return;
        try {
            // Toujours parser la dernière sortie (merge / refresh session)
            List<OrionFileSession.OrionFile> sessionFiles =
                    OrionFileStore.get().ingestOrionOutput(activity, full, null);
            if (sessionFiles == null || sessionFiles.isEmpty()) return;

            // Web HTML/CSS/JS → projet actif (si possible) + aperçu local + lint
            OrionFileTools.ApplyResult applied =
                    OrionFileTools.applyWebSessionToProjectDetailed(activity, sessionFiles);
            String mainPage = OrionFileTools.pickMainPage(sessionFiles);
            boolean hasWeb = false;
            for (OrionFileSession.OrionFile of : sessionFiles) {
                if (of != null && OrionFileTools.isWebAsset(of.path)) {
                    hasWeb = true;
                    break;
                }
            }
            if (hasWeb && mainPage != null) {
                generatingLabel.setVisibility(View.VISIBLE);
                generatingLabel.setTextColor(Color.parseColor(OrionUi.CYAN));
                if (applied.filesApplied > 0) {
                    String label = "🌐 " + applied.filesApplied
                            + " fichier(s) web → projet · aperçu";
                    if (applied.hasLintErrors()) {
                        label += " · ⚠ lint";
                    }
                    generatingLabel.setText(label);
                    Toast.makeText(activity,
                            applied.hasLintErrors()
                                    ? applied.filesApplied + " fichier(s) — lint à corriger"
                                    : applied.filesApplied + " fichier(s) appliqués — aperçu",
                            Toast.LENGTH_SHORT).show();
                } else {
                    generatingLabel.setText("🌐 Aperçu page");
                }
                openSessionPagePreview(mainPage, sessionFiles);
                return;
            }

            java.util.List<java.io.File> diskFiles = new ArrayList<>();
            for (OrionFileSession.OrionFile of : sessionFiles) {
                java.io.File f = GeneratedFiles.findByName(activity, of.path);
                if (f != null) diskFiles.add(f);
            }
            if (diskFiles.isEmpty()) {
                diskFiles = GeneratedFiles.listRecent(activity);
            }

            final java.util.List<java.io.File> files = diskFiles;
            java.io.File primary = files.isEmpty() ? null : pickPrimaryFile(files);
            StringBuilder list = new StringBuilder();
            for (OrionFileSession.OrionFile of : sessionFiles) {
                if (list.length() > 0) list.append('\n');
                list.append(of.statusLabel()).append(' ').append(of.path)
                        .append(" · ").append(of.lineCount()).append(" lignes");
            }
            String headline = sessionFiles.size() == 1
                    ? "Nouveaux fichiers → projet"
                    : sessionFiles.size() + " fichiers → projet";
            String active = OrionProjectStore.get(activity).getActiveProject();
            String projectHint = TextUtils.isEmpty(active)
                    ? "Choisis ou crée un projet pour les garder."
                    : "Projet actif : « " + active + " ».";

            generatingLabel.setVisibility(View.VISIBLE);
            generatingLabel.setTextColor(Color.parseColor(OrionUi.CYAN));
            generatingLabel.setText("💾 " + headline);

            boolean multi = sessionFiles.size() > 1;
            final List<OrionFileSession.OrionFile> toSave = sessionFiles;
            new AlertDialog.Builder(activity)
                    .setTitle(headline)
                    .setMessage(list + "\n\n" + projectHint
                            + "\nReview possible dans le panneau ci-dessous.")
                    .setPositiveButton("Dans le projet", (d, w) -> {
                        if (listener != null) listener.onSaveToProjectRequested(toSave);
                    })
                    .setNeutralButton(multi ? "ZIP" : "Partager", (d, w) -> {
                        if (files.isEmpty()) return;
                        if (multi) {
                            GeneratedFiles.shareAsZip(activity, files, null);
                        } else if (primary != null) {
                            GeneratedFiles.share(activity, primary);
                        }
                    })
                    .setNegativeButton("Plus tard", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(activity, "Auto-save impossible — utilise ⋮ → Dans le projet",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openSessionPagePreview(String mainPath,
            List<OrionFileSession.OrionFile> sessionFiles) {
        String content = "";
        Map<String, String> siblings = new HashMap<>();
        if (sessionFiles != null) {
            for (OrionFileSession.OrionFile f : sessionFiles) {
                if (f == null || f.path == null) continue;
                siblings.put(f.path, f.content != null ? f.content : "");
                if (f.path.equalsIgnoreCase(mainPath)) {
                    content = f.content != null ? f.content : "";
                }
            }
        }
        // Fusionner aussi le projet actif (css/js déjà présents)
        OrionProjectStore store = OrionProjectStore.get(activity);
        for (OrionProjectStore.ProjectFile pf : store.getProjectFiles()) {
            if (pf == null || pf.name == null) continue;
            if (!siblings.containsKey(pf.name)) {
                String body = store.readFile(pf.name);
                if (body != null) siblings.put(pf.name, body);
            }
        }
        if (TextUtils.isEmpty(content) && siblings.containsKey(mainPath)) {
            content = siblings.get(mainPath);
        }
        OrionPagePreview.openFullscreen(activity, mainPath, content, siblings);
    }

    private void previewFromSessionOrCode(boolean livePod) {
        List<OrionFileSession.OrionFile> sessionFiles = null;
        if (OrionFileStore.get().hasSession()) {
            sessionFiles = OrionFileStore.get().getCurrentSession().getFiles();
        } else if (!TextUtils.isEmpty(lastFullCode)) {
            sessionFiles = OrionFileStore.get().ingestOrionOutput(activity, lastFullCode, null);
        }
        String main = OrionFileTools.pickMainPage(sessionFiles);
        if (main == null && sessionFiles != null) {
            for (OrionFileSession.OrionFile f : sessionFiles) {
                if (f != null && OrionPagePreview.isPage(f.path, f.content)) {
                    main = f.path;
                    break;
                }
            }
        }
        if (main == null) {
            // Projet actif index.html
            OrionProjectStore store = OrionProjectStore.get(activity);
            String idx = store.readFile("index.html");
            if (!TextUtils.isEmpty(idx)) {
                Map<String, String> sib = new HashMap<>();
                for (OrionProjectStore.ProjectFile pf : store.getProjectFiles()) {
                    if (pf == null || pf.name == null) continue;
                    String body = store.readFile(pf.name);
                    if (body != null) sib.put(pf.name, body);
                }
                if (livePod && PodFileClient.isOnline()) {
                    String url = PodFileClient.previewUrl(store.getActiveProject(), "index.html");
                    if (!TextUtils.isEmpty(url)) {
                        OrionPagePreview.openLiveUrl(activity, "index.html (live)", url);
                        return;
                    }
                }
                OrionPagePreview.openFullscreen(activity, "index.html", idx, sib);
                return;
            }
            Toast.makeText(activity, "Aucune page HTML à prévisualiser", Toast.LENGTH_SHORT).show();
            return;
        }
        if (livePod && PodFileClient.isOnline()) {
            OrionFileTools.applyWebSessionToProjectDetailed(activity, sessionFiles);
            String url = PodFileClient.previewUrl(
                    OrionProjectStore.get(activity).getActiveProject(), main);
            if (!TextUtils.isEmpty(url)) {
                OrionPagePreview.openLiveUrl(activity, main + " (live)", url);
                return;
            }
            Toast.makeText(activity, "Aperçu pod indisponible — local",
                    Toast.LENGTH_SHORT).show();
        }
        openSessionPagePreview(main, sessionFiles);
    }

    private void showOrionActionsMenu() {
        boolean hasCode = !TextUtils.isEmpty(lastFullCode);
        boolean hasPod = !TextUtils.isEmpty(OrionStateStore.get().getPodId());
        java.util.List<String> items = new java.util.ArrayList<>();
        if (hasCode) {
            items.add("Copier le code");
            items.add("Dans le projet");
            items.add("Aperçu page (local)");
            if (PodFileClient.isOnline()
                    && !TextUtils.isEmpty(OrionProjectStore.get(activity).getActiveProject())) {
                items.add("Aperçu live (pod)");
            }
            items.add("Partager (Fichiers)");
        }
        if (hasPod) items.add("Ouvrir dans VS Code");
        items.add("Joindre un fichier");
        if (generating) items.add("Arrêter la génération");
        boolean learnOn = CodeLearnStore.isEnabled(activity);
        items.add(learnOn
                ? "Apprentissage code : ON (questions)"
                : "Apprentissage code : OFF");
        items.add(qaEnabled ? "QA auto : ON" : "QA auto : OFF");
        items.add("Paramètres Orion");
        if (items.isEmpty()) {
            Toast.makeText(activity, "Rien à faire pour l'instant", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = items.toArray(new String[0]);
        PegaseSheets.show(activity, "Actions", labels, which -> {
            String pick = labels[which];
            if (pick.startsWith("Copier")) copyCode();
            else if (pick.startsWith("Dans le projet")) {
                List<OrionFileSession.OrionFile> sessionFiles = null;
                if (OrionFileStore.get().hasSession()) {
                    sessionFiles = OrionFileStore.get().getCurrentSession().getFiles();
                } else if (!TextUtils.isEmpty(lastFullCode)) {
                    sessionFiles = OrionFileStore.get()
                            .ingestOrionOutput(activity, lastFullCode, null);
                }
                if (sessionFiles == null || sessionFiles.isEmpty()) {
                    Toast.makeText(activity, "Rien à enregistrer", Toast.LENGTH_SHORT).show();
                } else if (listener != null) {
                    listener.onSaveToProjectRequested(sessionFiles);
                }
            } else if (pick.startsWith("Aperçu page")) {
                previewFromSessionOrCode(false);
            } else if (pick.startsWith("Aperçu live")) {
                previewFromSessionOrCode(true);
            } else if (pick.startsWith("Partager")) saveCode(true);
            else if (pick.startsWith("Ouvrir")) openCodeServer();
            else if (pick.startsWith("Joindre")) showAttachMenu();
            else if (pick.startsWith("Arrêter")) stopGeneration();
            else if (pick.startsWith("Apprentissage code")) {
                boolean next = !CodeLearnStore.isEnabled(activity);
                CodeLearnStore.setEnabled(activity, next);
                Toast.makeText(activity, next
                                ? "Pégase posera des questions si le contexte code manque"
                                : "Compilation directe sans questions",
                        Toast.LENGTH_LONG).show();
            } else if (pick.startsWith("QA auto")) {
                qaEnabled = !qaEnabled;
                Toast.makeText(activity, qaEnabled
                                ? "QA : vérifie le diff après chaque Mission"
                                : "QA désactivé",
                        Toast.LENGTH_SHORT).show();
            } else if (pick.startsWith("Paramètres")) {
                activity.startActivity(new Intent(activity, OrionSettingsActivity.class));
            }
        });
    }

    private static java.io.File pickPrimaryFile(java.util.List<java.io.File> files) {
        for (java.io.File f : files) {
            if (!f.getName().startsWith("orion_full_")) return f;
        }
        return files.get(0);
    }

    private void stopGeneration() {
        OrionOllamaClient.requestCancel();
        generating = false;
        generatingLabel.setVisibility(View.GONE);
        stopGenBtn.setVisibility(View.GONE);
        Toast.makeText(activity, "Arrêt demandé…", Toast.LENGTH_SHORT).show();
    }

    private void copyCode() {
        String text = currentOutput();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(activity, "Rien à copier", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("orion_code", text));
            Toast.makeText(activity, "Code copié", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveCode(boolean alsoShare) {
        String text = currentOutput();
        if (GeneratedFiles.isEmpty(text)) {
            Toast.makeText(activity, "Rien à enregistrer", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.io.File out = GeneratedFiles.saveOrionOutput(activity, text);
            Toast.makeText(activity, "Sauvé : " + out.getName() + " (onglet Fichiers)",
                    Toast.LENGTH_SHORT).show();
            if (alsoShare) {
                GeneratedFiles.share(activity, out);
            } else if (activity instanceof PegaseInterfaceHost) {
                ((PegaseInterfaceHost) activity).openFilesTab();
            }
        } catch (Exception e) {
            Toast.makeText(activity, "Enregistrement impossible", Toast.LENGTH_SHORT).show();
        }
    }

    private String currentOutput() {
        if (!TextUtils.isEmpty(lastFullCode)) return lastFullCode;
        return OrionChatHistory.get().lastAssistantText();
    }

    private void setOutputActionsEnabled(boolean on) {
        if (copyBtn != null) copyBtn.setEnabled(on);
        if (saveBtn != null) saveBtn.setEnabled(on);
        if (shareBtn != null) shareBtn.setEnabled(on);
    }

    private void openCodeServer() {
        String url = OrionStateStore.get().getCodeServerUrl();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(activity, "Pas de pod actif", Toast.LENGTH_SHORT).show();
            return;
        }
        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void showError(String msg) {
        String clean = msg != null ? msg : "Erreur Orion";
        if (clean.length() > 400) clean = clean.substring(0, 397) + "…";
        Toast.makeText(activity, clean, Toast.LENGTH_LONG).show();
        generatingLabel.setVisibility(View.VISIBLE);
        generatingLabel.setTextColor(Color.parseColor("#FF6B6B"));
        generatingLabel.setText(clean);
    }

    private TextView appendChatBubble(boolean fromUser, String text) {
        TextView name = new TextView(activity);
        name.setText(fromUser ? "Toi" : "Orion");
        name.setTextSize(11);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(Color.parseColor(fromUser ? "#9AD4FF" : OrionUi.CYAN));
        name.setPadding(0, OrionUi.dp(activity, 8), 0, OrionUi.dp(activity, 2));
        chatColumn.addView(name, OrionUi.matchWrap());

        TextView body = new TextView(activity);
        body.setText(text != null ? text : "");
        body.setTextSize(13);
        body.setTextColor(Color.WHITE);
        // Sélection/copie OK, mais ne pas voler le focus clavier du champ « Taper »
        body.setTextIsSelectable(true);
        body.setFocusable(false);
        body.setFocusableInTouchMode(false);
        if (!fromUser) {
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextSize(12);
        }
        body.setPadding(OrionUi.dp(activity, 8), OrionUi.dp(activity, 6),
                OrionUi.dp(activity, 8), OrionUi.dp(activity, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(OrionUi.dp(activity, 8));
        bg.setColor(Color.parseColor(fromUser ? "#2235D0DD" : "#22FFFFFF"));
        body.setBackground(bg);
        LinearLayout.LayoutParams lp = OrionUi.matchWrap();
        lp.bottomMargin = OrionUi.dp(activity, 2);
        chatColumn.addView(body, lp);
        return body;
    }

    private void scrollChatToEnd() {
        if (chatScroll == null) return;
        chatScroll.post(() -> {
            // Ne pas utiliser fullScroll(FOCUS_DOWN) : ça focus la dernière bulle
            // sélectionnable et empêche de taper dans le champ Orion.
            View child = chatScroll.getChildAt(0);
            if (child != null) {
                int target = Math.max(0, child.getHeight() - chatScroll.getHeight());
                chatScroll.scrollTo(0, target);
            }
        });
    }
}
