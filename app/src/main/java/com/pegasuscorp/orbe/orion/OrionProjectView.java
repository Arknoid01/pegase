package com.pegasuscorp.orbe.orion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.pegasuscorp.orbe.OrionSettingsActivity;
import com.pegasuscorp.orbe.session.ChatConfirmBridge;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.orion.GitCommitTool;
import com.pegasuscorp.orbe.tools.orion.OrionProjectTool;
import com.pegasuscorp.orbe.ui.PegaseSheets;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Panneau projet Orion + revue de session (conflits Remplacer/Nouveau/Ignorer).
 * Observe {@link OrionProjectStore} et {@link OrionFileStore} indépendamment.
 */
public final class OrionProjectView {

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View root;
    private LinearLayout projectPanel;
    private TextView projectTitle;
    private TextView projectChevron;
    private LinearLayout projectBody;
    private LinearLayout projectFilesCol;
    private TextView projectHistory;
    private Button pushSelectedBtn;
    private final Set<String> selectedProjectFiles = new HashSet<>();
    private boolean projectExpanded;

    private LinearLayout sessionPanel;
    private TextView sessionTitle;
    private TextView sessionChevron;
    private LinearLayout sessionBody;
    private LinearLayout sessionFilesCol;
    private LinearLayout sessionActions;
    private TextView sessionHistory;
    private boolean sessionExpanded = true;

    private boolean observing;
    private final OrionFileStore.Observer sessionObserver = () -> main.post(this::refreshSessionPanel);
    private final OrionProjectStore.Observer projectObserver = () -> main.post(this::refreshProjectPanel);

    public OrionProjectView(Activity activity) {
        this.activity = activity;
    }

    public View build() {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        projectPanel = buildProjectPanel();
        col.addView(projectPanel, OrionUi.matchWrap());
        refreshProjectPanel();

        sessionPanel = buildSessionPanel();
        col.addView(sessionPanel, OrionUi.matchWrap());
        refreshSessionPanel();

        root = col;
        return col;
    }

    public View getRoot() {
        return root;
    }

    public void onAttach() {
        if (observing) return;
        observing = true;
        OrionFileStore.get().addObserver(sessionObserver);
        OrionProjectStore.get(activity).addObserver(projectObserver);
        refresh();
    }

    public void onDetach() {
        if (!observing) return;
        observing = false;
        OrionFileStore.get().removeObserver(sessionObserver);
        OrionProjectStore.get(activity).removeObserver(projectObserver);
    }

    public void refresh() {
        refreshProjectPanel();
        refreshSessionPanel();
    }

    /**
     * Enregistre les fichiers de session dans le projet actif.
     * Conflit → Remplacer / Nouveau / Ignorer (jamais d'écrasement silencieux).
     */
    public void offerSaveToProject(List<OrionFileSession.OrionFile> sessionFiles) {
        OrionProjectStore store = OrionProjectStore.get(activity);
        if (!store.hasActiveProject()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Sauver dans un projet ?")
                    .setMessage("Les fichiers restent en session. "
                            + "Un projet local permet de pusher plus tard sans perte.")
                    .setPositiveButton("Choisir projet", (d, w) -> showProjectPicker())
                    .setNeutralButton("Nouveau", (d, w) -> showNewProjectDialog())
                    .setNegativeButton("Plus tard", null)
                    .show();
            return;
        }
        saveSessionFilesToProject(sessionFiles, 0);
    }

