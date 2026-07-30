package com.pegasuscorp.orbe.bureau;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.GeneratedFiles;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.ui.ThinkingView;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI Markdown du Bureau : barre, outline, éditeur/aperçu, fil Pégase, barre bas.
 */
final class BureauMarkdownPanel {

    static final int BG = Color.parseColor("#0B0E14");
    static final int SURFACE = Color.parseColor("#141A22");
    static final int ACCENT = Color.parseColor("#5B8DEF");
    static final int TEXT = Color.parseColor("#E8EEF7");
    static final int MUTED = Color.parseColor("#8B9BB4");
    /** Fond boutons secondaires — aligné interface Pégase ({@code IfaceUi.C_BTN}). */
    static final int BTN_SECONDARY = Color.parseColor("#1C2430");
    private static final float BTN_RADIUS_DP = 10f;
    private static final int BTN_HEIGHT_DP = 40;

    private static final Pattern MD_FENCE = Pattern.compile(
            "```(?:md|markdown)?\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /** Actions hors UI (voix, Orion, dialogues contexte, fermeture). */
    interface Actions {
        void toggleDictate();
        void goToOrion();
        void showOpenPicker();
        void showSaveDialog();
        void speak(String text, Runnable after);
        void finishBureau();
        void openNewProjectInterview();
    }

    private final AppCompatActivity activity;
    private final Handler ui;
    private final Actions actions;

    private DrawerLayout drawerLayout;
    private LinearLayout outlineList;
    private EditText editor;
    private ScrollView editorScroll;
    private WebView preview;
    private FrameLayout editorHost;
    private TextView fileLabel;
    private TextView statusPill;
    private LinearLayout pegasePanel;
    private LinearLayout pegaseBody;
    private TextView pegaseHeaderLabel;
    private LinearLayout threadList;
    private ScrollView threadScroll;
    private EditText pegaseInput;
    private ThinkingView thinkingView;
    private Button previewToggleBtn;
    private Button pegaseBtn;
    private LinearLayout projectActionsBar;

    private String currentFilename;
    private String editingContextKeyword;
    private String structuredSlug;
    private boolean structuredMode;
    private boolean dirty;
    private boolean previewMode;
    private boolean pegaseBusy;
    /** Fil replié par défaut — le document occupe le premier viewport. */
    private boolean pegasePanelExpanded = false;
    private String lastThreadReply;
    private Runnable pendingSave;
    private final Runnable outlineDebounce = this::refreshOutline;

    BureauMarkdownPanel(AppCompatActivity activity, Handler ui, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.actions = actions;
    }

    DrawerLayout build() {
        float d = activity.getResources().getDisplayMetrics().density;
        final int pad = (int) (14 * d);
        final int drawerPadH = (int) (12 * d);
        final int drawerPadTop = (int) (16 * d);
        final int drawerPadBottom = (int) (12 * d);

        drawerLayout = new DrawerLayout(activity);
        drawerLayout.setBackgroundColor(BG);

        final LinearLayout main = new LinearLayout(activity);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(BG);
        main.setPadding(pad, pad, pad, pad);

        main.addView(buildTopBar(d));

        projectActionsBar = buildProjectActionsBar(d);
        projectActionsBar.setVisibility(View.GONE);
        main.addView(projectActionsBar);

        editorHost = buildEditorHost(d);
        main.addView(editorHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        thinkingView = new ThinkingView(activity);
        LinearLayout.LayoutParams thinkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thinkLp.topMargin = (int) (4 * d);
        main.addView(thinkingView, thinkLp);

        pegasePanel = buildPegasePanel(d);
        LinearLayout.LayoutParams pegLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pegLp.topMargin = (int) (6 * d);
        main.addView(pegasePanel, pegLp);
        applyPegasePanelLayout();

        main.addView(buildBottomBar(d));

        drawerLayout.addView(main, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout outlineDrawer = buildOutlineDrawer(d);
        DrawerLayout.LayoutParams drawerLp = new DrawerLayout.LayoutParams(
                (int) (280 * d), ViewGroup.LayoutParams.MATCH_PARENT);
        drawerLp.gravity = Gravity.START;
        drawerLayout.addView(outlineDrawer, drawerLp);

        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            main.setPadding(
                    pad + bars.left,
                    pad + bars.top,
                    pad + bars.right,
                    pad + bottom);
            outlineDrawer.setPadding(
                    drawerPadH + bars.left,
                    drawerPadTop + bars.top,
                    drawerPadH,
                    drawerPadBottom + bars.bottom);
            return insets;
        });

        return drawerLayout;
    }

    ThinkingView getThinkingView() {
        return thinkingView;
    }

    DrawerLayout getDrawerLayout() {
        return drawerLayout;
    }

    boolean isDirty() {
        return dirty;
    }

    boolean isPreviewMode() {
        return previewMode;
    }

    boolean isPegaseBusy() {
        return pegaseBusy;
    }

    String getCurrentFilename() {
        return currentFilename;
    }

    void setCurrentFilename(String filename) {
        this.currentFilename = filename;
        if (BureauProjectStore.isStructuredProjectFile(filename)) {
            structuredSlug = BureauProjectStore.slugFromFilename(filename);
            structuredMode = structuredSlug != null
                    && BureauProjectStore.exists(activity, structuredSlug);
        } else {
            structuredSlug = null;
            structuredMode = false;
        }
    }

    boolean isStructuredMode() {
        return structuredMode;
    }

    String getStructuredSlug() {
        return structuredSlug;
    }

    /** Ouvre un projet JSON + vue .md lecture seule. */
    void openStructuredProject(String slug) {
        if (slug == null || !BureauProjectStore.exists(activity, slug)) {
            Toast.makeText(activity, "Projet introuvable", Toast.LENGTH_SHORT).show();
            return;
        }
        persistNow();
        String md = BureauProjectStore.loadMarkdown(activity, slug);
        if (md == null) md = "";
        setCurrentFilename(BureauProjectStore.mdFilename(slug));
        setEditingContextKeyword(slug);
        setDocumentText(md);
        applyStructuredMode(true);
        updateFileLabel();
        markClean();
        refreshPreview();
        refreshOutline();
        reloadThreadUi();
        Trace.bureauAction("open_file", "project:" + slug);
        setStatus("Projet structuré");
    }

    void applyStructuredMode(boolean structured) {
        structuredMode = structured;
        if (!structured) {
            structuredSlug = null;
        } else if (structuredSlug == null && currentFilename != null) {
            structuredSlug = BureauProjectStore.slugFromFilename(currentFilename);
        }
        if (projectActionsBar != null) {
            projectActionsBar.setVisibility(structured ? View.VISIBLE : View.GONE);
        }
        if (editor != null) {
            editor.setFocusable(!structured);
            editor.setFocusableInTouchMode(!structured);
            editor.setCursorVisible(!structured);
            editor.setLongClickable(!structured);
            editor.setHint(structured
                    ? "Vue générée — utilise les actions ou Pégase"
                    : "Plan Markdown…");
        }
        if (previewToggleBtn != null) {
            previewToggleBtn.setEnabled(!structured);
            previewToggleBtn.setAlpha(structured ? 0.4f : 1f);
        }
        if (structured && !previewMode) {
            togglePreviewMode();
        }
        if (!structured && previewMode) {
            // laisser l'utilisateur décider
        }
    }

    void reloadStructuredView() {
        if (!structuredMode || structuredSlug == null) return;
        String md = BureauProjectStore.loadMarkdown(activity, structuredSlug);
        if (md == null) return;
        if (editor != null) {
            editor.setText(md);
        }
        dirty = false;
        refreshPreview();
        refreshOutline();
    }

    String getEditingContextKeyword() {
        return editingContextKeyword;
    }

    void setEditingContextKeyword(String keyword) {
        this.editingContextKeyword = keyword;
    }

    String getDocumentText() {
        if (editor == null || editor.getText() == null) return "";
        return editor.getText().toString();
    }

    void setDocumentText(String text) {
        if (editor == null) return;
        editor.setText(text != null ? text : "");
    }

    void updateFileLabel() {
        if (fileLabel != null) fileLabel.setText(displayTitle());
    }

    String displayTitle() {
        if (structuredMode && structuredSlug != null) {
            BureauProject p = BureauProjectStore.load(activity, structuredSlug);
            if (p != null && p.title != null && !p.title.isEmpty()) return p.title;
            return structuredSlug;
        }
        if (editingContextKeyword != null) return editingContextKeyword;
        return currentFilename != null ? currentFilename : "Bureau";
    }

    void clearCallbacks() {
        ui.removeCallbacks(outlineDebounce);
        if (pendingSave != null) ui.removeCallbacks(pendingSave);
    }

    private View buildTopBar(float d) {
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, (int) (8 * d));

        Button outlineBtn = secondaryButton("☰", d);
        outlineBtn.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(Gravity.START)) {
                drawerLayout.closeDrawer(Gravity.START);
            } else {
                refreshOutline();
                drawerLayout.openDrawer(Gravity.START);
            }
        });

        fileLabel = new TextView(activity);
        fileLabel.setTextColor(MUTED);
        fileLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fileLabel.setPadding((int) (8 * d), 0, 0, 0);
        fileLabel.setMaxLines(1);

        statusPill = new TextView(activity);
        statusPill.setTextColor(MUTED);
        statusPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusPill.setGravity(Gravity.END);
        statusPill.setPadding(0, 0, (int) (8 * d), 0);

        pegaseBtn = primaryButton("Pégase", d);
        pegaseBtn.setOnClickListener(v -> runPegaseButton());

        previewToggleBtn = secondaryButton("Aperçu", d);
        previewToggleBtn.setOnClickListener(v -> togglePreviewMode());

        Button menuBtn = secondaryButton("⋮", d);
        menuBtn.setOnClickListener(v -> showBureauMenu());

        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(outlineBtn);
        left.addView(row);
        left.addView(fileLabel);

        top.addView(left, grow);
        top.addView(statusPill);
        top.addView(pegaseBtn);
        top.addView(previewToggleBtn);
        top.addView(menuBtn);
        return top;
    }

    private void showBureauMenu() {
        if (structuredMode) {
            showStructuredMenu();
            return;
        }
        String previewLabel = previewMode ? "Mode édition" : "Aperçu Markdown";
        String pegaseLabel = pegasePanelExpanded ? "Replier le fil Pégase" : "Ouvrir le fil Pégase";
        final String[] items = new String[]{
                "Nouveau projet (interview)…",
                previewLabel,
                pegaseLabel,
                "Générer un tableau…",
                "Générer un schéma…",
                "Générer un CSV…",
                "Structurer le plan",
                "Cahier de conception",
                "Note technique (Orion)",
                "Extraire les tâches",
                "Challenger",
                "Vers Orion",
                "Ouvrir…",
                "Enregistrer…",
                "Fermer le Bureau"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Bureau")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: actions.openNewProjectInterview(); break;
                        case 1: togglePreviewMode(); break;
                        case 2: togglePegasePanel(); break;
                        case 3: promptStructuredGenerate("table"); break;
                        case 4: promptStructuredGenerate("schema"); break;
                        case 5: promptStructuredGenerate("csv"); break;
                        case 6:
                            runPlanAction(BureauMarkdownBrain.PlanAction.STRUCTURER,
                                    "J’ai structuré le plan.");
                            break;
                        case 7:
                            runPlanAction(BureauMarkdownBrain.PlanAction.DESIGN_DOC,
                                    "J’ai rédigé le cahier de conception.");
                            break;
                        case 8:
                            runPlanAction(BureauMarkdownBrain.PlanAction.NOTE_TECHNIQUE,
                                    "J’ai mis en note technique pour Orion.");
                            break;
                        case 9:
                            runPlanAction(BureauMarkdownBrain.PlanAction.EXTRACTION_TACHES,
                                    "J’ai extrait les tâches.");
                            break;
                        case 10:
                            runPlanAction(BureauMarkdownBrain.PlanAction.CHALLENGER,
                                    "J’ai challengé le plan.");
                            break;
                        case 11: actions.goToOrion(); break;
                        case 12: actions.showOpenPicker(); break;
                        case 13: actions.showSaveDialog(); break;
                        case 14: actions.finishBureau(); break;
                        default: break;
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showStructuredMenu() {
        String pegaseLabel = pegasePanelExpanded ? "Replier le fil Pégase" : "Ouvrir le fil Pégase";
        final String[] items = new String[]{
                "Nouveau projet (interview)…",
                pegaseLabel,
                "Modifier la vision…",
                "Ajouter une tâche…",
                "Ajouter une décision…",
                "Ajouter une recherche…",
                "Ouvrir les recherches…",
                "Modifier les données structurées…",
                "Vers Orion",
                "Ouvrir…",
                "Fermer le Bureau"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Projet structuré")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: actions.openNewProjectInterview(); break;
                        case 1: togglePegasePanel(); break;
                        case 2: promptSetVision(); break;
                        case 3: promptAppendTask(); break;
                        case 4: promptAppendDecision(); break;
                        case 5: promptAppendResearch(); break;
                        case 6: showResearchList(); break;
                        case 7: showStructuredJsonEditor(); break;
                        case 8: actions.goToOrion(); break;
                        case 9: actions.showOpenPicker(); break;
                        case 10: actions.finishBureau(); break;
                        default: break;
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private LinearLayout buildProjectActionsBar(float d) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, (int) (6 * d));

        HorizontalScrollView hsv = new HorizontalScrollView(activity);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button vision = secondaryButton("Vision", d);
        vision.setOnClickListener(v -> promptSetVision());
        row.addView(vision);

        Button task = secondaryButton("+ Tâche", d);
        task.setOnClickListener(v -> promptAppendTask());
        row.addView(task);

        Button dec = secondaryButton("+ Décision", d);
        dec.setOnClickListener(v -> promptAppendDecision());
        row.addView(dec);

        Button research = secondaryButton("Recherche", d);
        research.setOnClickListener(v -> promptAppendResearch());
        row.addView(research);

        Button openR = secondaryButton("Refs…", d);
        openR.setOnClickListener(v -> showResearchList());
        row.addView(openR);

        hsv.addView(row);
        bar.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void promptSetVision() {
        if (!structuredMode || structuredSlug == null) return;
        BureauProject p = BureauProjectStore.load(activity, structuredSlug);
        EditText field = new EditText(activity);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setHint("Une phrase");
        if (p != null) field.setText(p.vision);
        new AlertDialog.Builder(activity)
                .setTitle("Modifier la vision")
                .setView(field)
                .setPositiveButton("OK", (d, w) -> {
                    String v = field.getText() != null ? field.getText().toString().trim() : "";
                    BureauCommandExecutor.Result r =
                            BureauCommandExecutor.setVision(activity, structuredSlug, v);
                    afterCommand(r);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void promptAppendTask() {
        if (!structuredMode || structuredSlug == null) return;
        EditText field = new EditText(activity);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setHint("Nouvelle tâche");
        new AlertDialog.Builder(activity)
                .setTitle("Ajouter une tâche")
                .setView(field)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String t = field.getText() != null ? field.getText().toString().trim() : "";
                    afterCommand(BureauCommandExecutor.appendTask(activity, structuredSlug, t));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void promptAppendDecision() {
        if (!structuredMode || structuredSlug == null) return;
        EditText field = new EditText(activity);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setHint("Décision confirmée");
        new AlertDialog.Builder(activity)
                .setTitle("Ajouter une décision")
                .setView(field)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String t = field.getText() != null ? field.getText().toString().trim() : "";
                    afterCommand(BureauCommandExecutor.appendDecision(activity, structuredSlug, t,
                            BureauProject.Confidence.CONFIRMED, null));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void promptAppendResearch() {
        if (!structuredMode || structuredSlug == null) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText title = new EditText(activity);
        title.setHint("Titre");
        title.setTextColor(TEXT);
        title.setHintTextColor(MUTED);
        EditText body = new EditText(activity);
        body.setHint("Contenu / notes");
        body.setTextColor(TEXT);
        body.setHintTextColor(MUTED);
        body.setMinLines(4);
        box.addView(title);
        box.addView(body);
        new AlertDialog.Builder(activity)
                .setTitle("Ajouter une recherche")
                .setView(box)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String t = title.getText() != null ? title.getText().toString().trim() : "note";
                    String c = body.getText() != null ? body.getText().toString() : "";
                    afterCommand(BureauCommandExecutor.appendResearch(
                            activity, structuredSlug, t, c));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showResearchList() {
        if (!structuredMode || structuredSlug == null) return;
        BureauProject p = BureauProjectStore.load(activity, structuredSlug);
        if (p == null || p.references.isEmpty()) {
            Toast.makeText(activity, "Aucune référence", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[p.references.size()];
        for (int i = 0; i < p.references.size(); i++) {
            BureauProject.Reference r = p.references.get(i);
            labels[i] = (r.title == null || r.title.isEmpty() ? r.path : r.title);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Recherches")
                .setItems(labels, (d, which) -> {
                    BureauProject.Reference r = p.references.get(which);
                    if (r.path == null || !r.path.startsWith("research/")) return;
                    String name = r.path.substring("research/".length());
                    String content = BureauResearchStore.load(activity, name);
                    new AlertDialog.Builder(activity)
                            .setTitle(labels[which])
                            .setMessage(content == null ? "(vide)" : content)
                            .setPositiveButton("OK", null)
                            .show();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void showStructuredJsonEditor() {
        if (!structuredMode || structuredSlug == null) return;
        BureauProject p = BureauProjectStore.load(activity, structuredSlug);
        if (p == null) return;
        EditText field = new EditText(activity);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTypeface(Typeface.MONOSPACE);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        field.setMinLines(12);
        try {
            field.setText(BureauProjectStore.toJson(p).toString(2));
        } catch (Exception e) {
            Toast.makeText(activity, "JSON illisible", Toast.LENGTH_SHORT).show();
            return;
        }
        ScrollView sv = new ScrollView(activity);
        sv.addView(field);
        new AlertDialog.Builder(activity)
                .setTitle("Données structurées (JSON)")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    try {
                        String raw = field.getText() != null ? field.getText().toString() : "";
                        BureauProject edited = BureauProjectStore.fromJson(new org.json.JSONObject(raw));
                        if (edited.slug == null || edited.slug.isEmpty()) {
                            edited.slug = structuredSlug;
                        }
                        if (!BureauProjectStore.save(activity, edited)) {
                            Toast.makeText(activity, "Échec sauvegarde", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        reloadStructuredView();
                        setStatus("JSON enregistré");
                    } catch (Exception e) {
                        Toast.makeText(activity, "JSON invalide : " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void afterCommand(BureauCommandExecutor.Result r) {
        if (r == null || !r.ok) {
            Toast.makeText(activity, r != null ? r.message : "Erreur", Toast.LENGTH_SHORT).show();
            return;
        }
        reloadStructuredView();
        setStatus(r.message);
        Toast.makeText(activity, r.message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Demande ciblée : tableau Markdown, schéma Mermaid, ou export CSV.
     * @param kind {@code table}, {@code schema} ou {@code csv}
     */
    private void promptStructuredGenerate(String kind) {
        boolean table = "table".equals(kind);
        boolean csv = "csv".equals(kind);
        EditText field = new EditText(activity);
        field.setHint(csv
                ? "Ex. export CSV des tâches avec colonnes état"
                : table
                ? "Ex. tableau des priorités avec colonnes État"
                : "Ex. schéma d'architecture Orion → GitHub");
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setText(csv
                ? "Exporte ce document en CSV"
                : table
                ? "Fais un tableau comparatif clair à partir de ce document"
                : "Fais un schéma d'architecture Mermaid à partir de ce document");
        field.setSelection(field.getText().length());
        field.setMinLines(2);
        field.setMaxLines(4);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        field.setPadding(pad, pad / 2, pad, 0);
        field.setBackgroundColor(SURFACE);
        String title = csv ? "Générer un CSV"
                : table ? "Générer un tableau" : "Générer un schéma";
        String msg = csv
                ? "Pégase créera un .csv (onglet Fichiers) + aperçu dans le plan."
                : table
                ? "Pégase écrira un tableau Markdown (visible en Aperçu)."
                : "Pégase écrira un diagramme Mermaid (flux, séquence, ER…).";
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(msg)
                .setView(field)
                .setPositiveButton("Générer", (d, w) -> {
                    String req = field.getText() != null
                            ? field.getText().toString().trim() : "";
                    if (req.isEmpty()) {
                        req = csv
                                ? "Exporte ce document en CSV"
                                : table
                                ? "Fais un tableau Markdown à partir de ce document"
                                : "Fais un schéma Mermaid à partir de ce document";
                    }
                    if (csv && !BureauMarkdownBrain.wantsCsv(req)) {
                        req = "Export CSV : " + req;
                    }
                    if (table && !BureauMarkdownBrain.wantsMarkdownTable(req)) {
                        req = "Fais un tableau : " + req;
                    }
                    if (!csv && !table && !BureauMarkdownBrain.wantsMermaid(req)) {
                        req = "Fais un schéma Mermaid : " + req;
                    }
                    runPlanRequest(req, null);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private LinearLayout buildOutlineDrawer(float d) {
        LinearLayout drawer = new LinearLayout(activity);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(SURFACE);
        drawer.setPadding((int) (12 * d), (int) (16 * d), (int) (12 * d), (int) (12 * d));

        TextView h = new TextView(activity);
        h.setText("Outline & tâches");
        h.setTextColor(TEXT);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawer.addView(h);

        ScrollView sv = new ScrollView(activity);
        outlineList = new LinearLayout(activity);
        outlineList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(outlineList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        drawer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return drawer;
    }

    void refreshOutline() {
        if (outlineList == null || editor == null) return;
        outlineList.removeAllViews();
        float d = activity.getResources().getDisplayMetrics().density;
        String md = editor.getText() != null ? editor.getText().toString() : "";

        TextView secH = new TextView(activity);
        secH.setText("Sections");
        secH.setTextColor(MUTED);
        secH.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        secH.setPadding(0, (int) (10 * d), 0, (int) (4 * d));
        outlineList.addView(secH);

        for (BureauMarkdownOutline.HeadingItem h : BureauMarkdownOutline.headings(md)) {
            TextView row = new TextView(activity);
            String indent = h.level <= 1 ? "" : (h.level == 2 ? "  " : "    ");
            row.setText(indent + h.title);
            row.setTextColor(TEXT);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), (int) (8 * d));
            final int offset = h.charOffset;
            row.setOnClickListener(v -> {
                focusEditorAt(offset);
                drawerLayout.closeDrawer(Gravity.START);
            });
            outlineList.addView(row);
        }

        TextView taskH = new TextView(activity);
        taskH.setText("Tâches");
        taskH.setTextColor(MUTED);
        taskH.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        taskH.setPadding(0, (int) (14 * d), 0, (int) (4 * d));
        outlineList.addView(taskH);

        List<BureauMarkdownOutline.TaskItem> tasks = BureauMarkdownOutline.tasks(md);
        if (tasks.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("Aucune case à cocher");
            empty.setTextColor(MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            outlineList.addView(empty);
            return;
        }
        if (structuredMode && structuredSlug != null) {
            BureauProject project = BureauProjectStore.load(activity, structuredSlug);
            if (project != null) {
                for (BureauProject.Task t : project.tasks) {
                    CheckBox cb = new CheckBox(activity);
                    cb.setText(t.text.isEmpty() ? "(vide)" : t.text);
                    cb.setChecked(t.done);
                    cb.setTextColor(TEXT);
                    final String taskId = t.id;
                    cb.setOnClickListener(v -> {
                        BureauCommandExecutor.Result r;
                        if (cb.isChecked()) {
                            r = BureauCommandExecutor.completeTask(
                                    activity, structuredSlug, taskId);
                        } else {
                            r = BureauCommandExecutor.uncompleteTask(
                                    activity, structuredSlug, taskId);
                        }
                        afterCommand(r);
                    });
                    outlineList.addView(cb);
                }
                return;
            }
        }
        for (BureauMarkdownOutline.TaskItem t : tasks) {
            CheckBox cb = new CheckBox(activity);
            cb.setText(t.text.isEmpty() ? "(vide)" : t.text);
            cb.setChecked(t.done);
            cb.setTextColor(TEXT);
            final int line = t.lineIndex;
            final int offset = t.charOffset;
            cb.setOnClickListener(v -> {
                String cur = editor.getText().toString();
                editor.setText(BureauMarkdownOutline.toggleTaskAtLine(cur, line));
                dirty = true;
                scheduleAutosave();
                refreshOutline();
                refreshPreview();
                focusEditorAt(offset);
            });
            outlineList.addView(cb);
        }
    }

    void focusEditorAt(int charOffset) {
        if (editor == null) return;
        if (previewMode) togglePreviewMode();
        int len = editor.getText().length();
        int pos = Math.max(0, Math.min(charOffset, len));
        editor.requestFocus();
        editor.setSelection(pos);
        if (editorScroll != null) {
            editorScroll.post(() -> {
                int line = editor.getLayout() != null
                        ? editor.getLayout().getLineForOffset(pos) : 0;
                int y = editor.getLayout() != null
                        ? editor.getLayout().getLineTop(line) : 0;
                editorScroll.smoothScrollTo(0, Math.max(0, y - 40));
            });
        }
    }

    private FrameLayout buildEditorHost(float d) {
        editorHost = new FrameLayout(activity);
        editorHost.setBackgroundColor(SURFACE);

        editor = new EditText(activity);
        editor.setBackgroundColor(SURFACE);
        editor.setTextColor(TEXT);
        editor.setHintTextColor(MUTED);
        editor.setHint("Plan Markdown…");
        editor.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        editor.setLineSpacing(0f, 1.15f);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        editor.setMinLines(8);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (structuredMode) return;
                dirty = true;
                setStatus("Modifié…");
                scheduleAutosave();
                if (previewMode) refreshPreview();
                ui.removeCallbacks(outlineDebounce);
                ui.postDelayed(outlineDebounce, 400);
            }
        });

        editorScroll = new ScrollView(activity);
        editorScroll.setFillViewport(true);
        editorScroll.addView(editor, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = new WebView(activity);
        preview.setBackgroundColor(SURFACE);
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        preview.getSettings().setJavaScriptEnabled(true);
        preview.getSettings().setDomStorageEnabled(true);
        preview.setVisibility(View.GONE);

        editorHost.addView(editorScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editorHost.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return editorHost;
    }

    private LinearLayout buildPegasePanel(float d) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(SURFACE);
        panel.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, (int) (2 * d), 0, (int) (2 * d));

        pegaseHeaderLabel = new TextView(activity);
        pegaseHeaderLabel.setTextColor(MUTED);
        pegaseHeaderLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pegaseHeaderLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(pegaseHeaderLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.setOnClickListener(v -> togglePegasePanel());
        panel.addView(header);

        pegaseBody = new LinearLayout(activity);
        pegaseBody.setOrientation(LinearLayout.VERTICAL);

        threadScroll = new ScrollView(activity);
        threadList = new LinearLayout(activity);
        threadList.setOrientation(LinearLayout.VERTICAL);
        threadScroll.addView(threadList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pegaseBody.addView(threadScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        pegaseInput = new EditText(activity);
        pegaseInput.setHint("Demande à Pégase…");
        pegaseInput.setHintTextColor(MUTED);
        pegaseInput.setTextColor(TEXT);
        pegaseInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(BTN_SECONDARY);
        inputBg.setCornerRadius(BTN_RADIUS_DP * d);
        pegaseInput.setBackground(inputBg);
        pegaseInput.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        inputRow.addView(pegaseInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = primaryButton("Envoyer", d);
        send.setOnClickListener(v -> sendThreadMessage());
        inputRow.addView(send);

        Button insert = secondaryButton("Insérer", d);
        insert.setOnClickListener(v -> insertLastReplyIntoDoc());
        inputRow.addView(insert);

        pegaseBody.addView(inputRow);
        panel.addView(pegaseBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private void togglePegasePanel() {
        pegasePanelExpanded = !pegasePanelExpanded;
        applyPegasePanelLayout();
    }

    /** Ouvre le fil si besoin (ex. après envoi). */
    void ensurePegasePanelExpanded() {
        if (!pegasePanelExpanded) {
            pegasePanelExpanded = true;
            applyPegasePanelLayout();
        }
    }

    private void applyPegasePanelLayout() {
        if (pegasePanel == null) return;
        if (pegaseHeaderLabel != null) {
            pegaseHeaderLabel.setText(pegasePanelExpanded
                    ? "Fil Pégase  ▾  (replier)"
                    : "Fil Pégase  ▸  (discuter)");
        }
        if (pegaseBody != null) {
            pegaseBody.setVisibility(pegasePanelExpanded ? View.VISIBLE : View.GONE);
        }
        ViewGroup.LayoutParams lp = pegasePanel.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            if (pegasePanelExpanded) {
                // Moins agressif qu'avant (0.65) — le doc reste prioritaire
                llp.weight = 0.42f;
                llp.height = 0;
            } else {
                llp.weight = 0f;
                llp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            pegasePanel.setLayoutParams(llp);
        }
        pegasePanel.setVisibility(View.VISIBLE);
    }

    /** Bouton chrome : co-édite le document (question → réponse, sinon structure). */
    void runPegaseButton() {
        if (structuredMode) {
            ensurePegasePanelExpanded();
            if (pegaseInput != null) pegaseInput.requestFocus();
            Toast.makeText(activity,
                    "Décris la modification dans le fil — Pégase appliquera des commandes.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String req = BureauMarkdownBrain.resolvePegaseButtonRequest(getDocumentText());
        runPlanRequest(req, null);
    }

    void reloadThreadUi() {
        if (threadList == null) return;
        threadList.removeAllViews();
        float d = activity.getResources().getDisplayMetrics().density;
        List<BureauChatStore.Turn> turns = BureauChatStore.load(activity, currentFilename);
        if (turns.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("Fil vide — pose une question sur ce plan.");
            empty.setTextColor(MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            threadList.addView(empty);
            return;
        }
        for (BureauChatStore.Turn t : turns) {
            appendThreadBubble(t.fromUser, t.text, false);
        }
        threadScroll.post(() -> threadScroll.fullScroll(View.FOCUS_DOWN));
    }

    void appendThreadBubble(boolean fromUser, String text, boolean persist) {
        if (threadList == null) return;
        float d = activity.getResources().getDisplayMetrics().density;
        // Clear empty state
        if (threadList.getChildCount() == 1) {
            View first = threadList.getChildAt(0);
            if (first instanceof TextView) {
                CharSequence c = ((TextView) first).getText();
                if (c != null && c.toString().startsWith("Fil vide")) {
                    threadList.removeAllViews();
                }
            }
        }
        TextView bubble = new TextView(activity);
        bubble.setText((fromUser ? "Toi : " : "Pégase : ") + (text == null ? "" : text));
        bubble.setTextColor(TEXT);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bubble.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        bubble.setBackgroundColor(fromUser
                ? Color.parseColor("#1A2A40")
                : Color.parseColor("#1C2430"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (4 * d);
        threadList.addView(bubble, lp);
        if (persist) {
            BureauChatStore.append(activity, currentFilename, fromUser, text);
        }
        if (threadScroll != null) {
            threadScroll.post(() -> threadScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void sendThreadMessage() {
        if (pegaseBusy) {
            Toast.makeText(activity, "Pégase écrit déjà…", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = pegaseInput != null && pegaseInput.getText() != null
                ? pegaseInput.getText().toString().trim() : "";
        if (msg.isEmpty()) {
            ensurePegasePanelExpanded();
            if (pegaseInput != null) pegaseInput.requestFocus();
            return;
        }
        ensurePegasePanelExpanded();
        pegaseInput.setText("");
        appendThreadBubble(true, msg, true);
        pegaseBusy = true;
        setStatus("Pégase…");
        if (thinkingView != null) thinkingView.reset();
        String doc = editor.getText().toString();
        List<BureauChatStore.Turn> turns = BureauChatStore.load(activity, currentFilename);
        PegaseSession.get(activity).completeBureauThread(doc, msg, turns, new ChatBackend.OnReply() {
            @Override
            public void onLlmReply(LlmReply reply) {
                ui.post(() -> onThreadReply(reply != null ? reply.content : ""));
            }

            @Override
            public void onReply(String text) {
                ui.post(() -> onThreadReply(text));
            }

            @Override
            public void onError(String error) {
                ui.post(() -> {
                    pegaseBusy = false;
                    if (thinkingView != null) thinkingView.onError();
                    setStatus("Erreur");
                    String err = error != null ? error : "Erreur";
                    appendThreadBubble(false, err, true);
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onThreadReply(String text) {
        pegaseBusy = false;
        if (thinkingView != null) thinkingView.onComplete();
        String reply = text == null ? "" : text.trim();
        lastThreadReply = reply;
        appendThreadBubble(false, reply.isEmpty() ? "(pas de réponse)" : reply, true);
        setStatus("Pégase a répondu");
    }

    private void insertLastReplyIntoDoc() {
        if (lastThreadReply == null || lastThreadReply.trim().isEmpty()) {
            Toast.makeText(activity, "Rien à insérer — envoie d’abord un message.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String snippet = extractMdSnippet(lastThreadReply);
        if (snippet.isEmpty()) snippet = lastThreadReply.trim();
        if (structuredMode && structuredSlug != null) {
            String title = "Note fil " + BureauMarkdownBuilder.formatDate(System.currentTimeMillis());
            afterCommand(BureauCommandExecutor.appendResearch(
                    activity, structuredSlug, title, snippet));
            return;
        }
        String cur = editor.getText().toString();
        String next = BureauMarkdownOutline.insertUnderSection(
                cur, BureauPlanTemplate.SECTION_NOTES, snippet);
        editor.setText(next);
        dirty = true;
        scheduleAutosave();
        refreshOutline();
        refreshPreview();
        setStatus("Inséré dans Notes");
        Toast.makeText(activity, "Inséré sous Notes / recherche", Toast.LENGTH_SHORT).show();
    }

    static String extractMdSnippet(String reply) {
        if (reply == null) return "";
        Matcher m = MD_FENCE.matcher(reply);
        if (m.find()) return m.group(1).trim();
        return "";
    }

    private View buildBottomBar(float d) {
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, (int) (8 * d), 0, 0);

        // Même hiérarchie que la barre du haut : Pégase = CTA, le reste = secondaire.
        Button dictate = secondaryButton("Dicter", d);
        dictate.setOnClickListener(v -> actions.toggleDictate());

        Button pegaseBottom = primaryButton("Pégase", d);
        pegaseBottom.setOnClickListener(v -> runPegaseButton());

        Button append = secondaryButton("⏎", d);
        append.setOnClickListener(v -> appendText("\n"));

        int gap = (int) (6 * d);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        grow.setMargins(0, 0, gap, 0);
        dictate.setLayoutParams(grow);
        bottom.addView(dictate);

        LinearLayout.LayoutParams pegaseLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pegaseLp.setMargins(0, 0, gap, 0);
        pegaseBottom.setLayoutParams(pegaseLp);
        bottom.addView(pegaseBottom);

        LinearLayout.LayoutParams appendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        append.setLayoutParams(appendLp);
        bottom.addView(append);
        return bottom;
    }

    void togglePreviewMode() {
        if (structuredMode && previewMode) {
            Toast.makeText(activity, "Vue lecture seule — utilise les actions pour modifier.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        previewMode = !previewMode;
        if (previewMode) {
            refreshPreview();
            editorScroll.setVisibility(View.GONE);
            preview.setVisibility(View.VISIBLE);
            setStatus("Aperçu");
        } else {
            preview.setVisibility(View.GONE);
            editorScroll.setVisibility(View.VISIBLE);
            setStatus(dirty ? "Modifié…" : "Prêt");
        }
        if (previewToggleBtn != null) {
            previewToggleBtn.setText(previewMode ? "Éditer" : "Aperçu");
        }
    }

    void refreshPreview() {
        if (preview == null || editor == null) return;
        preview.setBackgroundColor(SURFACE);
        preview.loadDataWithBaseURL(
                "https://cdn.jsdelivr.net/",
                BureauMarkdownHtml.toHtml(editor.getText().toString()),
                "text/html",
                "UTF-8",
                null);
    }

    /** Bouton secondaire — même forme / taille que le CTA, fond neutre. */
    private Button secondaryButton(String label, float d) {
        return styledButton(label, d, BTN_SECONDARY, TEXT);
    }

    /** CTA Pégase / Envoyer — même coque que le secondaire, fond accent. */
    private Button primaryButton(String label, float d) {
        return styledButton(label, d, ACCENT, Color.WHITE);
    }

    private Button styledButton(String label, float d, int bgColor, int textColor) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight((int) (BTN_HEIGHT_DP * d));
        b.setMinimumHeight((int) (BTN_HEIGHT_DP * d));
        b.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
        b.setStateListAnimator(null);
        b.setElevation(0f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(BTN_RADIUS_DP * d);
        b.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (BTN_HEIGHT_DP * d));
        lp.setMargins(0, 0, (int) (6 * d), 0);
        b.setLayoutParams(lp);
        return b;
    }

    void scheduleAutosave() {
        if (pendingSave != null) ui.removeCallbacks(pendingSave);
        pendingSave = () -> {
            persistNow();
            setStatus("Enregistré");
        };
        ui.postDelayed(pendingSave, 2000);
    }

    void persistNow() {
        if (editor == null) return;
        if (structuredMode) {
            dirty = false;
            return;
        }
        BureauSessionStore.saveAsync(activity, currentFilename, editor.getText().toString());
        dirty = false;
    }

    void setStatus(String s) {
        if (statusPill != null) statusPill.setText(s);
    }

    void runPlanAction(BureauMarkdownBrain.PlanAction action, String threadNote) {
        if (structuredMode) {
            Toast.makeText(activity, "Projet structuré — utilise les actions ou le fil Pégase.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        runPlanRequest(BureauMarkdownBrain.requestForAction(action), threadNote,
                BureauMarkdownBrain.maxTokensForAction(action));
    }

    void runPlanRequest(String userRequest, String threadNoteOnSuccess) {
        runPlanRequest(userRequest, threadNoteOnSuccess, null);
    }

    void runPlanRequest(String userRequest, String threadNoteOnSuccess, Integer maxTokens) {
        if (pegaseBusy) {
            Toast.makeText(activity, "Pégase écrit déjà…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (structuredMode && structuredSlug != null) {
            runStructuredCommands(userRequest);
            return;
        }
        pegaseBusy = true;
        setStatus("Pégase écrit…");
        if (thinkingView != null) thinkingView.reset();
        String doc = editor.getText().toString();
        final boolean questionMode = BureauMarkdownBrain.isQuestion(userRequest);
        final String request = userRequest;
        PegaseSession.get(activity).editBureauMarkdown(doc, userRequest,
                new BureauMarkdownBrain.Callback() {
            @Override
            public void onResult(BureauMarkdownBrain.Result result) {
                pegaseBusy = false;
                if (thinkingView != null) thinkingView.onComplete();
                applyPegaseResult(result.parsed, request, questionMode);
                if (threadNoteOnSuccess != null) {
                    appendThreadBubble(false, threadNoteOnSuccess, true);
                    String hist = BureauMarkdownOutline.appendHistorique(
                            editor.getText().toString(), threadNoteOnSuccess);
                    editor.setText(hist);
                    dirty = true;
                    scheduleAutosave();
                }
            }

            @Override
            public void onError(String message) {
                pegaseBusy = false;
                if (thinkingView != null) thinkingView.onError();
                setStatus("Erreur");
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                actions.speak("Je n'ai pas pu modifier le document.", null);
            }
        }, maxTokens);
    }

    private void runStructuredCommands(String userRequest) {
        BureauProject project = BureauProjectStore.load(activity, structuredSlug);
        if (project == null) {
            Toast.makeText(activity, "Projet introuvable", Toast.LENGTH_SHORT).show();
            return;
        }
        pegaseBusy = true;
        setStatus("Pégase…");
        if (thinkingView != null) thinkingView.reset();
        PegaseSession.get(activity).completeBureauProjectCommands(project, userRequest,
                new ChatBackend.OnReply() {
                    @Override
                    public void onLlmReply(LlmReply reply) {
                        ui.post(() -> onCommandsReply(reply != null ? reply.content : ""));
                    }

                    @Override
                    public void onReply(String text) {
                        ui.post(() -> onCommandsReply(text));
                    }

                    @Override
                    public void onError(String error) {
                        ui.post(() -> {
                            pegaseBusy = false;
                            if (thinkingView != null) thinkingView.onError();
                            setStatus("Erreur");
                            Toast.makeText(activity,
                                    error != null ? error : "Erreur", Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void onCommandsReply(String raw) {
        pegaseBusy = false;
        if (thinkingView != null) thinkingView.onComplete();
        BureauPlanningBrain.CommandsReply parsed = BureauPlanningBrain.parseCommandsReply(raw);
        BureauCommandExecutor.Result r = BureauCommandExecutor.applyCommandsJson(
                activity, structuredSlug, parsed.commandsJson);
        if (r.ok) {
            reloadStructuredView();
            setStatus(r.message);
            if (!parsed.speak.isEmpty()) {
                actions.speak(parsed.speak, null);
                appendThreadBubble(false, parsed.speak, true);
            } else {
                Toast.makeText(activity, r.message, Toast.LENGTH_SHORT).show();
            }
        } else {
            setStatus("Erreur commandes");
            Toast.makeText(activity, r.message, Toast.LENGTH_LONG).show();
            if (!parsed.speak.isEmpty()) {
                appendThreadBubble(false, parsed.speak, true);
            }
        }
    }

    void applyPegaseResult(BureauMarkdownParser.Parsed parsed, String userRequest,
            boolean questionMode) {
        if (parsed == null) return;
        if (questionMode) {
            String answer = parsed.markdown;
            if (answer == null || answer.trim().isEmpty()) {
                answer = BureauMarkdownBrain.QUESTION_ANSWER_PREFIX
                        + (parsed.speak != null ? parsed.speak : "");
            }
            String merged = BureauMarkdownBrain.insertUnderQuestion(
                    editor.getText().toString(), userRequest, answer.trim());
            editor.setText(merged);
        } else if (parsed.replaceAll && !parsed.markdown.isEmpty()) {
            editor.setText(parsed.markdown);
        } else if (!parsed.markdown.isEmpty()) {
            String addition = parsed.markdown.trim();
            if (BureauMarkdownBrain.wantsCsv(userRequest)) {
                addition = materializeCsvExport(userRequest, addition);
            }
            if (!addition.isEmpty()) {
                String section = guessSectionForAddition(userRequest, addition);
                String next = BureauMarkdownOutline.insertUnderSection(
                        editor.getText().toString(), section, addition);
                editor.setText(next);
            }
        }
        dirty = true;
        scheduleAutosave();
        refreshPreview();
        refreshOutline();
        setStatus(questionMode ? "Pégase a répondu" : "Pégase a écrit");
        if (parsed.speak != null && !parsed.speak.isEmpty()) {
            actions.speak(parsed.speak, null);
        }
    }

    /** Sauve le .csv dans Fichiers et remplace l'addition par un aperçu Markdown. */
    private String materializeCsvExport(String userRequest, String addition) {
        String csv = BureauMarkdownBrain.extractCsvContent(addition);
        if (csv.isEmpty()) {
            csv = BureauMarkdownBrain.extractCsvContent(
                    addition + "\n" + editor.getText().toString());
        }
        if (csv.isEmpty()) return addition;
        String filename = BureauMarkdownBrain.suggestedCsvFilename(userRequest);
        try {
            File saved = GeneratedFiles.save(activity, filename, csv);
            Toast.makeText(activity, "CSV sauvé : " + saved.getName(),
                    Toast.LENGTH_SHORT).show();
            return BureauMarkdownBrain.csvExportMarkdownNote(saved.getName(), csv);
        } catch (Exception e) {
            Toast.makeText(activity, "CSV non sauvé : " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return addition;
        }
    }

    static String guessSectionForAddition(String request, String addition) {
        String f = ((request == null ? "" : request) + " " + (addition == null ? "" : addition))
                .toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e');
        // Tableaux / schémas / CSV → Notes (aperçu), pas Tâches
        if ((addition != null && (addition.contains("```mermaid") || addition.contains("|---")
                || addition.contains("```csv") || addition.contains("Export CSV")))
                || f.contains("tableau") || f.contains("schema") || f.contains("diagramme")
                || f.contains("mermaid") || f.contains("graphique") || f.contains("csv")) {
            return BureauPlanTemplate.SECTION_NOTES;
        }
        if (f.contains("tache") || f.contains("todo") || (addition != null && addition.contains("- ["))) {
            return BureauPlanTemplate.SECTION_TACHES;
        }
        if (f.contains("decision") || f.contains("risque") || f.contains("challenge")) {
            return BureauPlanTemplate.SECTION_DECISIONS;
        }
        if (f.contains("objectif")) {
            return BureauPlanTemplate.SECTION_OBJECTIFS;
        }
        return BureauPlanTemplate.SECTION_NOTES;
    }

    void appendText(String text) {
        if (structuredMode) {
            Toast.makeText(activity, "Projet structuré — utilise les actions.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (editor == null) return;
        int start = editor.getSelectionStart();
        Editable ed = editor.getText();
        if (start < 0 || start > ed.length()) {
            editor.append(text);
        } else {
            ed.insert(start, text);
            editor.setSelection(start + text.length());
        }
        refreshPreview();
        refreshOutline();
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Marque le document modifié et planifie l’autosave (hors TextWatcher). */
    void markDirtyAndAutosave() {
        dirty = true;
        scheduleAutosave();
    }

    void markClean() {
        dirty = false;
    }
}
