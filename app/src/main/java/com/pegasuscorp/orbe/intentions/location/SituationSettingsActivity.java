package com.pegasuscorp.orbe.intentions.location;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.intentions.PegaseModeStore;
import com.pegasuscorp.orbe.permissions.PermissionFlow;

import java.util.List;
import java.util.Locale;

/**
 * Réglages localisation, lieux nommés et conduite automatique (P6 v3).
 */
public class SituationSettingsActivity extends AppCompatActivity {

    private float density;
    private LinearLayout contentHost;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("Localisation & conduite");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Lieux, vitesse GPS, mode conduite automatique");
        subtitle.setTextColor(Color.parseColor("#88FFFFFF"));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

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
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionFlow.REQ_LOCATION
                && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LocationSituationBootstrap.ensureStarted(this);
            rebuild();
        }
    }

    private void rebuild() {
        contentHost.removeAllViews();

        addSectionTitle("État actuel");
        statusView = addHint(statusLine());
        addActionRow("Actualiser la position GPS", () -> {
            if (!PermissionFlow.ensureLocation(this)) {
                Toast.makeText(this, "Autorise la localisation", Toast.LENGTH_LONG).show();
                return;
            }
            LocationSituationBootstrap.ensureStarted(this);
            LocationSituationTracker.evaluate(this);
            if (statusView != null) statusView.setText(statusLine());
            Toast.makeText(this, "Position mise à jour", Toast.LENGTH_SHORT).show();
        });

        addSectionTitle("Conduite automatique");
        addSwitchRow("Activer la conduite auto (vitesse GPS)",
                LocationSituationPrefs.isAutoDriveEnabled(this),
                on -> LocationSituationPrefs.setAutoDriveEnabled(this, on));
        addSwitchRow("Masquer l'orbe copilote en auto-conduite",
                LocationSituationPrefs.hideCopilotOnAutoDrive(this),
                on -> LocationSituationPrefs.setHideCopilotOnAutoDrive(this, on));
        addValueRow("Seuil d'entrée (km/h)",
                String.format(Locale.FRANCE, "%.0f", LocationSituationPrefs.getDriveEnterKmh(this)),
                () -> editFloat("Seuil d'entrée conduite (km/h)", 5, 200,
                        LocationSituationPrefs.getDriveEnterKmh(this), v -> {
                            LocationSituationPrefs.setDriveEnterKmh(this, v);
                            rebuild();
                        }));
        addValueRow("Seuil de sortie (km/h)",
                String.format(Locale.FRANCE, "%.0f", LocationSituationPrefs.getDriveExitKmh(this)),
                () -> editFloat("Seuil de sortie conduite (km/h)", 5, 200,
                        LocationSituationPrefs.getDriveExitKmh(this), v -> {
                            LocationSituationPrefs.setDriveExitKmh(this, v);
                            rebuild();
                        }));
        addValueRow("Âge max vitesse GPS (minutes)",
                String.valueOf(LocationSituationPrefs.getSpeedMaxAgeMinutes(this)),
                () -> editInt("Âge max relevé vitesse (min)", 1, 120,
                        LocationSituationPrefs.getSpeedMaxAgeMinutes(this), v -> {
                            LocationSituationPrefs.setSpeedMaxAgeMinutes(this, v);
                            rebuild();
                        }));
        addValueRow("Mode Pégase actuel", modeLabel(), null);

        addSectionTitle("Rayon des lieux");
        addValueRow("Rayon par défaut (mètres)",
                String.format(Locale.FRANCE, "%.0f", LocationSituationPrefs.getDefaultRadiusM(this)),
                () -> editFloat("Rayon par défaut (m)", 30, 2000,
                        LocationSituationPrefs.getDefaultRadiusM(this), v -> {
                            LocationSituationPrefs.setDefaultRadiusM(this, v);
                            rebuild();
                        }));

        addSectionTitle("Lieux enregistrés");
        addHint("Maison, travail, restaurant… utilisés pour le contexte « Lieu : … ».");
        SavedPlaceStore store = SavedPlaceStore.getInstance(this);
        List<SavedPlace> places = store.listAll();
        if (places.isEmpty()) {
            addHint("Aucun lieu — ajoute-en un ci-dessous.");
        } else {
            for (SavedPlace p : places) {
                String coords = String.format(Locale.FRANCE, "%.5f, %.5f", p.lat, p.lon);
                addValueRow(placeTitle(p), p.label + " · " + coords + " · "
                                + (int) p.radiusM + " m",
                        () -> editPlace(p));
            }
        }
        addActionRow("Définir maison (position actuelle)",
                () -> saveFromGps(SavedPlace.Type.HOME, "Maison"));
        addActionRow("Définir travail (position actuelle)",
                () -> saveFromGps(SavedPlace.Type.WORK, "Travail"));
        addActionRow("Ajouter un restaurant (position actuelle)",
                () -> promptLabelThenGps(SavedPlace.Type.RESTAURANT, "Restaurant"));
        addActionRow("Ajouter un lieu libre (position actuelle)",
                () -> promptLabelThenGps(SavedPlace.Type.OTHER, "Lieu"));
        addActionRow("Importer depuis coordonnées météo",
                this::importFromWeatherCoords);
        addActionRow("Effacer tous les lieux", () -> new MaterialAlertDialogBuilder(this)
                .setTitle("Effacer les lieux ?")
                .setMessage("Maison, travail et autres zones seront supprimés.")
                .setPositiveButton("Effacer", (d, w) -> {
                    store.clearAll();
                    LocationSituationReader.setCurrentPlace(this, null);
                    rebuild();
                })
                .setNegativeButton("Annuler", null)
                .show());
    }

    private String statusLine() {
        LocationSituationReader.Snapshot snap = LocationSituationReader.read(this);
        StringBuilder sb = new StringBuilder();
        if (!snap.hasCoords) {
            sb.append("Position : indisponible");
        } else {
            sb.append(String.format(Locale.FRANCE,
                    "Position : %.5f, %.5f", snap.lat, snap.lon));
            float kmh = snap.effectiveSpeedKmh(this, System.currentTimeMillis());
            sb.append(String.format(Locale.FRANCE, "\nVitesse : %.0f km/h", kmh));
        }
        String place = LocationSituationReader.getCurrentPlaceLabel(this);
        if (place != null && !place.isEmpty()) {
            sb.append("\nZone : ").append(place);
        }
        sb.append("\nMode : ").append(modeLabel());
        if (PegaseModeStore.isAutoDriveActive(this)) {
            sb.append(" (auto)");
        }
        return sb.toString();
    }

    private static String placeTitle(SavedPlace p) {
        switch (p.type) {
            case HOME: return "Maison";
            case WORK: return "Travail";
            case RESTAURANT: return "Restaurant";
            default: return "Lieu";
        }
    }

    private String modeLabel() {
        PegaseModeStore.Mode m = PegaseModeStore.getMode(this);
        if (m == PegaseModeStore.Mode.DRIVE) return "Conduite";
        if (m == PegaseModeStore.Mode.WORK) return "Travail";
        return "Normal";
    }

    private void saveFromGps(SavedPlace.Type type, String defaultLabel) {
        if (!ensureGps()) return;
        float radius = LocationSituationPrefs.getDefaultRadiusM(this);
        LocationSituationReader.Snapshot snap = LocationSituationReader.read(this);
        SavedPlaceStore.getInstance(this).upsert(type, defaultLabel, snap.lat, snap.lon, radius);
        LocationSituationTracker.evaluate(this);
        Toast.makeText(this, defaultLabel + " enregistré", Toast.LENGTH_SHORT).show();
        rebuild();
    }

    private void promptLabelThenGps(SavedPlace.Type type, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Nom du lieu")
                .setView(input)
                .setPositiveButton("Suivant", (d, w) -> {
                    String label = input.getText() != null ? input.getText().toString().trim() : hint;
                    if (!ensureGps()) return;
                    float radius = LocationSituationPrefs.getDefaultRadiusM(this);
                    LocationSituationReader.Snapshot snap = LocationSituationReader.read(this);
                    SavedPlaceStore.getInstance(this).addPlace(type, label, snap.lat, snap.lon, radius);
                    LocationSituationTracker.evaluate(this);
                    Toast.makeText(this, label + " enregistré", Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void importFromWeatherCoords() {
        double[] coords = LocationSituationReader.parseCoords(ApiKeyStore.getUserCoords(this));
        if (coords == null) {
            Toast.makeText(this, "Coordonnées météo vides (tiroir → API)", Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Importer coordonnées météo")
                .setItems(new String[]{"Maison", "Travail", "Restaurant", "Lieu libre"}, (d, which) -> {
                    SavedPlace.Type type;
                    String label;
                    switch (which) {
                        case 0: type = SavedPlace.Type.HOME; label = "Maison"; break;
                        case 1: type = SavedPlace.Type.WORK; label = "Travail"; break;
                        case 2: type = SavedPlace.Type.RESTAURANT; label = "Restaurant"; break;
                        default: type = SavedPlace.Type.OTHER; label = "Lieu"; break;
                    }
                    float radius = LocationSituationPrefs.getDefaultRadiusM(this);
                    SavedPlaceStore store = SavedPlaceStore.getInstance(this);
                    if (type == SavedPlace.Type.HOME || type == SavedPlace.Type.WORK) {
                        store.upsert(type, label, coords[0], coords[1], radius);
                    } else {
                        store.addPlace(type, label, coords[0], coords[1], radius);
                    }
                    LocationSituationReader.persist(this, coords[0], coords[1], 0f);
                    LocationSituationTracker.evaluate(this);
                    Toast.makeText(this, label + " importé", Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .show();
    }

    private void editPlace(SavedPlace p) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText label = field("Nom", p.label);
        EditText lat = field("Latitude", String.format(Locale.US, "%.6f", p.lat));
        lat.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText lon = field("Longitude", String.format(Locale.US, "%.6f", p.lon));
        lon.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText radius = field("Rayon (m)", String.valueOf((int) p.radiusM));
        radius.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(label);
        form.addView(lat);
        form.addView(lon);
        form.addView(radius);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Modifier " + placeTitle(p))
                .setView(form)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    try {
                        double la = Double.parseDouble(text(lat));
                        double lo = Double.parseDouble(text(lon));
                        float r = Float.parseFloat(text(radius));
                        String lb = text(label);
                        SavedPlaceStore store = SavedPlaceStore.getInstance(this);
                        store.removeById(p.id);
                        if (p.type == SavedPlace.Type.HOME || p.type == SavedPlace.Type.WORK) {
                            store.upsert(p.type, lb, la, lo, r);
                        } else {
                            store.addPlace(p.type, lb, la, lo, r);
                        }
                        LocationSituationTracker.evaluate(this);
                        rebuild();
                    } catch (Exception e) {
                        Toast.makeText(this, "Valeurs invalides", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Supprimer", (d, w) -> {
                    SavedPlaceStore.getInstance(this).removeById(p.id);
                    LocationSituationTracker.evaluate(this);
                    rebuild();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private boolean ensureGps() {
        if (!PermissionFlow.ensureLocation(this)) {
            Toast.makeText(this, "Autorise la localisation", Toast.LENGTH_LONG).show();
            return false;
        }
        LocationSituationBootstrap.ensureStarted(this);
        LocationSituationReader.Snapshot snap = LocationSituationReader.read(this);
        if (!snap.hasCoords) {
            Toast.makeText(this, "GPS indisponible — attends quelques secondes",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private interface FloatSetter {
        void set(float v);
    }

    private interface IntSetter {
        void set(int v);
    }

    private void editFloat(String title, float min, float max, float current, FloatSetter setter) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.0f", current));
        input.setTextColor(Color.WHITE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        float v = Float.parseFloat(text(input));
                        if (v < min || v > max) throw new NumberFormatException();
                        setter.set(v);
                    } catch (Exception e) {
                        Toast.makeText(this, "Valeur invalide", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void editInt(String title, int min, int max, int current, IntSetter setter) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setTextColor(Color.WHITE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int v = Integer.parseInt(text(input));
                        if (v < min || v > max) throw new NumberFormatException();
                        setter.set(v);
                    } catch (Exception e) {
                        Toast.makeText(this, "Valeur invalide", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.parseColor("#66FFFFFF"));
        e.setPadding(0, dp(6), 0, dp(6));
        return e;
    }

    private static String text(EditText e) {
        return e.getText() != null ? e.getText().toString().trim() : "";
    }

    private void addSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#35D0DD"));
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(6));
        contentHost.addView(tv, matchWrap());
    }

    private TextView addHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#88FFFFFF"));
        tv.setTextSize(12);
        tv.setPadding(0, 0, 0, dp(8));
        contentHost.addView(tv, matchWrap());
        return tv;
    }

    private void addSwitchRow(String label, boolean checked, SwitchListener listener) {
        LinearLayout row = cardRow();
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        row.addView(tv, flex());
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> listener.onChanged(on));
        row.addView(sw);
        contentHost.addView(row, rowLp());
    }

    private void addValueRow(String label, String value, Runnable onClick) {
        LinearLayout row = cardRow();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        col.addView(t);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.parseColor("#88FFFFFF"));
        v.setTextSize(11);
        v.setPadding(0, dp(2), 0, 0);
        col.addView(v);
        row.addView(col, flex());
        if (onClick != null) {
            row.setOnClickListener(x -> onClick.run());
        }
        contentHost.addView(row, rowLp());
    }

    private void addActionRow(String label, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#35D0DD"));
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackground(cardBg());
        tv.setOnClickListener(v -> action.run());
        contentHost.addView(tv, rowLp());
    }

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(cardBg());
        return row;
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#14FFFFFF"));
        d.setCornerRadius(12 * density);
        return d;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(6);
        return lp;
    }

    private LinearLayout.LayoutParams flex() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private interface SwitchListener {
        void onChanged(boolean on);
    }
}
