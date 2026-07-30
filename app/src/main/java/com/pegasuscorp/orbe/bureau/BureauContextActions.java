package com.pegasuscorp.orbe.bureau;

import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.diag.Trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dialogues ouvrir / enregistrer contexte nommé pour le Bureau Markdown.
 */
final class BureauContextActions {

    private final AppCompatActivity activity;
    private final BureauMarkdownPanel panel;

    BureauContextActions(AppCompatActivity activity, BureauMarkdownPanel panel) {
        this.activity = activity;
        this.panel = panel;
    }

    void showOpenPicker() {
        ContextualFileStore ctxStore = ContextualFileStore.getInstance(activity);
        List<ContextualFileStore.Meta> metas = ctxStore.listContexts();
        List<String> sessions = BureauSessionStore.listSessionFiles(activity);

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add("Nouveau projet (interview)…");
        actions.add(() -> BureauPlanningActivity.open(activity));

        labels.add("Session du jour (" + BureauSessionStore.todayFilename() + ")");
        actions.add(() -> openLocalSession(BureauSessionStore.todayFilename()));

        for (String slug : BureauProjectStore.listSlugs(activity)) {
            labels.add("Projet : " + slug);
            String s = slug;
            actions.add(() -> panel.openStructuredProject(s));
        }

        for (String s : sessions) {
            if (s.equals(BureauSessionStore.todayFilename())) continue;
            labels.add("Session : " + s);
            String filename = s;
            actions.add(() -> openLocalSession(filename));
        }
        for (ContextualFileStore.Meta m : metas) {
            labels.add("Contexte : " + m.keyword + " (" + m.filename + ")");
            String filename = m.filename;
            actions.add(() -> openNamedContext(filename));
        }

        new AlertDialog.Builder(activity)
                .setTitle("Ouvrir un fichier")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Annuler", null)
                .show();
    }

