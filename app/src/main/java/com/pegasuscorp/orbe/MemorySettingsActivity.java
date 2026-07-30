package com.pegasuscorp.orbe;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryGraphLabels;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.memory.SessionSummary;
import com.pegasuscorp.orbe.memory.UserProfileStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Éditeur de la mémoire Pégase : souvenirs permanents, profil et résumés de session.
 */
public class MemorySettingsActivity extends AppCompatActivity {

    private static final int TAB_MEMORIES = 0;
    private static final int TAB_PROFILE = 1;
    private static final int TAB_SESSIONS = 2;

    private float density;
    private LinearLayout contentHost;
    private View[] tabButtons = new View[3];
    private int activeTab = TAB_MEMORIES;

    private MemoryRepository memory;
    private UserProfileStore profile;

    private EditText nameField;
    private EditText personalityField;
    private EditText projectsField;
    private EditText interestsField;
    private EditText notesField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        memory = MemoryRepository.getInstance(this);
        profile = UserProfileStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Mémoire de Pégase");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Souvenirs · Profil · Sessions — modifiables ici ou à la voix");
        subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(10));
        root.addView(tabRow, matchWrap());

        tabButtons[TAB_MEMORIES] = makeTabButton(tabRow, "Souvenirs", TAB_MEMORIES);
        tabButtons[TAB_PROFILE] = makeTabButton(tabRow, "Profil", TAB_PROFILE);
        tabButtons[TAB_SESSIONS] = makeTabButton(tabRow, "Sessions", TAB_SESSIONS);

        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contentHost, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });

        setContentView(root);
        selectTab(TAB_MEMORIES);
    }

    private void selectTab(int tab) {
        if (activeTab == TAB_PROFILE && tab != TAB_PROFILE) {
            saveProfileFieldsQuiet();
        }
        activeTab = tab;
        for (int i = 0; i < tabButtons.length; i++) {
            styleTab(tabButtons[i], i == tab);
        }
        contentHost.removeAllViews();
        switch (tab) {
            case TAB_MEMORIES:
                buildMemoriesTab();
                break;
            case TAB_PROFILE:
                buildProfileTab();
                break;
            case TAB_SESSIONS:
                buildSessionsTab();
                break;
            default:
                break;
        }
    }

    private void buildMemoriesTab() {
        contentHost.addView(hint(
                "Faits que Pégase retient entre les discussions. "
                        + "Tu peux aussi dire « retiens que… » ou « oublie… » à la voix."));
        addActionButton("Portrait — ce qu'elle croit savoir", () ->
                PortraitActivity.open(this));
        addActionButton("Atlas des entités (personnes, projets…)", () ->
                startActivity(new Intent(this, AtlasSettingsActivity.class)));
        addActionButton("API, modèle cloud & clés", () ->
                startActivity(new Intent(this, ApiSettingsActivity.class)));
        addActionButton("+ Ajouter un souvenir", this::showAddMemoryDialog);

        List<String> atlasEdges = MemoryGraphLabels.atlasEdgesLines(this);
        if (!atlasEdges.isEmpty()) {
            LinearLayout graphCard = cardContainer();
            TextView graphTitle = new TextView(this);
            graphTitle.setText("Graphe atlas — " + atlasEdges.size() + " lien"
                    + (atlasEdges.size() > 1 ? "s" : "") + " entité↔entité");
            graphTitle.setTextColor(Color.parseColor("#35D0DD"));
            graphTitle.setTextSize(13);
            graphTitle.setTypeface(null, Typeface.BOLD);
            graphCard.addView(graphTitle);
            for (String line : atlasEdges) {
                TextView edgeLine = new TextView(this);
                edgeLine.setText("• " + line);
                edgeLine.setTextColor(Color.parseColor("#CCFFFFFF"));
                edgeLine.setTextSize(12);
                graphCard.addView(edgeLine);
            }
            contentHost.addView(graphCard);
        }

        List<MemoryEntry> entries = memory.getAllPermanentMemories();
        if (entries.isEmpty()) {
            contentHost.addView(emptyLabel("Aucun souvenir pour l'instant."));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            MemoryEntry e = entries.get(i);
            contentHost.addView(memoryCard(e, index));
        }
    }

    private View memoryCard(MemoryEntry e, int index) {
        LinearLayout card = cardContainer();
        TextView meta = new TextView(this);
        meta.setTextColor(Color.parseColor("#88FFFFFF"));
        meta.setTextSize(11);
        String date = e.createdAt == null || e.createdAt.isEmpty() ? "—" : e.createdAt;
        meta.setText("[" + safeCategory(e.category) + "] · " + date);
        card.addView(meta);

        TextView body = new TextView(this);
        body.setText(e.content);
        body.setTextColor(Color.WHITE);
        body.setTextSize(14);
        body.setPadding(0, dp(6), 0, dp(4));
        card.addView(body);

        String entityLine = MemoryGraphLabels.entityLinksLine(this, e);
        if (!entityLine.isEmpty()) {
            TextView entities = new TextView(this);
            entities.setText("🔗 " + entityLine);
            entities.setTextColor(Color.parseColor("#88D0DD"));
            entities.setTextSize(12);
            entities.setPadding(0, 0, 0, dp(4));
            card.addView(entities);
        }

        String relatedLine = MemoryGraphLabels.relatedMemoriesLine(memory, e);
        if (!relatedLine.isEmpty()) {
            TextView related = new TextView(this);
            related.setText(relatedLine);
            related.setTextColor(Color.parseColor("#99FFFFFF"));
            related.setTextSize(11);
            related.setPadding(0, 0, 0, dp(6));
            card.addView(related);
        } else {
            body.setPadding(0, dp(6), 0, dp(8));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton("Modifier", () -> showEditMemoryDialog(index, e)));
        actions.addView(spacerH(dp(8)));
        actions.addView(smallButton("Supprimer", () -> confirmDeleteMemory(index)));
        card.addView(actions);
        return card;
    }

    private void showAddMemoryDialog() {
        showMemoryDialog(null, "user", "", 0.85, (category, content, importance) -> {
            memory.addPermanentMemory(new MemoryEntry(
                    category, content, importance, today()));
            selectTab(TAB_MEMORIES);
            toast("Souvenir ajouté.");
        });
    }

    private void showEditMemoryDialog(int index, MemoryEntry e) {
        showMemoryDialog(e.content, e.category, e.content, e.importance,
                (category, content, importance) -> {
                    MemoryEntry updated = new MemoryEntry(
                            category, content, importance,
                            e.createdAt == null || e.createdAt.isEmpty() ? today() : e.createdAt,
                            e.source);
                    updated.entityIds.addAll(e.entityIds);
                    updated.relatedMemoryKeys.addAll(e.relatedMemoryKeys);
                    memory.updatePermanentMemoryAt(index, updated);
                    selectTab(TAB_MEMORIES);
                    toast("Souvenir mis à jour.");
                });
    }

    private void showMemoryDialog(String title, String category, String content,
                                  double importance, MemorySaveCallback onSave) {
        LinearLayout layout = paddedVertical(dp(8));
        layout.setBackgroundColor(Color.parseColor("#FF1A1D24"));
        EditText catField = fieldWithHint("Catégorie (user, project, preference…)", category, 1);
        layout.addView(catField);

        EditText contentField = fieldWithHint("Contenu du souvenir", content, 4);
        layout.addView(contentField);

        darkDialog()
                .setTitle(title == null ? "Nouveau souvenir" : "Modifier le souvenir")
                .setView(layout)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String cat = catField.getText().toString().trim();
                    String text = contentField.getText().toString().trim();
                    if (text.isEmpty()) {
                        toast("Le contenu ne peut pas être vide.");
                        return;
                    }
                    if (cat.isEmpty()) cat = "user";
                    onSave.onSave(cat, text, importance);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDeleteMemory(int index) {
        darkDialog()
                .setTitle("Supprimer ce souvenir ?")
                .setMessage("Pégase ne s'en souviendra plus.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    memory.removePermanentMemoryAt(index);
                    selectTab(TAB_MEMORIES);
                    toast("Souvenir supprimé.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void buildProfileTab() {
        contentHost.addView(hint(
                "Profil structuré injecté dans le prompt de Pégase. Une ligne = un élément pour les listes."));
        addActionButton("Enregistrer le profil", this::saveProfileFields);
        addActionButton("Édition JSON avancée", this::showJsonEditor);

        contentHost.addView(fieldLabel("Ton prénom"));
        nameField = singleLineField(profile.getUserName());
        contentHost.addView(nameField);

        contentHost.addView(fieldLabel("Personnalité de Pégase"));
        contentHost.addView(hintLabel(
                "Décris comment Pégase te parle (ton, humour, style). Injecté dans chaque discussion."));
        personalityField = multilineField(profile.getAssistantPersonality(), 3);
        contentHost.addView(personalityField);

        contentHost.addView(fieldLabel("Projets (un par ligne)"));
        projectsField = multilineField(lines(profile.getProjectsList()), 4);
        contentHost.addView(projectsField);

        contentHost.addView(fieldLabel("Centres d'intérêt (un par ligne)"));
        interestsField = multilineField(lines(profile.getInterestsList()), 4);
        contentHost.addView(interestsField);

        contentHost.addView(fieldLabel("Notes privées pour Pégase (un par ligne)"));
        notesField = multilineField(lines(profile.getNotesList()), 6);
        contentHost.addView(notesField);
    }

    private void saveProfileFields() {
        if (saveProfileFieldsQuiet()) {
            toast("Profil enregistré.");
        } else {
            toast("Impossible d'enregistrer le profil.");
        }
    }

    private boolean saveProfileFieldsQuiet() {
        if (nameField == null) return false;
        boolean ok = profile.saveProfileForm(
                nameField.getText().toString().trim(),
                personalityField != null ? personalityField.getText().toString().trim() : null,
                parseLines(projectsField.getText().toString()),
                parseLines(interestsField.getText().toString()),
                parseLines(notesField.getText().toString()));
        return ok;
    }

    @Override
    protected void onPause() {
        if (activeTab == TAB_PROFILE) {
            saveProfileFieldsQuiet();
        }
        super.onPause();
    }

    private void showJsonEditor() {
        EditText jsonField = multilineField(profile.getProfileJsonPretty(), 14);
        jsonField.setTypeface(Typeface.MONOSPACE);
        jsonField.setHorizontallyScrolling(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#FF1A1D24"));
        scroll.addView(jsonField);

        darkDialog()
                .setTitle("Profil JSON")
                .setMessage("Réservé aux modifications avancées. JSON invalide = refusé.")
                .setView(scroll)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    if (profile.setProfileFromJson(jsonField.getText().toString())) {
                        toast("Profil JSON enregistré.");
                        selectTab(TAB_PROFILE);
                    } else {
                        toast("JSON invalide — rien n'a été modifié.");
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void buildSessionsTab() {
        contentHost.addView(hint(
                "Résumés générés à la fin des discussions. Lecture seule ici ; tu peux supprimer un résumé obsolète."));
        addActionButton("Effacer la discussion en cours (tours récents)", () ->
                darkDialog()
                        .setTitle("Effacer les tours récents ?")
                        .setMessage("La conversation affichée dans l'interface sera vidée. "
                                + "Les souvenirs permanents et le profil ne sont pas touchés.")
                        .setPositiveButton("Effacer", (d, w) -> {
                            memory.clearRecentTurns();
                            toast("Discussion récente effacée.");
                        })
                        .setNegativeButton("Annuler", null)
                        .show());

        List<SessionSummary> sessions = memory.getAllSessionSummaries();
        if (sessions.isEmpty()) {
            contentHost.addView(emptyLabel("Aucun résumé de session pour l'instant."));
            return;
        }
        List<SessionSummary> reversed = new ArrayList<>(sessions);
        java.util.Collections.reverse(reversed);
        for (int i = 0; i < reversed.size(); i++) {
            SessionSummary s = reversed.get(i);
            int realIndex = sessions.size() - 1 - i;
            contentHost.addView(sessionCard(s, realIndex));
        }
    }

    private View sessionCard(SessionSummary s, int index) {
        LinearLayout card = cardContainer();
        TextView meta = new TextView(this);
        meta.setTextColor(Color.parseColor("#88FFFFFF"));
        meta.setTextSize(11);
        String topic = s.topic == null || s.topic.isEmpty() ? "Session" : s.topic;
        String date = s.endedAt == null || s.endedAt.isEmpty() ? "—" : s.endedAt;
        meta.setText(topic + " · " + date);
        card.addView(meta);

        if (s.summary != null && !s.summary.isEmpty()) {
            TextView summary = new TextView(this);
            summary.setText(s.summary);
            summary.setTextColor(Color.WHITE);
            summary.setTextSize(14);
            summary.setPadding(0, dp(6), 0, dp(6));
            card.addView(summary);
        }
        appendListSection(card, "Faits importants", s.importantFacts);
        appendListSection(card, "Décisions", s.decisions);
        appendListSection(card, "À reprendre", s.pendingTopics);

        card.addView(smallButton("Supprimer ce résumé", () -> confirmDeleteSession(index)));
        return card;
    }

    private void appendListSection(LinearLayout card, String title, List<String> items) {
        if (items == null || items.isEmpty()) return;
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.parseColor("#AAFFFFFF"));
        label.setTextSize(12);
        label.setPadding(0, dp(4), 0, dp(2));
        card.addView(label);
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) continue;
            TextView line = new TextView(this);
            line.setText("• " + item.trim());
            line.setTextColor(Color.parseColor("#CCFFFFFF"));
            line.setTextSize(13);
            card.addView(line);
        }
    }

    private void confirmDeleteSession(int index) {
        darkDialog()
                .setTitle("Supprimer ce résumé ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    memory.removeSessionSummaryAt(index);
                    selectTab(TAB_SESSIONS);
                    toast("Résumé supprimé.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private View makeTabButton(LinearLayout row, String label, int tabIndex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        btn.setOnClickListener(v -> selectTab(tabIndex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = tabIndex < 2 ? dp(6) : 0;
        row.addView(btn, lp);
        return btn;
    }

    private void styleTab(View tab, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(selected ? Color.parseColor("#2835D0DD") : Color.parseColor("#14FFFFFF"));
        if (selected) bg.setStroke(dp(1), Color.parseColor("#35D0DD"));
        tab.setBackground(bg);
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor("#18FFFFFF"));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView hint(String text) {
        TextView tv = hintLabel(text);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView hintLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#66FFFFFF"));
        tv.setTextSize(11);
        return tv;
    }

    private TextView emptyLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#55FFFFFF"));
        tv.setTextSize(14);
        tv.setPadding(0, dp(20), 0, dp(20));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView fieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#AAFFFFFF"));
        tv.setTextSize(12);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addActionButton(String label, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor("#14FFFFFF"));
        btn.setBackground(bg);
        btn.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        contentHost.addView(btn, lp);
    }

    private TextView smallButton(String label, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#35D0DD"));
        btn.setTextSize(13);
        btn.setPadding(dp(4), dp(4), dp(12), dp(4));
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private MaterialAlertDialogBuilder darkDialog() {
        return new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog);
    }

    private static final int FIELD_BG = Color.parseColor("#FF252830");

    private EditText singleLineField(String value) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.parseColor("#88FFFFFF"));
        field.setBackgroundColor(FIELD_BG);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setSingleLine(true);
        return field;
    }

    private EditText multilineField(String value, int minLines) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.parseColor("#88FFFFFF"));
        field.setBackgroundColor(FIELD_BG);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setMinLines(minLines);
        field.setGravity(Gravity.TOP);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMovementMethod(new ScrollingMovementMethod());
        return field;
    }

    private EditText fieldWithHint(String hint, String value, int minLines) {
        EditText field = multilineField(value, minLines);
        field.setHint(hint);
        return field;
    }

    private LinearLayout paddedVertical(int pad) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    private View spacerH(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private static String lines(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static List<String> parseLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static String safeCategory(String category) {
        return category == null || category.isEmpty() ? "general" : category;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private interface MemorySaveCallback {
        void onSave(String category, String content, double importance);
    }
}
