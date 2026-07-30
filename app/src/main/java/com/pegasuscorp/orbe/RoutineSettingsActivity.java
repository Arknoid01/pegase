package com.pegasuscorp.orbe;

import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.f1companion.FavoriteTeamsStore;
import com.pegasuscorp.orbe.f1companion.F1LiveScheduler;
import com.pegasuscorp.orbe.f1companion.F1LiveStore;
import com.pegasuscorp.orbe.f1companion.F1NewsScheduler;
import com.pegasuscorp.orbe.intentions.IntentionIds;
import com.pegasuscorp.orbe.intentions.IntentionPrefs;
import com.pegasuscorp.orbe.intentions.PegaseModeStore;
import com.pegasuscorp.orbe.learning.LearningCandidate;
import com.pegasuscorp.orbe.learning.LearningCandidateStore;
import com.pegasuscorp.orbe.learning.LearningEngine;
import com.pegasuscorp.orbe.learning.LearningFeedback;
import com.pegasuscorp.orbe.learning.ObservationStore;
import com.pegasuscorp.orbe.life.LifePatternStore;
import com.pegasuscorp.orbe.objects.ProjectObjectStore;
import com.pegasuscorp.orbe.permissions.PermissionFlow;
import com.pegasuscorp.orbe.prefetch.PrefetchScheduler;
import com.pegasuscorp.orbe.routines.CustomRoutineStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Liste des routines custom du brief du matin — toggle actif + supprimer.
 */
public class RoutineSettingsActivity extends AppCompatActivity {

