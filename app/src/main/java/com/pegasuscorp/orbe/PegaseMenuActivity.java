package com.pegasuscorp.orbe;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.memory.UserProfileStore;
import com.pegasuscorp.orbe.routines.CustomRoutineStore;
import com.pegasuscorp.orbe.sms.SmsCleanerActivity;
import com.pegasuscorp.orbe.ui.OrbeTokens;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nouveau menu principal Pégase (dashboard scrollable), ouvert depuis le tap
 * sur la phrase de l'orbe. Ne remplace pas l'écran d'accueil : c'est un écran
 * séparé au-dessus de {@link MainActivity}, qui reste inchangé.
 */
public class PegaseMenuActivity extends AppCompatActivity {

    private static final int REQ_LOCATION = 4821;
    private static final String PEGASE_SMS_PACKAGE = "com.pegasuscorp.sms";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView weatherTemp;
    private TextView weatherCity;
    private EditText inputField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(OrbeTokens.COLOR_BG);

        FluidBackgroundView background = new FluidBackgroundView(this);
        root.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        background.startStatic();

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(buildHeader());
        column.addView(buildGreetingRow(), spacedAbove(18));
        column.addView(buildOrbCard(), spacedAbove(16));
        column.addView(buildGrid(), spacedAbove(20));
        column.addView(buildTodaySection(), spacedAbove(20));
        column.addView(buildInputBar(), spacedAbove(20));
        column.addView(buildNavBar(), spacedAbove(20));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            scroll.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        setContentView(root);
        loadWeather();
    }

    // ── En-tête ──────────────────────────────────────────────────────────

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView wing = new ImageView(this);
        wing.setImageResource(R.drawable.pegasus_wings);
        wing.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(wing, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("PÉGASE");
        title.setTextColor(Color.WHITE);
        title.setTypeface(OrbeTokens.typeMedium());
        title.setLetterSpacing(0.08f);
        title.setTextSize(17f);
        titles.addView(title);

        TextView tagline = new TextView(this);
        tagline.setText("Plus loin, ensemble");
        tagline.setTextColor(OrbeTokens.COLOR_MUTED);
        tagline.setTypeface(OrbeTokens.typeLight());
        tagline.setTextSize(11f);
        titles.addView(tagline);

        row.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextColor(Color.WHITE);
        gear.setTextSize(20f);
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.setOnClickListener(v -> backToMainWithExtra("open_personalization"));
        row.addView(gear);

        return row;
    }

    // ── Bonjour + météo ──────────────────────────────────────────────────

    private View buildGreetingRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        String userName = UserProfileStore.getInstance(this).getUserName();
        TextView hello = new TextView(this);
        hello.setText(userName == null || userName.trim().isEmpty()
                ? "Bonjour" : "Bonjour " + userName);
        hello.setTextColor(Color.WHITE);
        hello.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        hello.setTextSize(22f);
        left.addView(hello);

        TextView daily = new TextView(this);
        daily.setText(HomeDailyLine.forToday(this));
        daily.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        daily.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        daily.setTextSize(13f);
        daily.setPadding(0, dp(4), 0, 0);
        left.addView(daily);

        row.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(buildWeatherCard(), marginLeft(dp(10)));

        return row;
    }

    private View buildWeatherCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(card(0x18FFFFFF));

        weatherTemp = new TextView(this);
        weatherTemp.setText("··°C");
        weatherTemp.setTextColor(Color.WHITE);
        weatherTemp.setTypeface(OrbeTokens.typeMedium());
        weatherTemp.setTextSize(15f);
        card.addView(weatherTemp);

        weatherCity = new TextView(this);
        weatherCity.setText("");
        weatherCity.setTextColor(OrbeTokens.COLOR_MUTED);
        weatherCity.setTextSize(11f);
        weatherCity.setPadding(dp(8), 0, 0, 0);
        card.addView(weatherCity);

        return card;
    }

    // ── Carte orbe ───────────────────────────────────────────────────────

    private View buildOrbCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(card(0x1435D0DD));
        card.setOnClickListener(v -> backToMainWithExtra("wake_activate"));

        View orbDot = new View(this);
        GradientDrawable orbBg = new GradientDrawable();
        orbBg.setShape(GradientDrawable.OVAL);
        orbBg.setColor(Color.parseColor(OrbeTokens.CYAN_CORE));
        orbBg.setStroke(dp(2), Color.parseColor(OrbeTokens.CYAN));
        orbDot.setBackground(orbBg);
        card.addView(orbDot, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView line1 = new TextView(this);
        line1.setText("Je suis Pégase.");
        line1.setTextColor(Color.WHITE);
        line1.setTypeface(OrbeTokens.typeMedium());
        line1.setTextSize(15f);
        texts.addView(line1);

        TextView line2 = new TextView(this);
        line2.setText("Dis-moi tout, je t'écoute.");
        line2.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        line2.setTextSize(13f);
        texts.addView(line2);

        card.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView wave = new TextView(this);
        wave.setText("🎙");
        wave.setTextSize(20f);
        card.addView(wave);

        return card;
    }

    // ── Grille 2×4 ───────────────────────────────────────────────────────

    private static final class MenuItem {
        final String emoji;
        final String title;
        final String subtitle;
        final Runnable action;
        /** Appui long — optionnel. */
        final Runnable longAction;

        MenuItem(String emoji, String title, String subtitle, Runnable action) {
            this(emoji, title, subtitle, action, null);
        }

        MenuItem(String emoji, String title, String subtitle, Runnable action,
                Runnable longAction) {
            this.emoji = emoji;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
            this.longAction = longAction;
        }
    }

    private View buildGrid() {
        MenuItem[] items = {
                new MenuItem("💬", "Discuter", "Pose tes questions", () ->
                        backToMainWithExtra("open_text_discussion")),
                new MenuItem("🎨", "Créer", "Images, textes, idées…", () ->
                        openTextDiscussionWithPhrase("Aide-moi à créer quelque chose…")),
                new MenuItem("🗂️", "Organiser", "Tâches, projets, rappels", () ->
                        backToMainWithExtra("open_bureau")),
                new MenuItem("🎮", "Jouer", "Jeux et univers créatifs", () ->
                        openTextDiscussionWithPhrase("Propose-moi un petit jeu ou une devinette.")),
                new MenuItem("🧠", "Mémoire", "Tes infos essentielles", () ->
                        startActivity(new Intent(this, MemorySettingsActivity.class))),
                new MenuItem("💬", "Messages", "Appui long : ménage", this::openPegaseSms,
                        () -> startActivity(new Intent(this, SmsCleanerActivity.class))),
                new MenuItem("🌐", "Messageries", "WhatsApp, Messenger", () ->
                        startActivity(new Intent(this,
                                com.pegasuscorp.orbe.webmsg.WebMessagingActivity.class))),
                new MenuItem("🔧", "Outils", "Des solutions au quotidien", () ->
                        PegaseInterfaceState.openOrBringToFront(this, PegaseInterfaceState.TAB_TOOLS)),
                new MenuItem("⋯", "Plus", "Encore plus de possibilités", () ->
                        backToMainWithExtra("open_app_drawer")),
        };

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setRowCount((items.length + 1) / 2);
        grid.setUseDefaultMargins(false);

        for (int i = 0; i < items.length; i++) {
            View cell = buildGridCell(items[i]);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 2, 1f), GridLayout.spec(i % 2, 1f));
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(i % 2 == 0 ? 0 : dp(6), i < 2 ? 0 : dp(10),
                    i % 2 == 0 ? dp(6) : 0, 0);
            grid.addView(cell, lp);
        }
        return grid;
    }

    private View buildGridCell(MenuItem item) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(12), dp(12), dp(12), dp(12));
        cell.setBackground(card(0x14FFFFFF));
        cell.setOnClickListener(v -> item.action.run());
        if (item.longAction != null) {
            cell.setOnLongClickListener(v -> {
                item.longAction.run();
                return true;
            });
        }

        TextView emoji = new TextView(this);
        emoji.setText(item.emoji);
        emoji.setTextSize(20f);
        cell.addView(emoji);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTypeface(OrbeTokens.typeMedium());
        title.setTextSize(14f);
        title.setPadding(0, dp(6), 0, 0);
        cell.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(item.subtitle);
        subtitle.setTextColor(OrbeTokens.COLOR_MUTED);
        subtitle.setTextSize(11f);
        subtitle.setPadding(0, dp(2), 0, 0);
        cell.addView(subtitle);

        return cell;
    }

    /** Ouvre Pégase SMS (fork QUIK) ; à défaut, l'écran de ménage SMS. */
    private void openPegaseSms() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PEGASE_SMS_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        } else {
            android.widget.Toast.makeText(this, "Pégase SMS n'est pas installé : ménage SMS.",
                    android.widget.Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SmsCleanerActivity.class));
        }
    }

    // ── Aujourd'hui ──────────────────────────────────────────────────────

    private View buildTodaySection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Aujourd'hui");
        title.setTextColor(Color.WHITE);
        title.setTypeface(OrbeTokens.typeMedium());
        title.setTextSize(16f);
        headerRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView seeAll = new TextView(this);
        seeAll.setText("Voir tout ›");
        seeAll.setTextColor(Color.parseColor(OrbeTokens.CYAN));
        seeAll.setTextSize(13f);
        seeAll.setOnClickListener(v ->
                startActivity(new Intent(this, RoutineSettingsActivity.class)));
        headerRow.addView(seeAll);

        section.addView(headerRow);

        List<CustomRoutineStore.CustomRoutine> routines =
                CustomRoutineStore.getInstance(this).listActive();
        if (routines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Rien de prévu aujourd'hui.");
            empty.setTextColor(OrbeTokens.COLOR_MUTED);
            empty.setTextSize(13f);
            empty.setPadding(0, dp(10), 0, dp(10));
            section.addView(empty);
        } else {
            int shown = Math.min(3, routines.size());
            for (int i = 0; i < shown; i++) {
                section.addView(buildRoutineRow(routines.get(i)), spacedAbove(8));
            }
        }

        return section;
    }

    private View buildRoutineRow(CustomRoutineStore.CustomRoutine routine) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(card(0x0FFFFFFF));
        row.setOnClickListener(v ->
                startActivity(new Intent(this, RoutineSettingsActivity.class)));

        TextView icon = new TextView(this);
        icon.setText(routineEmoji(routine.type));
        icon.setTextSize(16f);
        row.addView(icon, marginLeft(0));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(routine.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13f);
        texts.addView(title);

        TextView category = new TextView(this);
        category.setText(routineCategoryLabel(routine.type));
        category.setTextColor(OrbeTokens.COLOR_MUTED);
        category.setTextSize(11f);
        category.setPadding(0, dp(2), 0, 0);
        texts.addView(category);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    private static String routineEmoji(CustomRoutineStore.Type type) {
        switch (type) {
            case WEB_PAGE: return "🔗";
            case WEB_SEARCH: return "🔍";
            case LOAD_CONTEXT: return "📄";
            case REMINDER:
            default: return "⏰";
        }
    }

    private static String routineCategoryLabel(CustomRoutineStore.Type type) {
        switch (type) {
            case WEB_PAGE: return "Page";
            case WEB_SEARCH: return "Recherche";
            case LOAD_CONTEXT: return "Contexte";
            case REMINDER:
            default: return "Rappel";
        }
    }

    // ── Barre de saisie ──────────────────────────────────────────────────

    private View buildInputBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(6), dp(6), dp(6));
        bar.setBackground(card(0x1235D0DD));

        inputField = new EditText(this);
        inputField.setHint("Écris-moi ce que tu as en tête…");
        inputField.setHintTextColor(OrbeTokens.COLOR_MUTED);
        inputField.setTextColor(Color.WHITE);
        inputField.setBackground(null);
        inputField.setSingleLine(true);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT);
        bar.addView(inputField, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView mic = new TextView(this);
        mic.setText("🎙");
        mic.setTextSize(18f);
        mic.setPadding(dp(10), dp(10), dp(10), dp(10));
        mic.setOnClickListener(v -> backToMainWithExtra("wake_activate"));
        bar.addView(mic);

        inputField.setOnEditorActionListener((v, actionId, event) -> {
            sendTypedMessage();
            return true;
        });

        return bar;
    }

    private void sendTypedMessage() {
        String text = inputField.getText() == null ? "" : inputField.getText().toString().trim();
        if (!text.isEmpty()) {
            PegaseInterfaceState.setPendingChatPhrase(text);
        }
        backToMainWithExtra("open_text_discussion");
    }

    // ── Nav bar ──────────────────────────────────────────────────────────

    private View buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        bar.addView(navItem("🏠", "Accueil", this::finish));
        bar.addView(navItem("🧭", "Découvrir", this::showTipsDialog));
        bar.addView(navItem("🕓", "Historique", () -> {
            Intent i = new Intent(this, MemorySettingsActivity.class);
            i.putExtra("open_sessions", true);
            startActivity(i);
        }));
        bar.addView(navItem("👤", "Profil", () -> backToMainWithExtra("open_personalization")));

        return bar;
    }

    private View navItem(String emoji, String label, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(8), 0, dp(8));
        item.setOnClickListener(v -> action.run());

        TextView emojiView = new TextView(this);
        emojiView.setText(emoji);
        emojiView.setTextSize(18f);
        emojiView.setGravity(Gravity.CENTER);
        item.addView(emojiView);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(OrbeTokens.COLOR_MUTED);
        labelView.setTextSize(10f);
        labelView.setGravity(Gravity.CENTER);
        item.addView(labelView);

        item.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return item;
    }

    // ── Navigation vers MainActivity ─────────────────────────────────────

    private void backToMainWithExtra(String extraKey) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(extraKey, true);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void openTextDiscussionWithPhrase(String phrase) {
        PegaseInterfaceState.setPendingChatPhrase(phrase);
        backToMainWithExtra("open_text_discussion");
    }

    private void showTipsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Orbe_DarkDialog)
                .setTitle("Astuces Pégase")
                .setMessage(
                        "🔹 Double-tap sur l'orbe → ouvre Termux (proot-distro login ubuntu)\n\n"
                        + "🔹 Triple-tap sur l'orbe → tableau de widgets\n\n"
                        + "🔹 Swipe vers le haut sur l'orbe → tiroir d'apps\n\n"
                        + "🔹 Onglet Termux (menu Pégase) → raccourcis de commandes enregistrées\n\n"
                        + "🔹 Réglages → Dictionnaire vocal → corrige la prononciation de Pégase\n\n"
                        + "🔹 Réglages → voix Piper → change la voix de Pégase (Siwis / UPMC)")
                .setPositiveButton("Fermer", null)
                .show();
    }

    // ── Météo (Open-Meteo, sans clé API) ─────────────────────────────────

    private void loadWeather() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        fetchWeather();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    fetchWeather();
                    return;
                }
            }
        }
    }

    private void fetchWeather() {
        Location last = lastKnownLocation();
        if (last != null) {
            fetchWeatherFor(last);
            return;
        }
        // Pas de dernière position connue (ex: GPS jamais fixé récemment) —
        // on demande une position fraîche en repli plutôt que de rester vide.
        requestFreshLocation();
    }

    private void fetchWeatherFor(Location loc) {
        io.execute(() -> {
            try {
                double lat = loc.getLatitude();
                double lon = loc.getLongitude();

                String url = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current_weather=true",
                        lat, lon);
                JSONObject json = httpGetJson(url);
                JSONObject current = json != null ? json.optJSONObject("current_weather") : null;
                if (current == null) return;
                double temp = current.optDouble("temperature", Double.NaN);

                String city = reverseGeocodeCity(lat, lon);

                main.post(() -> {
                    if (!Double.isNaN(temp)) {
                        weatherTemp.setText(Math.round(temp) + "°C");
                    }
                    if (city != null && !city.isEmpty()) {
                        weatherCity.setText(city);
                    }
                });
            } catch (Exception ignored) {
                // Pas de réseau : la carte reste sur le placeholder "··°C".
            }
        });
    }

    private Location lastKnownLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return last;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Repli : demande une position ponctuelle (réseau, puis GPS) avec timeout 8 s. */
    private void requestFreshLocation() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        String provider;
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else {
                return;
            }
            LocationListener[] listenerHolder = new LocationListener[1];
            Runnable timeout = () -> {
                if (listenerHolder[0] != null) {
                    try {
                        lm.removeUpdates(listenerHolder[0]);
                    } catch (Exception ignored) {}
                }
            };
            listenerHolder[0] = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    main.removeCallbacks(timeout);
                    try {
                        lm.removeUpdates(this);
                    } catch (Exception ignored) {}
                    fetchWeatherFor(location);
                }

                @Override
                public void onStatusChanged(String p, int status, android.os.Bundle extras) {}

                @Override
                public void onProviderEnabled(String p) {}

                @Override
                public void onProviderDisabled(String p) {}
            };
            lm.requestSingleUpdate(provider, listenerHolder[0], Looper.getMainLooper());
            main.postDelayed(timeout, 8000);
        } catch (SecurityException ignored) {
            // Permission perdue entre-temps : la carte reste sur le placeholder.
        }
    }

    private String reverseGeocodeCity(double lat, double lon) {
        try {
            Geocoder geocoder = new Geocoder(this, Locale.FRENCH);
            List<android.location.Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses != null && !addresses.isEmpty()) {
                String locality = addresses.get(0).getLocality();
                return locality != null ? locality : addresses.get(0).getSubAdminArea();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject httpGetJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestMethod("GET");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    private GradientDrawable card(int colorArgb) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(OrbeTokens.RADIUS_LG));
        d.setColor(colorArgb);
        d.setStroke(dp(1), Color.parseColor(OrbeTokens.CHIP_STROKE));
        return d;
    }

    private LinearLayout.LayoutParams spacedAbove(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dpValue);
        return lp;
    }

    private LinearLayout.LayoutParams marginLeft(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
