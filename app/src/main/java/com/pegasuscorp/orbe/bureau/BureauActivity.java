package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionContext;
import com.pegasuscorp.orbe.session.SessionObserver;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.ui.ThinkingView;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;
import com.pegasuscorp.orbe.voice.VoiceManager;

import java.util.Locale;

/**
 * Bureau téléphone — shell : cycle de vie, voix, Orion, autosave.
 * UI Markdown dans {@link BureauMarkdownPanel}.
 */
public class BureauActivity extends AppCompatActivity implements BureauHost {

    private static final long AUTOSAVE_MS = 30_000L;

    private BureauMarkdownPanel panel;
    private BureauContextActions contextActions;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SessionObserver bureauThinkingObserver = new SessionObserver() {
        @Override
        public void onReply(String text, boolean toolFired) {}

        @Override
        public void onToolResult(ToolResult result) {}

        @Override
        public void onError(String message) {}

        @Override
        public void onToolStart(String toolId) {
            ui.post(() -> {
                if (isFinishing() || panel == null) return;
                ThinkingView tv = panel.getThinkingView();
                if (tv == null) return;
                tv.onToolStart(toolId);
            });
        }

        @Override
        public void onToolEnd(String toolId, boolean ok) {
            ui.post(() -> {
                if (isFinishing() || panel == null) return;
                ThinkingView tv = panel.getThinkingView();
                if (tv == null) return;
                tv.onToolEnd(toolId, ok);
            });
        }

        @Override
        public void onLlmStart() {
            ui.post(() -> {
                if (isFinishing() || panel == null) return;
                ThinkingView tv = panel.getThinkingView();
                if (tv == null) return;
                tv.onLlmStart();
            });
        }
    };
    private final Runnable periodicSave = new Runnable() {
        @Override public void run() {
            if (panel != null && panel.isDirty()) panel.persistNow();
            ui.postDelayed(this, AUTOSAVE_MS);
        }
    };
    private VoiceManager voiceManager;
    private boolean listening;