    private LinearLayout buildProjectPanel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(OrionUi.dp(activity, 8), OrionUi.dp(activity, 6),
                OrionUi.dp(activity, 8), OrionUi.dp(activity, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1A1A"));
        bg.setCornerRadius(OrionUi.dp(activity, 8));
        bg.setStroke(OrionUi.dp(activity, 1), Color.parseColor("#2A2A2A"));
        panel.setBackground(bg);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, OrionUi.dp(activity, 2), 0, OrionUi.dp(activity, 2));

        projectChevron = new TextView(activity);
        projectChevron.setText("▸");
        projectChevron.setTextColor(Color.parseColor(OrionUi.CYAN));
        projectChevron.setTextSize(14);
        projectChevron.setPadding(0, 0, OrionUi.dp(activity, 6), 0);
        header.addView(projectChevron);

        projectTitle = new TextView(activity);
        projectTitle.setTextColor(Color.parseColor(OrionUi.CYAN));
        projectTitle.setTextSize(13);
        projectTitle.setTypeface(null, Typeface.BOLD);
        projectTitle.setText("📁 Projet");
        header.addView(projectTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button projectMenu = OrionUi.outlineBtn(activity, "⋮");
        projectMenu.setTextSize(14);
        projectMenu.setMinWidth(OrionUi.dp(activity, 40));
        projectMenu.setOnClickListener(v -> {
            PegaseSheets.haptic(v);
            showProjectMenu();
        });
        header.addView(projectMenu);

        View.OnClickListener toggleProject = v -> {
            PegaseSheets.haptic(v);
            setProjectExpanded(!projectExpanded);
        };
        projectChevron.setOnClickListener(toggleProject);
        projectTitle.setOnClickListener(toggleProject);
        header.setOnClickListener(toggleProject);
        panel.addView(header, OrionUi.matchWrap());

        projectBody = new LinearLayout(activity);
        projectBody.setOrientation(LinearLayout.VERTICAL);
        projectBody.setVisibility(View.GONE);

        ScrollView filesScroll = new ScrollView(activity);
        filesScroll.setFillViewport(false);
        filesScroll.setVerticalScrollBarEnabled(true);
        projectFilesCol = new LinearLayout(activity);
        projectFilesCol.setOrientation(LinearLayout.VERTICAL);
        projectFilesCol.setPadding(0, OrionUi.dp(activity, 4), 0, OrionUi.dp(activity, 4));
        filesScroll.addView(projectFilesCol, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, OrionUi.dp(activity, 160));
        projectBody.addView(filesScroll, scrollLp);

        pushSelectedBtn = OrionUi.cyanBtn(activity, "Push sélection");
        pushSelectedBtn.setVisibility(View.GONE);
        projectBody.addView(pushSelectedBtn);

        projectHistory = new TextView(activity);
        projectHistory.setTextColor(Color.parseColor("#8A8A8A"));
        projectHistory.setTextSize(11);
        projectHistory.setPadding(0, OrionUi.dp(activity, 4), 0, 0);
        projectBody.addView(projectHistory, OrionUi.matchWrap());

        panel.addView(projectBody, OrionUi.matchWrap());
        return panel;
    }

    private void setProjectExpanded(boolean expanded) {
        projectExpanded = expanded;
        if (projectBody != null) {
            projectBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (projectChevron != null) {
            projectChevron.setText(expanded ? "▾" : "▸");
        }
        updatePushSelectedButton();
    }

    private void setSessionExpanded(boolean expanded) {
        sessionExpanded = expanded;
        if (sessionBody != null) {
            sessionBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (sessionChevron != null) {
            sessionChevron.setText(expanded ? "▾" : "▸");
        }
        if (sessionTitle != null && OrionFileStore.get().hasSession()) {
            OrionFileSession session = OrionFileStore.get().getCurrentSession();
            if (session != null && !session.isEmpty()) {
                String active = OrionProjectStore.get(activity).getActiveProject();
                String dest = TextUtils.isEmpty(active) ? "choisir un projet" : "→ " + active;
                sessionTitle.setText("📥 À revoir (" + session.size() + ") " + dest
                        + (sessionExpanded ? "" : " · fichiers repliés"));
            }
        }
    }

    private void showProjectMenu() {
        OrionProjectStore store = OrionProjectStore.get(activity);
        String active = store.getActiveProject();
        int sel = selectedProjectFiles.size();
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("Changer de projet");
        items.add("Nouveau projet");
        if (!TextUtils.isEmpty(active)) {
            items.add("Push projet → GitHub");
            items.add(sel > 0
                    ? "Push sélection (" + sel + ")"
                    : "Push sélection (coche des fichiers)");
            items.add("Rendre public sur GitHub");
            items.add("Actualiser");
            items.add("Supprimer le projet");
        }
        items.add("Paramètres Orion");
        String[] labels = items.toArray(new String[0]);
        PegaseSheets.show(activity,
                TextUtils.isEmpty(active) ? "Projet Orion" : "📁 " + active,
                labels, which -> {
                    String pick = labels[which];
                    if (pick.startsWith("Changer")) showProjectPicker();
                    else if (pick.startsWith("Nouveau")) showNewProjectDialog();
                    else if (pick.startsWith("Push projet")) pushActiveProject(false);
                    else if (pick.startsWith("Push sélection")) {
                        if (sel == 0) {
                            Toast.makeText(activity, "Coche au moins un fichier",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            pushActiveProject(true);
                        }
                    } else if (pick.startsWith("Rendre public")) makeActiveProjectPublic();
                    else if (pick.startsWith("Actualiser")) refreshProjectPanel();
                    else if (pick.startsWith("Supprimer")) confirmDeleteActiveProject();
                    else if (pick.startsWith("Paramètres")) {
                        activity.startActivity(new Intent(activity, OrionSettingsActivity.class));
                    }
                });
    }

    private void confirmDeleteActiveProject() {
        OrionProjectStore store = OrionProjectStore.get(activity);
        String name = store.getActiveProject();
        if (TextUtils.isEmpty(name)) return;
        new AlertDialog.Builder(activity)
                .setTitle("Supprimer « " + name + " » ?")
                .setMessage("Efface le dossier local files/orion/projects/" + name
                        + ". Pas de push auto — GitHub reste intact.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    if (store.deleteProject(name)) {
                        selectedProjectFiles.clear();
                        Toast.makeText(activity, "Projet « " + name + " » supprimé",
                                Toast.LENGTH_SHORT).show();
                        refreshProjectPanel();
                    } else {
                        Toast.makeText(activity, "Suppression impossible",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void refreshProjectPanel() {
        if (projectPanel == null || projectTitle == null || projectFilesCol == null) return;
        OrionProjectStore store = OrionProjectStore.get(activity);
        String active = store.getActiveProject();
        if (TextUtils.isEmpty(active)) {
            projectTitle.setText("📁 Projet — aucun");
            projectFilesCol.removeAllViews();
            selectedProjectFiles.clear();
            if (projectHistory != null) projectHistory.setText("");
            TextView hint = new TextView(activity);
            hint.setText("⋮ → Nouveau projet — local = source de vérité.");
            hint.setTextColor(Color.parseColor("#8A8A8A"));
            hint.setTextSize(11);
            projectFilesCol.addView(hint, OrionUi.matchWrap());
            updatePushSelectedButton();
            return;
        }
        List<OrionProjectStore.ProjectFile> files = store.getProjectFiles();
        int sel = selectedProjectFiles.size();
        projectTitle.setText("📁 " + active + " (" + files.size() + ")"
                + (sel > 0 ? " · " + sel + " sel." : "")
                + (projectExpanded ? "" : " · tap pour ouvrir"));
        Set<String> alive = new HashSet<>();
        for (OrionProjectStore.ProjectFile pf : files) alive.add(pf.name);
        selectedProjectFiles.retainAll(alive);

        projectFilesCol.removeAllViews();
        if (files.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("Dossier vide — Orion y déposera les fichiers.");
            empty.setTextColor(Color.parseColor("#8A8A8A"));
            empty.setTextSize(11);
            projectFilesCol.addView(empty, OrionUi.matchWrap());
        } else {
            for (OrionProjectStore.ProjectFile pf : files) {
                projectFilesCol.addView(makeProjectFileRow(pf), OrionUi.matchWrap());
            }
        }
        updatePushSelectedButton();
        loadProjectCommitHistory();
    }

    private void updatePushSelectedButton() {
        if (projectTitle == null) return;
        OrionProjectStore store = OrionProjectStore.get(activity);
        String active = store.getActiveProject();
        if (TextUtils.isEmpty(active)) return;
        int nFiles = store.getProjectFiles().size();
        int sel = selectedProjectFiles.size();
        projectTitle.setText("📁 " + active + " (" + nFiles + ")"
                + (sel > 0 ? " · " + sel + " sel." : "")
                + (projectExpanded ? "" : " · tap pour ouvrir"));
        if (pushSelectedBtn != null) {
            pushSelectedBtn.setText(sel > 0 ? "Push (" + sel + ")" : "Push sélection");
            pushSelectedBtn.setEnabled(sel > 0);
            // Visible seulement si le panneau est ouvert et qu'il y a une sélection
            pushSelectedBtn.setVisibility(
                    projectExpanded && sel > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private View makeProjectFileRow(OrionProjectStore.ProjectFile pf) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, OrionUi.dp(activity, 2), 0, OrionUi.dp(activity, 2));

        CheckBox check = new CheckBox(activity);
        check.setChecked(selectedProjectFiles.contains(pf.name));
        check.setOnCheckedChangeListener((b, checked) -> {
            if (checked) selectedProjectFiles.add(pf.name);
            else selectedProjectFiles.remove(pf.name);
            updatePushSelectedButton();
        });
        row.addView(check);

        OrionProjectStore store = OrionProjectStore.get(activity);
        boolean dirty = store.hasChangedSincePush(pf.name);
        TextView label = new TextView(activity);
        label.setText((dirty ? "● " : "📄 ") + pf.name + "  ·  " + pf.lineCount() + " l.");
        label.setTextColor(dirty ? Color.parseColor("#FFB74D") : Color.WHITE);
        label.setTextSize(12);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setOnClickListener(v -> check.setChecked(!check.isChecked()));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button eye = OrionUi.outlineBtn(activity, "👁");
        eye.setTextSize(9);
        eye.setOnClickListener(v -> {
            String content = store.readFile(pf.name);
            showTextPreview(pf.name, content);
        });
        row.addView(eye);

        Button edit = OrionUi.outlineBtn(activity, "✏️");
        edit.setTextSize(9);
        edit.setOnClickListener(v -> showFileEditor(pf.name));
        row.addView(edit);

        Button diff = OrionUi.outlineBtn(activity, "Δ");
        diff.setTextSize(9);
        diff.setOnClickListener(v -> showFileDiff(pf.name));
        row.addView(diff);

        Button rename = OrionUi.outlineBtn(activity, "Aa");
        rename.setTextSize(9);
        rename.setOnClickListener(v -> showRenameDialog(pf.name));
        row.addView(rename);

        Button del = OrionUi.outlineBtn(activity, "🗑");
        del.setTextSize(9);
        del.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle("Supprimer ?")
                .setMessage(pf.name + " du projet « "
                        + store.getActiveProject() + " »")
                .setPositiveButton("Supprimer", (d, w) -> {
                    store.deleteFile(pf.name);
                    selectedProjectFiles.remove(pf.name);
                    refreshProjectPanel();
                })
                .setNegativeButton("Annuler", null)
                .show());
        row.addView(del);

        return row;
    }

    private void showTextPreview(String title, String content) {
        if (OrionPagePreview.isPage(title, content)) {
            OrionPagePreview.openFullscreen(activity, title, content, projectSiblingMap());
            return;
        }
        OrionUi.darkDialog(activity)
                .setTitle(title)
                .setView(OrionUi.darkCodeScroll(activity, title, content))
                .setPositiveButton("✏️ Éditer", (d, w) -> showFileEditor(title))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void showCodeOnlyPreview(String title, String content, Runnable onEdit) {
        OrionUi.darkDialog(activity)
                .setTitle("Code · " + title)
                .setView(OrionUi.darkCodeScroll(activity, title, content))
                .setPositiveButton("✏️ Éditer", (d, w) -> {
                    if (onEdit != null) onEdit.run();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private Map<String, String> projectSiblingMap() {
        Map<String, String> out = new HashMap<>();
        OrionProjectStore store = OrionProjectStore.get(activity);
        for (OrionProjectStore.ProjectFile pf : store.getProjectFiles()) {
            if (pf == null || pf.name == null) continue;
            String body = store.readFile(pf.name);
            if (body != null) out.put(pf.name, body);
        }
        return out;
    }

    private void showFileEditor(String filename) {
        OrionProjectStore store = OrionProjectStore.get(activity);
        String content = store.readFile(filename);
        EditText field = new EditText(activity);
        field.setText(content);
        field.setTextColor(Color.WHITE);
        field.setBackgroundColor(Color.parseColor(OrionUi.BG));
        field.setTextSize(12);
        field.setTypeface(Typeface.MONOSPACE);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(12);
        field.setMaxLines(24);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPadding(OrionUi.dp(activity, 10), OrionUi.dp(activity, 8),
                OrionUi.dp(activity, 10), OrionUi.dp(activity, 8));
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.parseColor(OrionUi.BG));
        scroll.addView(field, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        OrionUi.darkDialog(activity)
                .setTitle("✏️ " + filename)
                .setView(scroll)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String next = field.getText() != null ? field.getText().toString() : "";
                    OrionProjectStore.SaveResult r = store.replaceFile(filename, next);
                    Toast.makeText(activity,
                            r.message.isEmpty() ? "Enregistré" : r.message,
                            Toast.LENGTH_SHORT).show();
                    refreshProjectPanel();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showRenameDialog(String filename) {
        EditText field = new EditText(activity);
        field.setText(filename);
        field.setSelectAllOnFocus(true);
        field.setTextColor(Color.WHITE);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setPadding(OrionUi.dp(activity, 16), OrionUi.dp(activity, 8),
                OrionUi.dp(activity, 16), 0);
        wrap.addView(field, OrionUi.matchWrap());
        new AlertDialog.Builder(activity)
                .setTitle("Renommer")
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String to = field.getText() != null
                            ? field.getText().toString().trim() : "";
                    String renamed = OrionProjectStore.get(activity).renameFile(filename, to);
                    if (renamed.isEmpty()) {
                        Toast.makeText(activity, "Renommage impossible", Toast.LENGTH_SHORT).show();
                    } else {
                        if (selectedProjectFiles.remove(filename)) {
                            selectedProjectFiles.add(renamed);
                        }
                        Toast.makeText(activity, filename + " → " + renamed,
                                Toast.LENGTH_SHORT).show();
                        refreshProjectPanel();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showFileDiff(String filename) {
        OrionProjectStore store = OrionProjectStore.get(activity);
        String local = store.readFile(filename);
        String baseline = store.getLastPushedContent(filename);
        if (baseline != null) {
            showDiffDialog(filename, baseline, local, "depuis dernier push");
            return;
        }
        String repo = com.pegasuscorp.orbe.chat.ApiKeyStore.getGithubRepo(activity);
        String token = com.pegasuscorp.orbe.chat.ApiKeyStore.getGithubToken(activity);
        if (TextUtils.isEmpty(repo) || TextUtils.isEmpty(token)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Δ " + filename)
                    .setMessage("Pas encore de snapshot local (push une fois pour activer le diff).\n"
                            + "Configure aussi GitHub pour comparer au remote.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        Toast.makeText(activity, "Comparaison remote…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String remote = com.pegasuscorp.orbe.git.GitHubApiClient
                    .fetchFileContent(token, repo, filename, "main");
            main.post(() -> {
                if (remote == null) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Δ " + filename)
                            .setMessage("Fichier absent du remote (ou erreur). "
                                    + "Push une fois pour créer la base locale.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                showDiffDialog(filename, remote, local, "vs GitHub main");
            });
        }).start();
    }

    private void showDiffDialog(String filename, String baseline, String local, String subtitle) {
        String diff = OrionTextDiff.unified(filename, baseline, local);
        OrionUi.darkDialog(activity)
                .setTitle("Δ " + filename + " (" + subtitle + ")")
                .setView(OrionUi.darkMonoScroll(activity, diff))
                .setPositiveButton("✏️ Éditer", (d, w) -> showFileEditor(filename))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void loadProjectCommitHistory() {
        if (projectHistory == null) return;
        String repo = com.pegasuscorp.orbe.chat.ApiKeyStore.getGithubRepo(activity);
        String token = com.pegasuscorp.orbe.chat.ApiKeyStore.getGithubToken(activity);
        if (TextUtils.isEmpty(repo) || TextUtils.isEmpty(token)) {
            projectHistory.setText("📜 Commits — configure GitHub pour l'historique.");
            return;
        }
        projectHistory.setText("📜 Commits…");
        final String active = OrionProjectStore.get(activity).getActiveProject();
        new Thread(() -> {
            List<String> remote = com.pegasuscorp.orbe.git.GitHubApiClient
                    .listRecentCommits(token, repo, 5);
            main.post(() -> {
                if (projectHistory == null) return;
                if (!active.equals(OrionProjectStore.get(activity).getActiveProject())) return;
                if (remote.isEmpty()) {
                    projectHistory.setText("📜 Aucun commit distant (ou repo vide).");
                    return;
                }
                StringBuilder sb = new StringBuilder("📜 Derniers commits\n");
                for (String m : remote) sb.append("• ").append(m).append('\n');
                projectHistory.setText(sb.toString().trim());
            });
        }).start();
    }

    private void showProjectPicker() {
        OrionProjectStore store = OrionProjectStore.get(activity);
        List<String> projects = store.listProjects();
        if (projects.isEmpty()) {
            showNewProjectDialog();
            return;
        }
        String[] labels = projects.toArray(new String[0]);
        new AlertDialog.Builder(activity)
                .setTitle("Projet Orion")
                .setItems(labels, (d, which) -> {
                    selectedProjectFiles.clear();
                    store.setActive(labels[which]);
                    refreshProjectPanel();
                    Toast.makeText(activity, "Projet « " + labels[which] + " »",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Nouveau", (d, w) -> showNewProjectDialog())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showNewProjectDialog() {
        EditText field = new EditText(activity);
        field.setHint("balle-html");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.parseColor("#55FFFFFF"));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setPadding(OrionUi.dp(activity, 16), OrionUi.dp(activity, 8),
                OrionUi.dp(activity, 16), 0);
        wrap.addView(field, OrionUi.matchWrap());
        new AlertDialog.Builder(activity)
                .setTitle("Nouveau projet")
                .setMessage("Dossier local files/orion/projects/ — pas de push auto.")
                .setView(wrap)
                .setPositiveButton("Créer", (d, w) -> {
                    String name = field.getText().toString().trim();
                    String created = OrionProjectStore.get(activity).createProject(name);
                    if (created.isEmpty()) {
                        Toast.makeText(activity, "Nom invalide", Toast.LENGTH_SHORT).show();
                    } else {
                        selectedProjectFiles.clear();
                        refreshProjectPanel();
                        Toast.makeText(activity, "Projet « " + created + " »",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void pushActiveProject(boolean selectedOnly) {
        OrionProjectStore store = OrionProjectStore.get(activity);
        if (!store.hasActiveProject()) {
            Toast.makeText(activity, "Crée un projet d'abord", Toast.LENGTH_SHORT).show();
            showNewProjectDialog();
            return;
        }
        if (selectedOnly && selectedProjectFiles.isEmpty()) {
            Toast.makeText(activity, "Coche au moins un fichier", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject params = new JSONObject()
                    .put("action", "push")
                    .put("message", "feat(" + store.getActiveProject() + "): update");
            if (selectedOnly) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String name : selectedProjectFiles) arr.put(name);
                params.put("files", arr);
            }
            new OrionProjectTool().execute(activity, params, new ToolCallback() {
                @Override
                public void onSuccess(ToolResult result) {
                    main.post(() -> {
                        Toast.makeText(activity,
                                result != null ? result.text : "Push OK",
                                Toast.LENGTH_LONG).show();
                        refreshProjectPanel();
                    });
                }

                @Override
                public void onConfirmNeeded(String question, Runnable onConfirm,
                        Runnable onCancel) {
                    ChatConfirmBridge.askConfirm(activity, question, onConfirm, onCancel);
                }

                @Override
                public void onChoiceNeeded(String title, String[] labels,
                        java.util.function.IntConsumer onChosen, Runnable onCancel) {
                    ChatConfirmBridge.askChoice(activity, title, labels, onChosen, onCancel);
                }

                @Override
                public void onError(String error) {
                    main.post(() -> Toast.makeText(activity,
                            error != null ? error : "Push impossible",
                            Toast.LENGTH_LONG).show());
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "Push : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void makeActiveProjectPublic() {
        OrionProjectStore store = OrionProjectStore.get(activity);
        if (!store.hasActiveProject()) {
            Toast.makeText(activity, "Aucun projet actif", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject params = new JSONObject().put("action", "make_public");
            new OrionProjectTool().execute(activity, params, new ToolCallback() {
                @Override
                public void onSuccess(ToolResult result) {
                    main.post(() -> Toast.makeText(activity,
                            result != null ? result.text : "Dépôt public",
                            Toast.LENGTH_LONG).show());
                }

                @Override
                public void onConfirmNeeded(String question, Runnable onConfirm,
                        Runnable onCancel) {
                    ChatConfirmBridge.askConfirm(activity, question, onConfirm, onCancel);
                }

                @Override
                public void onChoiceNeeded(String title, String[] labels,
                        java.util.function.IntConsumer onChosen, Runnable onCancel) {
                    ChatConfirmBridge.askChoice(activity, title, labels, onChosen, onCancel);
                }

                @Override
                public void onError(String error) {
                    main.post(() -> Toast.makeText(activity,
                            error != null ? error : "Impossible de passer en public",
                            Toast.LENGTH_LONG).show());
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "Public : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveSessionFilesToProject(List<OrionFileSession.OrionFile> files, int index) {
        if (files == null || index >= files.size()) {
            refreshProjectPanel();
            refreshSessionPanel();
            String active = OrionProjectStore.get(activity).getActiveProject();
            if (!TextUtils.isEmpty(active)) {
                Toast.makeText(activity, "Enregistré dans « " + active + " »",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        OrionFileSession.OrionFile of = files.get(index);
        if (of.path != null && of.path.toLowerCase(Locale.ROOT).startsWith("orion_full")) {
            saveSessionFilesToProject(files, index + 1);
            return;
        }
        OrionProjectStore store = OrionProjectStore.get(activity);
        OrionProjectStore.SaveResult r = store.saveFile(of.path, of.content, false, false);
        if (r.outcome == OrionProjectStore.SaveOutcome.NEEDS_CONFIRM) {
            new AlertDialog.Builder(activity)
                    .setTitle(of.path + " existe déjà")
                    .setMessage("Dans le projet « " + store.getActiveProject() + " ».")
                    .setPositiveButton("Remplacer", (d, w) -> {
                        store.replaceFile(of.path, of.content);
                        saveSessionFilesToProject(files, index + 1);
                    })
                    .setNeutralButton("Nouveau", (d, w) -> {
                        store.saveAsNew(of.path, of.content);
                        saveSessionFilesToProject(files, index + 1);
                    })
                    .setNegativeButton("Ignorer", (d, w) ->
                            saveSessionFilesToProject(files, index + 1))
                    .show();
            return;
        }
        saveSessionFilesToProject(files, index + 1);
    }

    private LinearLayout buildSessionPanel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(OrionUi.dp(activity, 8), OrionUi.dp(activity, 6),
                OrionUi.dp(activity, 8), OrionUi.dp(activity, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#121820"));
        bg.setCornerRadius(OrionUi.dp(activity, 8));
        bg.setStroke(OrionUi.dp(activity, 1), Color.parseColor("#22303C"));
        panel.setBackground(bg);
        panel.setVisibility(View.GONE);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, OrionUi.dp(activity, 2), 0, OrionUi.dp(activity, 2));

        sessionChevron = new TextView(activity);
        sessionChevron.setText("▾");
        sessionChevron.setTextColor(Color.parseColor(OrionUi.CYAN));
        sessionChevron.setTextSize(14);
        sessionChevron.setPadding(0, 0, OrionUi.dp(activity, 6), 0);
        header.addView(sessionChevron);

        sessionTitle = new TextView(activity);
        sessionTitle.setTextColor(Color.parseColor(OrionUi.CYAN));
        sessionTitle.setTextSize(13);
        sessionTitle.setTypeface(null, Typeface.BOLD);
        header.addView(sessionTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View.OnClickListener toggleSession = v -> {
            PegaseSheets.haptic(v);
            setSessionExpanded(!sessionExpanded);
        };
        sessionChevron.setOnClickListener(toggleSession);
        sessionTitle.setOnClickListener(toggleSession);
        header.setOnClickListener(toggleSession);
        panel.addView(header, OrionUi.matchWrap());

        // Actions GitHub / validation toujours visibles (même replié)
        sessionActions = new LinearLayout(activity);
        sessionActions.setOrientation(LinearLayout.HORIZONTAL);
        Button validate = OrionUi.outlineBtn(activity, "Valider");
        validate.setTextSize(11);
        validate.setOnClickListener(v -> {
            if (!OrionFileStore.get().hasSession()) return;
            OrionFileStore.get().validateAll();
            refreshSessionPanel();
            Toast.makeText(activity, "Fichiers validés — prêts pour commit",
                    Toast.LENGTH_SHORT).show();
        });
        sessionActions.addView(validate, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button toProject = OrionUi.outlineBtn(activity, "Projet");
        toProject.setTextSize(11);
        toProject.setOnClickListener(v -> {
            if (!OrionFileStore.get().hasSession()) return;
            offerSaveToProject(OrionFileStore.get().getCurrentSession().getFiles());
        });
        sessionActions.addView(toProject, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button commit = OrionUi.cyanBtn(activity, "Commit");
        commit.setTextSize(11);
        commit.setOnClickListener(v -> commitSessionAll());
        sessionActions.addView(commit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = OrionUi.outlineBtn(activity, "Vider");
        clear.setTextSize(11);
        clear.setOnClickListener(v -> {
            OrionFileStore.get().clearSession();
            refreshSessionPanel();
        });
        sessionActions.addView(clear, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(sessionActions, OrionUi.matchWrap());

        sessionBody = new LinearLayout(activity);
        sessionBody.setOrientation(LinearLayout.VERTICAL);

        ScrollView filesScroll = new ScrollView(activity);
        filesScroll.setVerticalScrollBarEnabled(true);
        sessionFilesCol = new LinearLayout(activity);
        sessionFilesCol.setOrientation(LinearLayout.VERTICAL);
        sessionFilesCol.setPadding(0, OrionUi.dp(activity, 4), 0, OrionUi.dp(activity, 4));
        filesScroll.addView(sessionFilesCol, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, OrionUi.dp(activity, 140));
        sessionBody.addView(filesScroll, scrollLp);

        sessionHistory = new TextView(activity);
        sessionHistory.setTextColor(Color.parseColor("#66FFFFFF"));
        sessionHistory.setTextSize(11);
        sessionHistory.setPadding(0, OrionUi.dp(activity, 4), 0, 0);
        sessionBody.addView(sessionHistory, OrionUi.matchWrap());

        panel.addView(sessionBody, OrionUi.matchWrap());
        setSessionExpanded(true);
        return panel;
    }

    private void refreshSessionPanel() {
        if (sessionPanel == null) return;
        OrionFileSession session = OrionFileStore.get().getCurrentSession();
        if (session == null || session.isEmpty()) {
            sessionPanel.setVisibility(View.GONE);
            return;
        }
        boolean wasHidden = sessionPanel.getVisibility() != View.VISIBLE;
        sessionPanel.setVisibility(View.VISIBLE);
        // Nouvelle session → ouvrir la liste une fois ; sinon garder l'état replié
        if (wasHidden) {
            setSessionExpanded(true);
        }
        String active = OrionProjectStore.get(activity).getActiveProject();
        String dest = TextUtils.isEmpty(active) ? "choisir un projet" : "→ " + active;
        sessionTitle.setText("📥 À revoir (" + session.size() + ") " + dest
                + (sessionExpanded ? "" : " · fichiers repliés"));
        sessionFilesCol.removeAllViews();
        for (OrionFileSession.OrionFile f : session.getFiles()) {
            sessionFilesCol.addView(makeSessionFileRow(f), OrionUi.matchWrap());
        }
        StringBuilder hist = new StringBuilder();
        List<String> local = OrionFileStore.get().getCommitHistory();
        if (!local.isEmpty()) {
            hist.append("Historique :\n");
            for (int i = 0; i < Math.min(local.size(), 5); i++) {
                hist.append("• ").append(local.get(i)).append('\n');
            }
        }
        String repo = com.pegasuscorp.orbe.chat.ApiKeyStore.getGithubRepo(activity);
        String token = com.pegasuscorp.orbe.chat.ApiKeyStore.getGithubToken(activity);
        if (!TextUtils.isEmpty(repo) && !TextUtils.isEmpty(token)) {
            new Thread(() -> {
                List<String> remote = com.pegasuscorp.orbe.git.GitHubApiClient
                        .listRecentCommits(token, repo, 3);
                if (remote.isEmpty()) return;
                StringBuilder sb = new StringBuilder(hist);
                if (sb.length() == 0) sb.append("Commits GitHub :\n");
                else sb.append("GitHub :\n");
                for (String m : remote) sb.append("• ").append(m).append('\n');
                main.post(() -> {
                    if (sessionHistory != null) {
                        sessionHistory.setText(sb.toString().trim());
                    }
                });
            }).start();
        }
        sessionHistory.setText(hist.toString().trim());
    }

    private View makeSessionFileRow(OrionFileSession.OrionFile f) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, OrionUi.dp(activity, 2), 0, OrionUi.dp(activity, 2));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText(f.statusLabel() + " " + f.path + "  ·  " + f.lineCount() + " l.");
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LintReport lint = OrionLintClient.getCached(f.path);
        if (lint != null && lint.hasVisibleIssues()) {
            TextView badge = new TextView(activity);
            int err = lint.errorCount();
            int warn = lint.warningCount();
            StringBuilder bt = new StringBuilder("⚠ ");
            if (err > 0) bt.append(err).append(" err");
            if (err > 0 && warn > 0) bt.append(" · ");
            if (warn > 0) bt.append(warn).append(" warn");
            badge.setText(bt.toString());
            badge.setTextSize(10);
            badge.setTextColor(err > 0
                    ? Color.parseColor("#FF8A80")
                    : Color.parseColor("#FFD54F"));
            badge.setPadding(OrionUi.dp(activity, 6), 0, OrionUi.dp(activity, 4), 0);
            badge.setOnClickListener(v -> showLintDetails(f.path, lint));
            row.addView(badge);
        }

        Button eye = OrionUi.outlineBtn(activity, "👁");
        eye.setTextSize(10);
        eye.setOnClickListener(v -> showSessionFilePreview(f));
        row.addView(eye);

        Button edit = OrionUi.outlineBtn(activity, "✏️");
        edit.setTextSize(10);
        edit.setOnClickListener(v -> showSessionFileEditor(f));
        row.addView(edit);

        Button ok = OrionUi.outlineBtn(activity, "✅");
        ok.setTextSize(10);
        ok.setOnClickListener(v -> {
            OrionFileStore.get().setStatus(f.path, OrionFileSession.FileStatus.VALIDATED);
            refreshSessionPanel();
        });
        row.addView(ok);

        Button no = OrionUi.outlineBtn(activity, "❌");
        no.setTextSize(10);
        no.setOnClickListener(v -> {
            OrionFileStore.get().setStatus(f.path, OrionFileSession.FileStatus.REJECTED);
            refreshSessionPanel();
        });
        row.addView(no);

        col.addView(row, OrionUi.matchWrap());
        return col;
    }

    private void showLintDetails(String path, LintReport report) {
        if (report == null || !report.hasVisibleIssues()) return;
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(report.tool)) {
            sb.append(report.tool).append('\n');
        }
        for (LintReport.LintIssue issue : report.issues) {
            if (issue == null) continue;
            sb.append(issue.isError() ? "● " : "○ ")
                    .append(issue.summaryLine()).append('\n');
        }
        OrionUi.darkDialog(activity)
                .setTitle("Lint — " + path)
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showSessionFilePreview(OrionFileSession.OrionFile f) {
        if (OrionPagePreview.isPage(f.path, f.content)) {
            OrionPagePreview.openFullscreen(activity, f.path, f.content, sessionSiblingMap());
            return;
        }
        OrionUi.darkDialog(activity)
                .setTitle(f.statusLabel() + " " + f.path)
                .setView(OrionUi.darkCodeScroll(activity, f.path, f.content))
                .setPositiveButton("✏️ Éditer", (d, w) -> showSessionFileEditor(f))
                .setNeutralButton("Valider", (d, w) -> {
                    OrionFileStore.get().setStatus(f.path,
                            OrionFileSession.FileStatus.VALIDATED);
                    refreshSessionPanel();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private Map<String, String> sessionSiblingMap() {
        Map<String, String> out = new HashMap<>();
        OrionFileSession session = OrionFileStore.get().getCurrentSession();
        if (session == null) return out;
        for (OrionFileSession.OrionFile file : session.getFiles()) {
            if (file == null || file.path == null) continue;
            out.put(file.path, file.content != null ? file.content : "");
        }
        return out;
    }

    private void showSessionFileEditor(OrionFileSession.OrionFile f) {
        EditText field = new EditText(activity);
        field.setText(f.content);
        field.setTextColor(Color.WHITE);
        field.setBackgroundColor(Color.parseColor(OrionUi.BG));
        field.setTextSize(12);
        field.setTypeface(Typeface.MONOSPACE);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(12);
        field.setMaxLines(24);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPadding(OrionUi.dp(activity, 10), OrionUi.dp(activity, 8),
                OrionUi.dp(activity, 10), OrionUi.dp(activity, 8));
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.parseColor(OrionUi.BG));
        scroll.addView(field, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        OrionUi.darkDialog(activity)
                .setTitle("✏️ " + f.path)
                .setView(scroll)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String next = field.getText() != null ? field.getText().toString() : "";
                    OrionFileStore.get().updateContent(f.path, next);
                    OrionProjectStore proj = OrionProjectStore.get(activity);
                    if (proj.hasActiveProject() && proj.fileExists(f.path)) {
                        proj.replaceFile(f.path, next);
                    }
                    Toast.makeText(activity, "Corrigé : " + f.path, Toast.LENGTH_SHORT).show();
                    refreshSessionPanel();
                    refreshProjectPanel();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void commitSessionAll() {
        OrionFileStore store = OrionFileStore.get();
        if (!store.hasSession()) {
            Toast.makeText(activity, "Pas de session", Toast.LENGTH_SHORT).show();
            return;
        }
        int pending = 0;
        for (OrionFileSession.OrionFile f : store.getCurrentSession().getFiles()) {
            if (f != null && f.status == OrionFileSession.FileStatus.PENDING) pending++;
        }
        if (pending > 0) {
            store.validateAll();
            Toast.makeText(activity, pending + " fichier(s) validé(s) avant commit",
                    Toast.LENGTH_SHORT).show();
        }
        if (store.getReadyFiles().isEmpty()) {
            store.validateAll();
        }
        try {
            JSONObject params = new JSONObject()
                    .put("action", "commit")
                    .put("session", true)
                    .put("message", store.defaultCommitMessage());
            new GitCommitTool().execute(activity, params, new ToolCallback() {
                @Override
                public void onSuccess(ToolResult result) {
                    main.post(() -> {
                        Toast.makeText(activity,
                                result != null ? result.text : "Commit OK",
                                Toast.LENGTH_LONG).show();
                        refreshSessionPanel();
                        refreshProjectPanel();
                    });
                }

                @Override
                public void onConfirmNeeded(String question, Runnable onConfirm,
                        Runnable onCancel) {
                    ChatConfirmBridge.askConfirm(activity, question, onConfirm, onCancel);
                }

                @Override
                public void onChoiceNeeded(String title, String[] labels,
                        java.util.function.IntConsumer onChosen, Runnable onCancel) {
                    ChatConfirmBridge.askChoice(activity, title, labels, onChosen, onCancel);
                }

                @Override
                public void onError(String error) {
                    main.post(() -> Toast.makeText(activity,
                            error != null ? error : "Commit impossible",
                            Toast.LENGTH_LONG).show());
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "Commit : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
