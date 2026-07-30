package com.pegasuscorp.orbe;

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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.R;
import com.pegasuscorp.orbe.memory.Entity;
import com.pegasuscorp.orbe.memory.EntityStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Éditeur de l'atlas : personnes, projets, lieux, appareils (fiches structurées).
 */
public class AtlasSettingsActivity extends AppCompatActivity {

    private static final int TAB_ALL = 0;
    private static final int TAB_PERSON = 1;
    private static final int TAB_PROJECT = 2;
    private static final int TAB_PLACE = 3;
    private static final int TAB_DEVICE = 4;

    private float density;
    private LinearLayout contentHost;
    private View[] tabButtons = new View[5];
    private int activeTab = TAB_ALL;

    private EntityStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = EntityStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Atlas de Pégase");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Fiches structurées · alias · Pégase n'injecte que celles qui matchent");
        subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(10));
        root.addView(tabRow, matchWrap());

        tabButtons[TAB_ALL] = makeTabButton(tabRow, "Tous", TAB_ALL);
        tabButtons[TAB_PERSON] = makeTabButton(tabRow, "👤", TAB_PERSON);
        tabButtons[TAB_PROJECT] = makeTabButton(tabRow, "📁", TAB_PROJECT);
        tabButtons[TAB_PLACE] = makeTabButton(tabRow, "📍", TAB_PLACE);
        tabButtons[TAB_DEVICE] = makeTabButton(tabRow, "📱", TAB_DEVICE);

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
        refreshTab();
    }

    private void refreshTab() {
        for (int i = 0; i < tabButtons.length; i++) {
            styleTab(tabButtons[i], i == activeTab);
        }
        contentHost.removeAllViews();
        contentHost.addView(hint(
                "Quand tu dis « ma chérie » ou « mon assistant », Pégase résout l'alias "
                        + "et n'envoie que la fiche correspondante au LLM."));

        addActionButton("+ Ajouter une fiche", () -> showEntityDialog(null, defaultTypeForTab()));

        List<Entity> list = entitiesForTab();
        if (list.isEmpty()) {
            contentHost.addView(emptyLabel("Aucune fiche dans cette catégorie."));
            return;
        }
        for (Entity entity : list) {
            contentHost.addView(entityCard(entity));
        }
    }

    private String defaultTypeForTab() {
        switch (activeTab) {
            case TAB_PERSON: return Entity.TYPE_PERSON;
            case TAB_PROJECT: return Entity.TYPE_PROJECT;
            case TAB_PLACE: return Entity.TYPE_PLACE;
            case TAB_DEVICE: return Entity.TYPE_DEVICE;
            default: return Entity.TYPE_PERSON;
        }
    }

    private List<Entity> entitiesForTab() {
        switch (activeTab) {
            case TAB_PERSON: return store.listByType(Entity.TYPE_PERSON);
            case TAB_PROJECT: return store.listByType(Entity.TYPE_PROJECT);
            case TAB_PLACE: return store.listByType(Entity.TYPE_PLACE);
            case TAB_DEVICE: return store.listByType(Entity.TYPE_DEVICE);
            default: return store.listByType(null);
        }
    }

    private View entityCard(Entity e) {
        LinearLayout card = cardContainer();

        TextView meta = new TextView(this);
        meta.setTextColor(Color.parseColor("#88FFFFFF"));
        meta.setTextSize(11);
        meta.setText(Entity.typeLabelFr(e.type) + " · " + e.id);
        card.addView(meta);

        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(0, dp(4), 0, dp(2));
        card.addView(name);

        String extra = e.extraFieldValue();
        if (extra != null && !extra.trim().isEmpty()) {
            TextView extraTv = new TextView(this);
            extraTv.setText(Entity.extraFieldLabel(e.type) + " : " + extra.trim());
            extraTv.setTextColor(Color.parseColor("#AAFFFFFF"));
            extraTv.setTextSize(13);
            card.addView(extraTv);
        }

        if (!e.aliases.isEmpty()) {
            TextView aliases = new TextView(this);
            aliases.setText("Alias : " + joinAliases(e.aliases));
            aliases.setTextColor(Color.parseColor("#35D0DD"));
            aliases.setTextSize(12);
            aliases.setPadding(0, dp(4), 0, dp(4));
            card.addView(aliases);
        }

        for (String fact : e.getFacts()) {
            TextView line = new TextView(this);
            line.setText("• " + fact);
            line.setTextColor(Color.parseColor("#CCFFFFFF"));
            line.setTextSize(13);
            card.addView(line);
        }

        TextView preview = new TextView(this);
        preview.setText("Prompt : " + e.toPromptBlock());
        preview.setTextColor(Color.parseColor("#55FFFFFF"));
        preview.setTextSize(11);
        preview.setPadding(0, dp(6), 0, dp(4));
        card.addView(preview);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton("Modifier", () -> showEntityDialog(e, e.type)));
        actions.addView(spacerH(dp(8)));
        actions.addView(smallButton("Supprimer", () -> confirmDelete(e)));
        card.addView(actions);
        return card;
    }

    private void showEntityDialog(Entity existing, String defaultType) {
        boolean editing = existing != null;
        final String[] selectedType = {editing ? existing.type : defaultType};

        LinearLayout layout = paddedVertical(dp(8));
        layout.setBackgroundColor(Color.parseColor("#FF1A1D24"));

        List<TextView> typeChips = new ArrayList<>();
        if (!editing) {
            layout.addView(fieldLabel("Type de fiche"));
            LinearLayout typeRow = new LinearLayout(this);
            typeRow.setOrientation(LinearLayout.HORIZONTAL);
            typeChips.add(makeTypeChip(typeRow, "Personne", Entity.TYPE_PERSON, selectedType, typeChips));
            typeChips.add(makeTypeChip(typeRow, "Projet", Entity.TYPE_PROJECT, selectedType, typeChips));
            layout.addView(typeRow);
            LinearLayout typeRow2 = new LinearLayout(this);
            typeRow2.setOrientation(LinearLayout.HORIZONTAL);
            typeChips.add(makeTypeChip(typeRow2, "Lieu", Entity.TYPE_PLACE, selectedType, typeChips));
            typeChips.add(makeTypeChip(typeRow2, "Appareil", Entity.TYPE_DEVICE, selectedType, typeChips));
            layout.addView(typeRow2);
            layout.addView(spacerV(dp(8)));
        }

        EditText idField = fieldWithHint("Identifiant (ex. person_sarah)",
                editing ? existing.id : EntityStore.suggestId(selectedType[0], ""), 1);
        EditText nameField = fieldWithHint("Nom affiché", editing ? existing.name : "", 1);
        EditText aliasesField = fieldWithHint(
                "Alias (un par ligne) — « ma chérie », « mon assistant »…",
                editing ? lines(existing.aliases) : "", 3);
        final TextView extraLabel = fieldLabel(Entity.extraFieldLabel(selectedType[0]));
        EditText extraField = fieldWithHint(
                Entity.extraFieldLabel(selectedType[0]),
                editing ? existing.extraFieldValue() : "", 1);
        EditText factsField = fieldWithHint("Faits (un par ligne)",
                editing ? lines(existing.getFacts()) : "", 3);

        if (!editing) {
            for (TextView chip : typeChips) {
                chip.setOnClickListener(v -> {
                    selectedType[0] = (String) v.getTag();
                    for (TextView c : typeChips) {
                        styleChip(c, selectedType[0].equals(c.getTag()));
                    }
                    extraLabel.setText(Entity.extraFieldLabel(selectedType[0]));
                    extraField.setHint(Entity.extraFieldLabel(selectedType[0]));
                    boolean showExtra = Entity.TYPE_PERSON.equals(selectedType[0])
                            || Entity.TYPE_PROJECT.equals(selectedType[0])
                            || Entity.TYPE_PLACE.equals(selectedType[0]);
                    extraLabel.setVisibility(showExtra ? View.VISIBLE : View.GONE);
                    extraField.setVisibility(showExtra ? View.VISIBLE : View.GONE);
                    if (idField.getText().toString().trim().isEmpty()
                            || idField.getText().toString().startsWith(selectedType[0] + "_")) {
                        idField.setText(EntityStore.suggestId(selectedType[0],
                                nameField.getText().toString()));
                    }
                });
            }
        }

        layout.addView(fieldLabel("Identifiant"));
        layout.addView(idField);
        layout.addView(fieldLabel("Nom"));
        layout.addView(nameField);
        layout.addView(fieldLabel("Alias"));
        layout.addView(aliasesField);
        boolean showExtra = Entity.TYPE_PERSON.equals(selectedType[0])
                || Entity.TYPE_PROJECT.equals(selectedType[0])
                || Entity.TYPE_PLACE.equals(selectedType[0]);
        if (showExtra) {
            layout.addView(extraLabel);
            layout.addView(extraField);
        }
        layout.addView(fieldLabel("Faits"));
        layout.addView(factsField);

        nameField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (!editing) {
                    idField.setText(EntityStore.suggestId(selectedType[0], s.toString()));
                }
            }
        });

        darkDialog()
                .setTitle(editing ? "Modifier la fiche" : "Nouvelle fiche")
                .setView(layout)
                .setPositiveButton("Enregistrer", (d, w) -> saveEntityFromDialog(
                        existing, selectedType[0], idField, nameField, aliasesField,
                        extraField, factsField))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void saveEntityFromDialog(Entity existing, String entityType,
            EditText idField, EditText nameField, EditText aliasesField,
            EditText extraField, EditText factsField) {
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            toast("Le nom est obligatoire.");
            return;
        }
        String id = idField.getText().toString().trim();
        if (id.isEmpty()) id = EntityStore.suggestId(entityType, name);
        if (existing == null && store.findById(id) != null) {
            toast("Cet identifiant existe déjà.");
            return;
        }
        try {
            Entity saved = Entity.fromForm(
                    id,
                    entityType,
                    name,
                    parseLines(aliasesField.getText().toString()),
                    extraField.getText().toString().trim(),
                    parseLines(factsField.getText().toString()));
            store.upsert(saved);
            refreshTab();
            toast(existing != null ? "Fiche mise à jour." : "Fiche ajoutée.");
        } catch (Exception ex) {
            toast("Erreur : " + ex.getMessage());
        }
    }

    private TextView makeTypeChip(LinearLayout row, String label, String typeValue,
            String[] selectedType, List<TextView> allChips) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setTag(typeValue);
        styleChip(chip, typeValue.equals(selectedType[0]));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        lp.bottomMargin = dp(6);
        row.addView(chip, lp);
        return chip;
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(selected ? Color.parseColor("#2835D0DD") : Color.parseColor("#14FFFFFF"));
        if (selected) bg.setStroke(dp(1), Color.parseColor("#35D0DD"));
        chip.setBackground(bg);
        chip.setTextColor(Color.WHITE);
    }

    private void confirmDelete(Entity e) {
        darkDialog()
                .setTitle("Supprimer « " + e.name + " » ?")
                .setMessage("Pégase ne pourra plus résoudre les alias de cette fiche.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    store.remove(e.id);
                    refreshTab();
                    toast("Fiche supprimée.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ── UI helpers (alignés sur MemorySettingsActivity) ───────────────────────

    private View makeTabButton(LinearLayout row, String label, int tabIndex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(tabIndex == TAB_ALL ? 13 : 15);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));
        btn.setOnClickListener(v -> {
            activeTab = tabIndex;
            refreshTab();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = tabIndex < 4 ? dp(4) : 0;
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
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#66FFFFFF"));
        tv.setTextSize(11);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
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
        lp.topMargin = dp(6);
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
        if (minLines == 1) field.setSingleLine(true);
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

    private View spacerV(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, height));
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

    private static String joinAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < aliases.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('«').append(aliases.get(i)).append('»');
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
}