    public static void open(Context context) {
        context.startActivity(new Intent(context, BureauActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static void openProject(Context context, String slug) {
        Intent i = new Intent(context, BureauActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (slug != null) i.putExtra(BureauPlanningActivity.EXTRA_OPEN_SLUG, slug);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Trace.init(this);
        PegaseSession.get(this).init(new SessionContext(Channel.BUREAU, false));

        panel = new BureauMarkdownPanel(this, ui, new BureauMarkdownPanel.Actions() {
            @Override public void toggleDictate() { BureauActivity.this.toggleDictate(); }
            @Override public void goToOrion() { BureauActivity.this.goToOrion(); }
            @Override public void showOpenPicker() { contextActions.showOpenPicker(); }
            @Override public void showSaveDialog() { contextActions.showSaveDialog(); }
            @Override public void speak(String text, Runnable after) {
                BureauActivity.this.speak(text, after);
            }
            @Override public void finishBureau() { finish(); }
            @Override public void openNewProjectInterview() {
                BureauPlanningActivity.open(BureauActivity.this);
            }
        });
        contextActions = new BureauContextActions(this, panel);

        String openSlug = getIntent() != null
                ? getIntent().getStringExtra(BureauPlanningActivity.EXTRA_OPEN_SLUG) : null;

        DrawerLayout root = panel.build();
        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        if (openSlug != null && !openSlug.isEmpty()
                && BureauProjectStore.exists(this, openSlug)) {
            panel.openStructuredProject(openSlug);
            Trace.bureauAction("open", panel.getCurrentFilename());
        } else {
            panel.setCurrentFilename(BureauSessionStore.todayFilename());
            Trace.bureauAction("open", panel.getCurrentFilename());
            panel.setDocumentText(BureauSessionStore.loadToday(this));
            panel.applyStructuredMode(false);
            panel.updateFileLabel();
            panel.markClean();
            panel.refreshPreview();
            panel.refreshOutline();
            panel.reloadThreadUi();
            panel.setStatus("Prêt");
        }

        voiceManager = ChatVoiceBridge.getSharedVoice(this);
        ChatVoiceBridge.registerBureau(this);
        ui.postDelayed(periodicSave, AUTOSAVE_MS);
    }

    private void toggleDictate() {
        if (listening) {
            stopListening();
            return;
        }
        listening = true;
        panel.setStatus("Écoute…");
        if (voiceManager != null) voiceManager.startListening();
    }

    private void stopListening() {
        listening = false;
        if (voiceManager != null) voiceManager.stopListening();
        if (panel != null && !panel.isPegaseBusy()) {
            panel.setStatus(panel.isDirty() ? "Modifié…" : "Prêt");
        }
    }

    @Override
    public void handleBureauVoice(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return;
        listening = false;
        String t = transcript.trim();
        String fold = t.toLowerCase(Locale.ROOT).replace('é', 'e').replace('è', 'e');
        Trace.bureauAction("voice_input", tracePreview(t));

        if (fold.contains("ferme") && fold.contains("bureau")) {
            Trace.bureauAction("close", null);
            finish();
            return;
        }
        if (fold.contains("aperçu") || fold.contains("apercu")) {
            Trace.bureauAction("mode_preview", null);
            if (!panel.isPreviewMode()) panel.togglePreviewMode();
            speak("Voici l'aperçu.", null);
            return;
        }
        if (fold.contains("edite") || fold.contains("édite") || fold.contains("mode edition")) {
            if (panel.isStructuredMode()) {
                speak("Ce projet est en lecture seule. Utilise les actions.", null);
                return;
            }
            Trace.bureauAction("mode_edit", null);
            if (panel.isPreviewMode()) panel.togglePreviewMode();
            speak("Mode édition.", null);
            return;
        }
        if ((fold.contains("fil") && fold.contains("pegase"))
                || fold.contains("ouvre le fil")
                || fold.contains("discuter")) {
            Trace.bureauAction("open_fil", null);
            panel.ensurePegasePanelExpanded();
            speak("Fil Pégase ouvert.", null);
            return;
        }
        if (fold.equals("pegase")
                || fold.startsWith("pegase ")
                || fold.contains("aide moi sur ce plan")
                || fold.contains("structure le plan")) {
            Trace.bureauAction("pegase_button", null);
            panel.runPegaseButton();
            return;
        }

        if (tryLocalEdit(t, fold)) return;

        if (BureauMarkdownBrain.wantsLlmEdit(t)) {
            panel.runPlanRequest(t, null);
            return;
        }

        if (panel.isStructuredMode()) {
            panel.runPlanRequest(t, null);
            return;
        }

        Trace.bureauAction("dictation", tracePreview(t));
        panel.appendText(t + "\n");
        panel.setStatus("Dictée ajoutée");
    }

    private static String tracePreview(String text) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= 120 ? t : t.substring(0, 119) + "…";
    }

    private boolean tryLocalEdit(String t, String fold) {
        if (panel.isStructuredMode()) return false;
        if (fold.startsWith("nouvelle section ") || fold.startsWith("ajoute une section ")) {
            String name = t.replaceFirst("(?i)^(?:nouvelle section|ajoute une section)\\s+", "").trim();
            if (!name.isEmpty()) {
                Trace.bureauAction("local_section", name);
                panel.appendText("\n## " + BureauMarkdownPanel.capitalize(name) + "\n\n");
                panel.setStatus("Section ajoutée");
                return true;
            }
        }
        if (fold.startsWith("note ") || fold.startsWith("ajoute ") || fold.startsWith("ecris ")
                || fold.startsWith("écris ")) {
            String body = t.replaceFirst("(?i)^(note|ajoute|ecris|écris)\\s+", "").trim();
            if (!body.isEmpty()) {
                Trace.bureauAction("local_note", tracePreview(body));
                String next = BureauMarkdownOutline.insertUnderSection(
                        panel.getDocumentText(),
                        BureauPlanTemplate.SECTION_NOTES,
                        "- " + body);
                panel.setDocumentText(next);
                panel.markDirtyAndAutosave();
                panel.setStatus("Noté");
                return true;
            }
        }
        return false;
    }

    private void goToOrion() {
        panel.persistNow();
        String editing = panel.getEditingContextKeyword();
        if (editing == null || editing.trim().isEmpty()) {
            // Force un nom de projet avant le pont
            final EditText input = new EditText(this);
            input.setHint("Nom du plan / contexte");
            input.setTextColor(BureauMarkdownPanel.TEXT);
            input.setHintTextColor(BureauMarkdownPanel.MUTED);
            contextActions.suggestKeyword(input);
            new AlertDialog.Builder(this)
                    .setTitle("Vers Orion")
                    .setMessage("Enregistre d’abord le plan comme contexte nommé.")
                    .setView(input)
                    .setPositiveButton("Sauver et ouvrir", (d, w) -> {
                        String key = input.getText().toString().trim();
                        if (key.isEmpty()) {
                            Toast.makeText(this, "Indique un nom", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        contextActions.saveContextAndConfirm(key, ContextualFileStore.getInstance(this)
                                .contextExists(key));
                        openOrionBridge(key);
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }
        contextActions.saveContextAndConfirm(editing, true);
        openOrionBridge(editing);
    }

    private void openOrionBridge(String keyword) {
        ContextualFileStore.getInstance(this).load(keyword);
        Trace.bureauAction("bureau → orion", keyword);
        String firstTask = firstUncheckedTaskHint();
        String prompt = "MODE GREENFIELD (création depuis Bureau).\n"
                + "Contexte « " + keyword + " » chargé.\n"
                + "N'implémente QUE la première tâche utile"
                + (firstTask.isEmpty() ? "" : " : « " + firstTask + " »")
                + ".\n"
                + "Pas tout le plan d'un coup — un slice minimal et testable.\n"
                + "Langage : HTML + CSS + JS (index.html / style.css / app.js) — "
                + "PAS de Java, Kotlin ni Android, sauf si le plan l'exige clairement.\n"
                + "Plusieurs petits fichiers OK pour ce slice ; pas de refactor global.";
        PegaseInterfaceState.openOrionWithPrompt(this, prompt);
        Toast.makeText(this, "Orion : 1ʳᵉ tâche de « " + keyword + " » (génère dès READY)",
                Toast.LENGTH_SHORT).show();
    }

    /** Première tâche non cochée du document ouvert (aperçu pour le prompt Orion). */
    private String firstUncheckedTaskHint() {
        if (panel == null) return "";
        if (panel.isStructuredMode() && panel.getStructuredSlug() != null) {
            BureauProject p = BureauProjectStore.load(this, panel.getStructuredSlug());
            if (p != null) {
                for (BureauProject.Task t : p.tasks) {
                    if (t != null && !t.done && t.text != null && !t.text.trim().isEmpty()) {
                        String s = t.text.trim();
                        return s.length() > 80 ? s.substring(0, 77) + "…" : s;
                    }
                }
            }
        }
        String md = panel.getDocumentText();
        for (BureauMarkdownOutline.TaskItem t : BureauMarkdownOutline.tasks(md)) {
            if (t != null && !t.done && t.text != null && !t.text.trim().isEmpty()) {
                String s = t.text.trim();
                return s.length() > 80 ? s.substring(0, 77) + "…" : s;
            }
        }
        return "";
    }

    private void speak(String text, Runnable after) {
        if (text == null || text.isEmpty()) {
            if (after != null) after.run();
            return;
        }
        if (voiceManager != null) voiceManager.speak(text, after);
        else if (after != null) after.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PegaseSession.get(this).addObserver(bureauThinkingObserver);
    }

    @Override
    protected void onPause() {
        PegaseSession.get(this).removeObserver(bureauThinkingObserver);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (panel != null) panel.persistNow();
        stopListening();
    }

    @Override
    protected void onDestroy() {
        PegaseSession.get(this).removeObserver(bureauThinkingObserver);
        ui.removeCallbacks(periodicSave);
        if (panel != null) panel.clearCallbacks();
        ChatVoiceBridge.unregisterBureau(this);
        PegaseWakeController.setBureauActive(false);
        VoiceWakeClient.get().startListening(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = panel != null ? panel.getDrawerLayout() : null;
        if (drawer != null && drawer.isDrawerOpen(Gravity.START)) {
            drawer.closeDrawer(Gravity.START);
            return;
        }
        if (panel != null) panel.persistNow();
        super.onBackPressed();
    }
}