    private float density;
    private LinearLayout listHost;
    private CustomRoutineStore store;
    private View alarmTimeRow;
    private TextView alarmTimeLabel;
    private TextView intentionsQuietHint;
    private TextView intentionsQuietRow;
    private LinearLayout learningHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = CustomRoutineStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Pégase · réglages");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Brief · intentions · rythmes · apprentissages");
        subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        root.addView(subtitle);

        alarmTimeRow = buildAlarmTimeRow();
        root.addView(alarmTimeRow, rowLp());
        root.addView(buildIntentionsSection(), rowLp());
        root.addView(buildF1NewsSection(), rowLp());
        root.addView(buildLifePatternsSection(), rowLp());
        learningHost = new LinearLayout(this);
        learningHost.setOrientation(LinearLayout.VERTICAL);
        refreshLearningSection();
        root.addView(learningHost, rowLp());
        root.addView(buildProjectObjectsSection(), rowLp());

        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listHost, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("Voix : « ajoute à ma routine du matin : cherche les résultats F1 »");
        hint.setTextColor(Color.parseColor("#66FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(10), 0, 0);
        root.addView(hint);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(14) + bars.left, dp(14) + bars.top,
                    dp(14) + bars.right, dp(14) + bars.bottom);
            return insets;
        });

        setContentView(root);
        rebuildList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAlarmTimeLabel();
        rebuildList();
    }

    private View buildAlarmTimeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        row.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Heure du brief automatique");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        row.addView(title);

        alarmTimeLabel = new TextView(this);
        alarmTimeLabel.setTextColor(Color.parseColor("#35D0DD"));
        alarmTimeLabel.setTextSize(18);
        alarmTimeLabel.setTypeface(null, Typeface.BOLD);
        alarmTimeLabel.setPadding(0, dp(4), 0, 0);
        row.addView(alarmTimeLabel);
        refreshAlarmTimeLabel();

        TextView hint = new TextView(this);
        hint.setText("Alarme RTC quotidienne — fallback au retour sur l'accueil");
        hint.setTextColor(Color.parseColor("#66FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(4), 0, 0);
        row.addView(hint);

        row.setOnClickListener(v -> openAlarmTimePicker());
        return row;
    }

    private View buildIntentionsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        section.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Intentions Pégase");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(IntentionPrefs.isEnabled(this));
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            IntentionPrefs.setEnabled(this, checked);
            if (checked) {
                boolean already = PermissionFlow.hasNotifications(this);
                PermissionFlow.ensureNotifications(this);
                Toast.makeText(this,
                        already
                                ? "Intentions activées"
                                : "Active les notifications pour les suggestions Pégase",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Intentions coupées", Toast.LENGTH_SHORT).show();
            }
        });
        top.addView(toggle);
        section.addView(top);

        intentionsQuietHint = new TextView(this);
        intentionsQuietHint.setText(quietHoursHintText());
        intentionsQuietHint.setTextColor(Color.parseColor("#88FFFFFF"));
        intentionsQuietHint.setTextSize(11);
        intentionsQuietHint.setPadding(0, dp(6), 0, 0);
        section.addView(intentionsQuietHint);

        TextView wifiRow = new TextView(this);
        String ssid = IntentionPrefs.getWorkWifiSsid(this);
        wifiRow.setText("Wi‑Fi du lieu de travail : "
                + (ssid.isEmpty() ? "(non défini)" : ssid));
        wifiRow.setTextColor(Color.parseColor("#35D0DD"));
        wifiRow.setTextSize(13);
        wifiRow.setPadding(0, dp(10), 0, 0);
        wifiRow.setOnClickListener(v -> editWorkWifi(wifiRow));
        section.addView(wifiRow);

        TextView carRow = new TextView(this);
        String car = IntentionPrefs.getCarBtName(this);
        carRow.setText("Bluetooth voiture : "
                + (car.isEmpty() ? "(auto : nom « voiture/car/… »)" : car));
        carRow.setTextColor(Color.parseColor("#35D0DD"));
        carRow.setTextSize(13);
        carRow.setPadding(0, dp(8), 0, 0);
        carRow.setOnClickListener(v -> editCarBt(carRow));
        section.addView(carRow);

        TextView destRow = new TextView(this);
        String dest = IntentionPrefs.getDriveDestination(this);
        destRow.setText("Navigation conduite : "
                + (dest.isEmpty() ? "(non définie)" : dest));
        destRow.setTextColor(Color.parseColor("#35D0DD"));
        destRow.setTextSize(13);
        destRow.setPadding(0, dp(8), 0, 0);
        destRow.setOnClickListener(v -> editDriveDestination(destRow));
        section.addView(destRow);

        TextView spotifyRow = new TextView(this);
        String sq = IntentionPrefs.getDriveSpotifyQuery(this);
        spotifyRow.setText("Spotify conduite : "
                + (sq.isEmpty() ? "(reprise lecture)" : sq));
        spotifyRow.setTextColor(Color.parseColor("#35D0DD"));
        spotifyRow.setTextSize(13);
        spotifyRow.setPadding(0, dp(8), 0, 0);
        spotifyRow.setOnClickListener(v -> editDriveSpotify(spotifyRow));
        section.addView(spotifyRow);

        intentionsQuietRow = new TextView(this);
        intentionsQuietRow.setText(quietHoursRowText());
        intentionsQuietRow.setTextColor(Color.parseColor("#35D0DD"));
        intentionsQuietRow.setTextSize(13);
        intentionsQuietRow.setPadding(0, dp(8), 0, 0);
        intentionsQuietRow.setOnClickListener(v -> editQuietHours());
        section.addView(intentionsQuietRow);

        TextView modeRow = new TextView(this);
        modeRow.setText("Mode actuel : "
                + modeLabel()
                + " (appuie pour changer)");
        modeRow.setTextColor(Color.parseColor("#AAFFFFFF"));
        modeRow.setTextSize(12);
        modeRow.setPadding(0, dp(8), 0, 0);
        modeRow.setOnClickListener(v -> {
            PegaseModeStore.Mode cur = PegaseModeStore.getMode(this);
            PegaseModeStore.Mode next = cur == PegaseModeStore.Mode.NORMAL
                    ? PegaseModeStore.Mode.WORK
                    : cur == PegaseModeStore.Mode.WORK
                    ? PegaseModeStore.Mode.DRIVE
                    : PegaseModeStore.Mode.NORMAL;
            PegaseModeStore.setMode(this, next);
            modeRow.setText("Mode actuel : " + modeLabel() + " (tap = cycle)");
            Toast.makeText(this, modeLabel(), Toast.LENGTH_SHORT).show();
        });
        section.addView(modeRow);

        TextView suppressRow = new TextView(this);
        suppressRow.setText("Réactiver une suggestion…");
        suppressRow.setTextColor(Color.parseColor("#35D0DD"));
        suppressRow.setTextSize(13);
        suppressRow.setPadding(0, dp(8), 0, 0);
        suppressRow.setOnClickListener(v -> showUnsuppressPicker());
        section.addView(suppressRow);

        return section;
    }

    private View buildF1NewsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        section.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Actus F1 (RSS)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(FavoriteTeamsStore.isNewsEnabled(this));
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            FavoriteTeamsStore.setNewsEnabled(this, checked);
            if (checked) {
                try { F1NewsScheduler.ensureScheduled(this); } catch (Exception ignored) {}
            }
            Toast.makeText(this, checked ? "Actus F1 activées" : "Actus F1 coupées",
                    Toast.LENGTH_SHORT).show();
        });
        top.addView(toggle);
        section.addView(top);

        TextView hint = new TextView(this);
        hint.setText("Une notif max quand une actu touche tes écuries (Autosport / Motorsport).");
        hint.setTextColor(Color.parseColor("#88FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(6), 0, 0);
        section.addView(hint);

        TextView teamsRow = new TextView(this);
        teamsRow.setText("Équipes : " + FavoriteTeamsStore.summaryLabel(this));
        teamsRow.setTextColor(Color.parseColor("#35D0DD"));
        teamsRow.setTextSize(13);
        teamsRow.setPadding(0, dp(10), 0, 0);
        teamsRow.setOnClickListener(v -> editFavoriteTeams(teamsRow));
        section.addView(teamsRow);

        LinearLayout liveTop = new LinearLayout(this);
        liveTop.setOrientation(LinearLayout.HORIZONTAL);
        liveTop.setGravity(Gravity.CENTER_VERTICAL);
        liveTop.setPadding(0, dp(12), 0, 0);

        TextView liveTitle = new TextView(this);
        liveTitle.setText("Alertes live GP");
        liveTitle.setTextColor(Color.WHITE);
        liveTitle.setTextSize(14);
        liveTop.addView(liveTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch liveToggle = new Switch(this);
        liveToggle.setChecked(F1LiveStore.isEnabled(this));
        liveToggle.setOnCheckedChangeListener((btn, checked) -> {
            F1LiveStore.setEnabled(this, checked);
            if (checked) {
                try { F1LiveScheduler.ensureScheduled(this); } catch (Exception ignored) {}
            }
            Toast.makeText(this, checked ? "Live F1 activé" : "Live F1 coupé",
                    Toast.LENGTH_SHORT).show();
        });
        liveTop.addView(liveToggle);
        section.addView(liveTop);

        TextView liveHint = new TextView(this);
        liveHint.setText("Pendant la course : Safety Car, VSC, pénalités de tes écuries, fin de GP.");
        liveHint.setTextColor(Color.parseColor("#88FFFFFF"));
        liveHint.setTextSize(11);
        liveHint.setPadding(0, dp(4), 0, 0);
        section.addView(liveHint);

        TextView memRow = new TextView(this);
        memRow.setText("Mémoire fan : "
                + com.pegasuscorp.orbe.f1companion.F1MemoryStore.load(this).summaryLine());
        memRow.setTextColor(Color.parseColor("#35D0DD"));
        memRow.setTextSize(13);
        memRow.setPadding(0, dp(12), 0, 0);
        memRow.setOnClickListener(v -> showFanMemoryDialog(memRow));
        section.addView(memRow);

        return section;
    }

    private void showFanMemoryDialog(TextView label) {
        com.pegasuscorp.orbe.f1companion.F1FanMemory mem =
                com.pegasuscorp.orbe.f1companion.F1MemoryStore.load(this);
        String body = mem.isEmpty()
                ? "Vide pour l’instant.\nVoix : « souviens-toi que… » / « mon pronostic… »"
                : mem.toMarkdown(FavoriteTeamsStore.selectedTeams(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Mémoire fan F1")
                .setMessage(body.length() > 2500 ? body.substring(0, 2497) + "…" : body)
                .setPositiveButton("OK", null)
                .setNeutralButton("Effacer", (d, w) -> {
                    com.pegasuscorp.orbe.f1companion.F1MemoryStore.clearAll(this);
                    label.setText("Mémoire fan : "
                            + com.pegasuscorp.orbe.f1companion.F1MemoryStore.load(this)
                            .summaryLine());
                    Toast.makeText(this, "Mémoire fan effacée", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void editFavoriteTeams(TextView label) {
        FavoriteTeamsStore.TeamDef[] catalog = FavoriteTeamsStore.CATALOG;
        String[] labels = new String[catalog.length];
        boolean[] checked = new boolean[catalog.length];
        for (int i = 0; i < catalog.length; i++) {
            labels[i] = catalog[i].label;
            checked[i] = FavoriteTeamsStore.isSelected(this, catalog[i].id);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Écuries suivies")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                })
                .setPositiveButton("OK", (d, w) -> {
                    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < catalog.length; i++) {
                        if (checked[i]) ids.add(catalog[i].id);
                    }
                    FavoriteTeamsStore.setSelectedIds(this, ids);
                    label.setText("Équipes : " + FavoriteTeamsStore.summaryLabel(this));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private String modeLabel() {
        PegaseModeStore.Mode m = PegaseModeStore.getMode(this);
        if (m == PegaseModeStore.Mode.DRIVE) return "Conduite";
        if (m == PegaseModeStore.Mode.WORK) return "Travail";
        return "Normal";
    }

    private void editWorkWifi(TextView label) {
        EditText input = new EditText(this);
        input.setText(IntentionPrefs.getWorkWifiSsid(this));
        input.setHint("SSID exact (ex. Boucherie-Wifi)");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Wi‑Fi du lieu de travail")
                .setMessage("Doit correspondre au nom du réseau (sensible à la casse selon l'appareil).")
                .setView(input)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String s = input.getText() != null ? input.getText().toString().trim() : "";
                    IntentionPrefs.setWorkWifiSsid(this, s);
                    label.setText("Wi‑Fi du lieu de travail : "
                            + (s.isEmpty() ? "(non défini)" : s));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void editCarBt(TextView label) {
        EditText input = new EditText(this);
        input.setText(IntentionPrefs.getCarBtName(this));
        input.setHint("ex. Voiture, BMW, fragment du nom BT");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth voiture")
                .setMessage("Sous-chaîne du nom Bluetooth. Vide = détection auto (car/voiture/…).")
                .setView(input)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String s = input.getText() != null ? input.getText().toString().trim() : "";
                    IntentionPrefs.setCarBtName(this, s);
                    label.setText("Bluetooth voiture : "
                            + (s.isEmpty() ? "(auto : nom « voiture/car/… »)" : s));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void editDriveDestination(TextView label) {
        EditText input = new EditText(this);
        input.setText(IntentionPrefs.getDriveDestination(this));
        input.setHint("ex. Maison, 12 rue…");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Navigation conduite")
                .setMessage("Ouverte au « Oui » mode conduite (Maps / Waze).")
                .setView(input)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String s = input.getText() != null ? input.getText().toString().trim() : "";
                    IntentionPrefs.setDriveDestination(this, s);
                    label.setText("Navigation conduite : "
                            + (s.isEmpty() ? "(non définie)" : s));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void editDriveSpotify(TextView label) {
        EditText input = new EditText(this);
        input.setText(IntentionPrefs.getDriveSpotifyQuery(this));
        input.setHint("vide = reprise · sinon recherche Spotify");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Spotify conduite")
                .setMessage("Recherche optionnelle au « Oui » conduite.")
                .setView(input)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String s = input.getText() != null ? input.getText().toString().trim() : "";
                    IntentionPrefs.setDriveSpotifyQuery(this, s);
                    label.setText("Spotify conduite : "
                            + (s.isEmpty() ? "(reprise lecture)" : s));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void editQuietHours() {
        int start = IntentionPrefs.getQuietStartHour(this);
        new TimePickerDialog(this, (view, h, m) -> {
            int end = IntentionPrefs.getQuietEndHour(this);
            new TimePickerDialog(this, (v2, h2, m2) -> {
                IntentionPrefs.setQuietHours(this, h, h2);
                if (intentionsQuietHint != null) {
                    intentionsQuietHint.setText(quietHoursHintText());
                }
                if (intentionsQuietRow != null) {
                    intentionsQuietRow.setText(quietHoursRowText());
                }
                Toast.makeText(this, String.format(Locale.FRANCE,
                                "Heures calmes %02dh–%02dh", h, h2),
                        Toast.LENGTH_SHORT).show();
            }, end, 0, true).show();
        }, start, 0, true).show();
    }

    private String quietHoursHintText() {
        return "Notifications rares : batterie, Wi‑Fi, brief, F1, RDV, conduite. "
                + "Heures calmes " + IntentionPrefs.getQuietStartHour(this) + "h–"
                + IntentionPrefs.getQuietEndHour(this) + "h.";
    }

    private String quietHoursRowText() {
        return String.format(Locale.FRANCE, "Heures calmes %02dh–%02dh…",
                IntentionPrefs.getQuietStartHour(this),
                IntentionPrefs.getQuietEndHour(this));
    }

    private void showUnsuppressPicker() {
        Set<String> suppressed = IntentionPrefs.getSuppressedIds(this);
        if (suppressed == null || suppressed.isEmpty()) {
            Toast.makeText(this, "Aucune suggestion désactivée", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> ids = new ArrayList<>(suppressed);
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            labels[i] = IntentionIds.displayName(ids.get(i));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Réactiver")
                .setItems(labels, (d, which) -> {
                    IntentionPrefs.unsuppress(this, ids.get(which));
                    Toast.makeText(this, "Réactivé : " + labels[which],
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private View buildLifePatternsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        section.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Ma vie / rythmes");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        section.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Créneaux locaux que Pégase connaît (ex. ménage 18h30–19h45). "
                + "Voix : « ajoute un rythme ménage de 18h30 à 19h45 ».");
        hint.setTextColor(Color.parseColor("#88FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(4), 0, dp(6));
        section.addView(hint);

        LifePatternStore store = LifePatternStore.getInstance(this);
        List<LifePatternStore.LifePattern> patterns = store.listAll();
        if (patterns.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucun rythme — tape pour en ajouter.");
            empty.setTextColor(Color.parseColor("#AAFFFFFF"));
            empty.setTextSize(13);
            empty.setPadding(0, dp(4), 0, 0);
            empty.setOnClickListener(v -> showAddLifePatternDialog());
            section.addView(empty);
        } else {
            for (LifePatternStore.LifePattern p : patterns) {
                section.addView(buildLifePatternRow(p), rowLp());
            }
        }

        TextView add = new TextView(this);
        add.setText("+ Ajouter un rythme");
        add.setTextColor(Color.parseColor("#35D0DD"));
        add.setTextSize(13);
        add.setPadding(0, dp(10), 0, 0);
        add.setOnClickListener(v -> showAddLifePatternDialog());
        section.addView(add);
        return section;
    }

    private void refreshLearningSection() {
        if (learningHost == null) return;
        learningHost.removeAllViews();
        learningHost.addView(buildLearningSection(),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View buildLearningSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        section.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Apprentissages en attente");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        section.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Hypothèses locales — rien n'est appliqué sans ton Oui.");
        hint.setTextColor(Color.parseColor("#88FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(4), 0, dp(6));
        section.addView(hint);

        int obsCount = ObservationStore.getInstance(this).countAll();
        int pendingCount = LearningCandidateStore.getInstance(this).listPending().size();
        TextView stats = new TextView(this);
        stats.setText(obsCount + " observations · " + pendingCount + " en attente");
        stats.setTextColor(Color.parseColor("#66FFFFFF"));
        stats.setTextSize(11);
        stats.setPadding(0, 0, 0, dp(6));
        section.addView(stats);

        TextView run = new TextView(this);
        run.setText("Lancer les détecteurs");
        run.setTextColor(Color.parseColor("#35D0DD"));
        run.setTextSize(13);
        run.setTypeface(null, Typeface.BOLD);
        run.setPadding(0, 0, 0, dp(8));
        run.setOnClickListener(v -> {
            LearningEngine.runDetectorsNow(this);
            Toast.makeText(this, "Détecteurs lancés", Toast.LENGTH_SHORT).show();
            refreshLearningSection();
        });
        section.addView(run);

        List<LearningCandidate> pending =
                LearningCandidateStore.getInstance(this).listPending();
        if (pending.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucune hypothèse pour l'instant.");
            empty.setTextColor(Color.parseColor("#AAFFFFFF"));
            empty.setTextSize(13);
            empty.setPadding(0, dp(4), 0, 0);
            section.addView(empty);
        } else {
            for (LearningCandidate c : pending) {
                section.addView(buildLearningRow(c), rowLp());
            }
        }
        return section;
    }

    private View buildLearningRow(LearningCandidate c) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(4), 0, dp(6));

        TextView label = new TextView(this);
        label.setText(c.title() + "\n" + c.body());
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        col.addView(label);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);

        TextView yes = actionChip("Oui", "#35D0DD");
        yes.setOnClickListener(v -> {
            LearningFeedback.accept(this, c.id);
            refreshLearningSection();
        });
        actions.addView(yes);

        TextView later = actionChip("Plus tard", "#88FFFFFF");
        later.setOnClickListener(v -> {
            LearningFeedback.snooze(this, c.id);
            refreshLearningSection();
        });
        later.setPadding(dp(14), 0, 0, 0);
        actions.addView(later);

        TextView no = actionChip("Non", "#FF8A80");
        no.setOnClickListener(v -> {
            LearningFeedback.refuse(this, c.id);
            refreshLearningSection();
        });
        no.setPadding(dp(14), 0, 0, 0);
        actions.addView(no);

        col.addView(actions);
        return col;
    }

    private TextView actionChip(String text, String colorHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(colorHex));
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private View buildLifePatternRow(LifePatternStore.LifePattern p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(p.label + "  " + p.timeLabel());
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(p.active);
        toggle.setOnCheckedChangeListener((btn, checked) ->
                LifePatternStore.getInstance(this).setActive(p.id, checked));
        row.addView(toggle);

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextColor(Color.parseColor("#FF8A80"));
        del.setTextSize(18);
        del.setPadding(dp(10), 0, 0, 0);
        del.setOnClickListener(v -> {
            LifePatternStore.getInstance(this).remove(p.id);
            recreate();
        });
        row.addView(del);
        return row;
    }

    private void showAddLifePatternDialog() {
        EditText input = new EditText(this);
        input.setHint("ménage de 18h30 à 19h45");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Nouveau rythme")
                .setMessage("Indique une plage : « label de HHhMM à HHhMM ».")
                .setView(input)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String u = input.getText() != null ? input.getText().toString().trim() : "";
                    LifePatternStore.LifePattern p =
                            LifePatternStore.getInstance(this).addFromUtterance(u);
                    if (p == null) {
                        Toast.makeText(this, "Plage horaire manquante (ex. 18h30 à 19h45)",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Rythme ajouté : " + p.label,
                                Toast.LENGTH_SHORT).show();
                        recreate();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private View buildProjectObjectsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        section.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Fiches projet");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        section.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Objets locaux (Orion + custom). Voix : « note le projet cuisine ».");
        hint.setTextColor(Color.parseColor("#88FFFFFF"));
        hint.setTextSize(11);
        hint.setPadding(0, dp(4), 0, dp(6));
        section.addView(hint);

        ProjectObjectStore store = ProjectObjectStore.getInstance(this);
        List<JSONObject> custom = store.listCustom();
        if (custom.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucune fiche custom — tape pour en ajouter.");
            empty.setTextColor(Color.parseColor("#AAFFFFFF"));
            empty.setTextSize(13);
            empty.setPadding(0, dp(4), 0, 0);
            empty.setOnClickListener(v -> showAddProjectDialog());
            section.addView(empty);
        } else {
            for (JSONObject o : custom) {
                section.addView(buildProjectRow(o), rowLp());
            }
        }

        TextView add = new TextView(this);
        add.setText("+ Ajouter une fiche");
        add.setTextColor(Color.parseColor("#35D0DD"));
        add.setTextSize(13);
        add.setPadding(0, dp(10), 0, 0);
        add.setOnClickListener(v -> showAddProjectDialog());
        section.addView(add);
        return section;
    }

    private View buildProjectRow(JSONObject o) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        String status = o.optString("status", "");
        label.setText(o.optString("label", "?")
                + (status.isEmpty() ? "" : " · " + status));
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextColor(Color.parseColor("#FF8A80"));
        del.setTextSize(18);
        del.setPadding(dp(10), 0, 0, 0);
        String id = o.optString("id", "");
        del.setOnClickListener(v -> {
            ProjectObjectStore.getInstance(this).remove(id);
            recreate();
        });
        row.addView(del);
        return row;
    }

    private void showAddProjectDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), 0);
        EditText labelIn = new EditText(this);
        labelIn.setHint("Nom du projet");
        labelIn.setTextColor(Color.WHITE);
        labelIn.setHintTextColor(Color.parseColor("#66FFFFFF"));
        box.addView(labelIn);
        EditText notesIn = new EditText(this);
        notesIn.setHint("Notes (optionnel)");
        notesIn.setTextColor(Color.WHITE);
        notesIn.setHintTextColor(Color.parseColor("#66FFFFFF"));
        box.addView(notesIn);
        EditText statusIn = new EditText(this);
        statusIn.setHint("Statut (ex. en cours)");
        statusIn.setText("actif");
        statusIn.setTextColor(Color.WHITE);
        statusIn.setHintTextColor(Color.parseColor("#66FFFFFF"));
        box.addView(statusIn);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Nouvelle fiche projet")
                .setView(box)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String label = labelIn.getText() != null
                            ? labelIn.getText().toString().trim() : "";
                    String notes = notesIn.getText() != null
                            ? notesIn.getText().toString().trim() : "";
                    String status = statusIn.getText() != null
                            ? statusIn.getText().toString().trim() : "actif";
                    String id = ProjectObjectStore.getInstance(this)
                            .upsertCustom(null, label, notes, status);
                    if (id == null) {
                        Toast.makeText(this, "Nom requis", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Fiche ajoutée", Toast.LENGTH_SHORT).show();
                        recreate();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void refreshAlarmTimeLabel() {
        if (alarmTimeLabel != null) {
            alarmTimeLabel.setText(PrefetchScheduler.formatTimeLabel(this));
        }
    }

    private void openAlarmTimePicker() {
        int hour = PrefetchScheduler.getHour(this);
        int minute = PrefetchScheduler.getMinute(this);
        new TimePickerDialog(this, (view, h, m) -> {
            PrefetchScheduler.setAlarmTime(this, h, m);
            refreshAlarmTimeLabel();
            Toast.makeText(this, "Brief programmé à "
                            + PrefetchScheduler.formatTimeLabel(this),
                    Toast.LENGTH_SHORT).show();
        }, hour, minute, true).show();
    }

    private void rebuildList() {
        listHost.removeAllViews();
        store.purgeExpired();
        List<CustomRoutineStore.CustomRoutine> all = store.listAll();
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucune routine custom.\nAjoute-en à la voix ou au prochain brief.");
            empty.setTextColor(Color.parseColor("#AAFFFFFF"));
            empty.setTextSize(14);
            empty.setPadding(0, dp(8), 0, dp(8));
            listHost.addView(empty);
            return;
        }
        for (CustomRoutineStore.CustomRoutine r : all) {
            listHost.addView(buildRow(r), rowLp());
        }
    }

    private View buildRow(CustomRoutineStore.CustomRoutine r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        row.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(r.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(label, labelLp);

        Switch toggle = new Switch(this);
        toggle.setChecked(r.active);
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            store.setActive(r.id, checked);
            Toast.makeText(this, checked ? "Activée" : "Désactivée",
                    Toast.LENGTH_SHORT).show();
        });
        top.addView(toggle);
        row.addView(top, matchWrap());

        TextView meta = new TextView(this);
        String ttl = r.ttlDays != null ? " · TTL " + r.ttlDays + " j" : "";
        meta.setText(r.type.name().toLowerCase() + ttl);
        meta.setTextColor(Color.parseColor("#88FFFFFF"));
        meta.setTextSize(12);
        meta.setPadding(0, dp(4), 0, 0);
        row.addView(meta);

        TextView del = new TextView(this);
        del.setText("Supprimer");
        del.setTextColor(Color.parseColor("#35D0DD"));
        del.setTextSize(13);
        del.setPadding(0, dp(8), 0, 0);
        del.setOnClickListener(v -> confirmDelete(r));
        row.addView(del);

        return row;
    }

    private void confirmDelete(CustomRoutineStore.CustomRoutine r) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Supprimer cette routine ?")
                .setMessage(r.label)
                .setPositiveButton("Supprimer", (d, w) -> {
                    store.remove(r.id);
                    rebuildList();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        return lp;
    }
}
