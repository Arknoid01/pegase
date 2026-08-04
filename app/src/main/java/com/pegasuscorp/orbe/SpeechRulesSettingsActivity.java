package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
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
import com.pegasuscorp.orbe.voice.SpeechRulesStore;

import java.util.List;

/**
 * Éditeur du dictionnaire vocal Piper/TTS : prononciation, remplacements, abréviations.
 */
public class SpeechRulesSettingsActivity extends AppCompatActivity {

    private static final int TAB_DICT = 0;
    private static final int TAB_REPLACE = 1;
    private static final int TAB_EXPAND = 2;

    private static final int FIELD_BG = Color.parseColor("#FF252830");

    private float density;
    private LinearLayout contentHost;
    private final View[] tabButtons = new View[3];
    private int activeTab = TAB_DICT;
    private SpeechRulesStore rules;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rules = SpeechRulesStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Dictionnaire vocal");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Prononciation · Remplacements · Abréviations — modifiables aussi à la voix");
        subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(10));
        root.addView(tabRow, matchWrap());

        tabButtons[TAB_DICT] = makeTabButton(tabRow, "Prononcer", TAB_DICT);
        tabButtons[TAB_REPLACE] = makeTabButton(tabRow, "Remplacer", TAB_REPLACE);
        tabButtons[TAB_EXPAND] = makeTabButton(tabRow, "Épeler", TAB_EXPAND);

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
        selectTab(TAB_DICT);
    }

    private void selectTab(int tab) {
        activeTab = tab;
        for (int i = 0; i < tabButtons.length; i++) {
            styleTab(tabButtons[i], i == tab);
        }
        contentHost.removeAllViews();
        switch (tab) {
            case TAB_DICT:
                buildDictTab();
                break;
            case TAB_REPLACE:
                buildReplaceTab();
                break;
            case TAB_EXPAND:
                buildExpandTab();
                break;
            default:
                break;
        }
    }

    private void buildDictTab() {
        contentHost.addView(hint(
                "Mot → prononciation phonétique pour Piper.\n"
                        + "À la voix : « dis Qwen comme Couène », « prononce GitHub comme Guite Hub »."));
        addActionButton("+ Ajouter une prononciation", () ->
                showRuleDialog("Nouvelle prononciation", "", "",
                        "Mot ou nom (ex: Qwen)", "Se prononce (ex: Couène)",
                        (word, value) -> {
                            if (word.contains(" ")) {
                                if (!rules.putReplace(word, value)) {
                                    toast("Échec d'enregistrement.");
                                    return;
                                }
                                selectTab(TAB_REPLACE);
                                toast("Phrase enregistrée dans Remplacer.");
                                return;
                            }
                            if (SpeechRulesStore.isBlockedDictionaryKey(word)) {
                                toast("Mot trop courant — choisis un nom propre, ou l'onglet Remplacer pour une phrase (ex: dis moi).");
                                return;
                            }
                            if (!rules.putDictionary(word, value)) {
                                toast("Échec d'enregistrement.");
                                return;
                            }
                            selectTab(TAB_DICT);
                            toast("Prononciation enregistrée.");
                        }));

        List<SpeechRulesStore.RuleEntry> entries = rules.listDictionary();
        if (entries.isEmpty()) {
            contentHost.addView(emptyLabel("Aucune règle de prononciation pour l'instant."));
            return;
        }
        for (SpeechRulesStore.RuleEntry e : entries) {
            contentHost.addView(ruleCard(e, TAB_DICT));
        }
    }

    private void buildReplaceTab() {
        contentHost.addView(hint(
                "Remplace un mot par un autre avant la synthèse vocale.\n"
                        + "Ex : apps → applis, wifi → wai faï."));
        addActionButton("+ Ajouter un remplacement", () ->
                showRuleDialog("Nouveau remplacement", "", "",
                        "Mot d'origine", "Remplacer par",
                        (word, value) -> {
                            if (!rules.putReplace(word, value)) {
                                toast("Échec d'enregistrement.");
                                return;
                            }
                            selectTab(TAB_REPLACE);
                            toast("Remplacement enregistré.");
                        }));

        List<SpeechRulesStore.RuleEntry> entries = rules.listReplace();
        if (entries.isEmpty()) {
            contentHost.addView(emptyLabel("Aucun remplacement personnalisé."));
            return;
        }
        for (SpeechRulesStore.RuleEntry e : entries) {
            contentHost.addView(ruleCard(e, TAB_REPLACE));
        }
    }

    private void buildExpandTab() {
        contentHost.addView(hint(
                "Épelle les sigles et acronymes lettre par lettre.\n"
                        + "Ex : API → a p i, SMS → ess em esse."));
        addActionButton("+ Ajouter une épellation", () ->
                showRuleDialog("Nouvelle épellation", "", "",
                        "Sigle (ex: API)", "Épellation (ex: a p i)",
                        (word, value) -> {
                            if (!rules.putExpand(word, value)) {
                                toast("Échec d'enregistrement.");
                                return;
                            }
                            selectTab(TAB_EXPAND);
                            toast("Épellation enregistrée.");
                        }));

        List<SpeechRulesStore.RuleEntry> entries = rules.listExpand();
        if (entries.isEmpty()) {
            contentHost.addView(emptyLabel("Aucune règle d'épellation."));
            return;
        }
        for (SpeechRulesStore.RuleEntry e : entries) {
            contentHost.addView(ruleCard(e, TAB_EXPAND));
        }
    }

    private View ruleCard(SpeechRulesStore.RuleEntry e, int tab) {
        LinearLayout card = cardContainer();

        TextView word = new TextView(this);
        word.setText(e.word);
        word.setTextColor(Color.WHITE);
        word.setTextSize(15);
        word.setTypeface(null, Typeface.BOLD);
        card.addView(word);

        TextView value = new TextView(this);
        value.setText("→ " + e.value);
        value.setTextColor(Color.parseColor("#CCFFFFFF"));
        value.setTextSize(14);
        value.setPadding(0, dp(4), 0, dp(8));
        card.addView(value);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton("Modifier", () -> showRuleDialog(
                "Modifier", e.word, e.value,
                "Mot", "Valeur",
                (wordNew, valNew) -> {
                    if (tab == TAB_DICT && !wordNew.contains(" ")
                            && SpeechRulesStore.isBlockedDictionaryKey(wordNew)) {
                        toast("Mot trop courant — utilise Remplacer pour une phrase.");
                        return;
                    }
                    removeEntry(tab, e.word);
                    if (!putEntry(tab, wordNew, valNew)) {
                        toast("Échec d'enregistrement.");
                        selectTab(tab);
                        return;
                    }
                    selectTab(tab == TAB_DICT && wordNew.contains(" ") ? TAB_REPLACE : tab);
                    toast("Règle mise à jour.");
                })));
        actions.addView(spacerH(dp(8)));
        actions.addView(smallButton("Supprimer", () -> confirmDelete(tab, e.word)));
        card.addView(actions);
        return card;
    }

    private void showRuleDialog(String title, String word, String value,
                                String wordHint, String valueHint, RuleSaveCallback onSave) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.setBackgroundColor(Color.parseColor("#FF1A1D24"));

        EditText wordField = field(word, wordHint, 1);
        layout.addView(wordField);
        layout.addView(spacerV(dp(8)));
        EditText valueField = field(value, valueHint, 2);
        layout.addView(valueField);

        darkDialog()
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String wv = wordField.getText().toString().trim();
                    String vv = valueField.getText().toString().trim();
                    if (wv.isEmpty() || vv.isEmpty()) {
                        toast("Les deux champs sont obligatoires.");
                        return;
                    }
                    onSave.onSave(wv, vv);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDelete(int tab, String word) {
        darkDialog()
                .setTitle("Supprimer cette règle ?")
                .setMessage("« " + word + " » ne sera plus modifié à la voix.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    removeEntry(tab, word);
                    selectTab(tab);
                    toast("Règle supprimée.");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private boolean putEntry(int tab, String word, String value) {
        switch (tab) {
            case TAB_DICT: return rules.putDictionary(word, value);
            case TAB_REPLACE: return rules.putReplace(word, value);
            case TAB_EXPAND: return rules.putExpand(word, value);
            default: return false;
        }
    }

    private void removeEntry(int tab, String word) {
        switch (tab) {
            case TAB_DICT: rules.removeDictionary(word); break;
            case TAB_REPLACE: rules.removeReplace(word); break;
            case TAB_EXPAND: rules.removeExpand(word); break;
            default: break;
        }
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private MaterialAlertDialogBuilder darkDialog() {
        return new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog);
    }

    private EditText field(String value, String hint, int minLines) {
        EditText f = new EditText(this);
        f.setText(value);
        f.setHint(hint);
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(Color.parseColor("#88FFFFFF"));
        f.setBackgroundColor(FIELD_BG);
        f.setPadding(dp(12), dp(10), dp(12), dp(10));
        f.setMinLines(minLines);
        f.setGravity(Gravity.TOP);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return f;
    }

    private View makeTabButton(LinearLayout row, String label, int tabIndex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        btn.setOnClickListener(v -> selectTab(tabIndex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    private View spacerH(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private View spacerV(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
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

    private interface RuleSaveCallback {
        void onSave(String word, String value);
    }
}