    void showNewPlanDialog() {
        final EditText input = new EditText(activity);
        input.setHint("Titre du plan");
        input.setTextColor(BureauMarkdownPanel.TEXT);
        input.setHintTextColor(BureauMarkdownPanel.MUTED);
        new AlertDialog.Builder(activity)
                .setTitle("Nouveau plan")
                .setView(input)
                .setPositiveButton("Créer", (d, w) -> {
                    panel.persistNow();
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) title = "Nouveau plan";
                    String slug = title.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9àâäéèêëïîôùûüç\\s-]", "")
                            .replaceAll("\\s+", "-");
                    if (slug.isEmpty()) slug = "plan";
                    panel.setCurrentFilename("plan-" + slug + ".md");
                    panel.setEditingContextKeyword(null);
                    String doc = BureauPlanTemplate.namedPlan(title);
                    panel.setDocumentText(doc);
                    panel.updateFileLabel();
                    panel.persistNow();
                    // Disponible pour import conversation
                    try {
                        ContextualFileStore.getInstance(activity).save(slug, doc);
                    } catch (Exception ignored) {
                    }
                    panel.refreshPreview();
                    panel.refreshOutline();
                    panel.reloadThreadUi();
                    Trace.bureauAction("new_plan", panel.getCurrentFilename());
                    panel.setStatus("Nouveau plan");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void openLocalSession(String filename) {
        panel.persistNow();
        String content = BureauSessionStore.loadFile(activity, filename);
        if (content == null) {
            Toast.makeText(activity, "Fichier introuvable", Toast.LENGTH_SHORT).show();
            return;
        }
        panel.setCurrentFilename(filename);
        panel.setEditingContextKeyword(null);
        panel.setDocumentText(content);
        panel.applyStructuredMode(false);
        panel.updateFileLabel();
        panel.markClean();
        panel.refreshPreview();
        panel.refreshOutline();
        panel.reloadThreadUi();
        Trace.bureauAction("open_file", filename);
        panel.setStatus("Ouvert");
    }

    void openNamedContext(String filename) {
        panel.persistNow();
        ContextualFileStore store = ContextualFileStore.getInstance(activity);
        String content = store.readFile(filename);
        if (content == null) {
            Toast.makeText(activity, "Contexte introuvable", Toast.LENGTH_SHORT).show();
            return;
        }
        panel.setCurrentFilename("edit-" + filename);
        panel.setEditingContextKeyword(keywordForFilename(store, filename));
        BureauSessionStore.saveAsync(activity, panel.getCurrentFilename(), content);
        panel.setDocumentText(content);
        panel.applyStructuredMode(false);
        panel.updateFileLabel();
        panel.markClean();
        panel.refreshPreview();
        panel.refreshOutline();
        panel.reloadThreadUi();
        Trace.bureauAction("open_file", "context:" + filename);
        panel.setStatus("Contexte ouvert");
    }

    static String keywordForFilename(ContextualFileStore store, String filename) {
        if (store == null || filename == null) return null;
        for (ContextualFileStore.Meta m : store.listContexts()) {
            if (filename.equals(m.filename)) return m.keyword;
        }
        return filename.replace("-context.md", "").replace(".md", "");
    }

    void showSaveDialog() {
        final String[] choices = new String[]{
                "Créer un nouveau projet…",
                "Remplacer un fichier existant…"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Enregistrer")
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        showCreateNewProject();
                    } else {
                        showReplaceExistingContext();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void showCreateNewProject() {
        final EditText input = new EditText(activity);
        input.setHint("ex. olympo, balle-html…");
        input.setTextColor(BureauMarkdownPanel.TEXT);
        input.setHintTextColor(BureauMarkdownPanel.MUTED);
        suggestKeyword(input);
        new AlertDialog.Builder(activity)
                .setTitle("Nouveau projet")
                .setMessage("Nom du projet (fichier contexte). S'il existe déjà, confirmation pour remplacer.")
                .setView(input)
                .setPositiveButton("Créer", (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (key.isEmpty()) {
                        Toast.makeText(activity, "Indique un nom de projet", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ContextualFileStore store = ContextualFileStore.getInstance(activity);
                    if (store.contextExists(key)) {
                        confirmOverwrite(key);
                    } else {
                        saveContextAndConfirm(key, false);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void suggestKeyword(EditText input) {
        String editing = panel.getEditingContextKeyword();
        if (editing != null) {
            input.setText(editing);
            input.setSelection(editing.length());
            return;
        }
        String doc = panel.getDocumentText();
        if (doc.startsWith("# ")) {
            String first = doc.split("\n", 2)[0].trim();
            if (first.startsWith("# ")) {
                String suggest = first.substring(2).trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9àâäéèêëïîôùûüç\\s-]", "")
                        .replaceAll("\\s+", "-");
                if (!suggest.isEmpty() && suggest.length() < 40) {
                    input.setText(suggest);
                    input.setSelection(suggest.length());
                }
            }
        }
    }

    void showReplaceExistingContext() {
        ContextualFileStore store = ContextualFileStore.getInstance(activity);
        List<ContextualFileStore.Meta> metas = store.listContexts();
        if (metas.isEmpty()) {
            Toast.makeText(activity, "Aucun fichier de contexte — crée un nouveau projet.",
                    Toast.LENGTH_SHORT).show();
            showCreateNewProject();
            return;
        }
        List<String> labels = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        int preselect = -1;
        String editing = panel.getEditingContextKeyword();
        for (int i = 0; i < metas.size(); i++) {
            ContextualFileStore.Meta m = metas.get(i);
            labels.add(m.keyword + " (" + m.filename + ")");
            keys.add(m.keyword);
            if (editing != null && editing.equalsIgnoreCase(m.keyword)) {
                preselect = i;
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Remplacer un fichier")
                .setNegativeButton("Annuler", null);
        if (preselect >= 0) {
            final int idx = preselect;
            b.setMessage("Fichier ouvert : « " + keys.get(idx) + " ».\n"
                            + "Remplacer celui-ci, ou choisir un autre ?")
                    .setPositiveButton("Remplacer « " + keys.get(idx) + " »",
                            (d, w) -> confirmOverwrite(keys.get(idx)))
                    .setNeutralButton("Autre…", (d, w) ->
                            showReplacePicker(labels, keys));
        } else {
            b.setItems(labels.toArray(new String[0]),
                    (d, which) -> confirmOverwrite(keys.get(which)));
        }
        b.show();
    }

    void showReplacePicker(List<String> labels, List<String> keys) {
        new AlertDialog.Builder(activity)
                .setTitle("Choisir le fichier à remplacer")
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> confirmOverwrite(keys.get(which)))
                .setNegativeButton("Annuler", null)
                .show();
    }

    void confirmOverwrite(String key) {
        new AlertDialog.Builder(activity)
                .setTitle("Remplacer « " + key + " » ?")
                .setMessage("Le contenu actuel du bureau écrasera le fichier de contexte. "
                        + "Cette action est définitive.")
                .setPositiveButton("Remplacer", (d, w) -> saveContextAndConfirm(key, true))
                .setNegativeButton("Annuler", null)
                .show();
    }

    void saveContextAndConfirm(String key, boolean replaced) {
        ContextualFileStore store = ContextualFileStore.getInstance(activity);
        store.save(key, panel.getDocumentText());
        // Save-and-load : disponible pour chat / Orion immédiatement
        store.load(key);
        panel.setEditingContextKeyword(key);
        panel.updateFileLabel();
        Trace.bureauAction(replaced ? "replace_context" : "save_context", key);
        Toast.makeText(activity,
                replaced
                        ? "« " + key + " » remplacé et chargé"
                        : "Projet « " + key + " » créé et chargé",
                Toast.LENGTH_SHORT).show();
        panel.setStatus(replaced ? "Fichier remplacé" : "Nouveau projet sauvé");
        panel.markClean();
    }
}
