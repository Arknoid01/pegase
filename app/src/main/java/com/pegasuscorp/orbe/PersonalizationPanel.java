package com.pegasuscorp.orbe;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.chat.CloudModelStore;
import com.pegasuscorp.orbe.chat.ProviderKeyProbe;
import com.pegasuscorp.orbe.spotify.SpotifyAuthHelper;
import com.pegasuscorp.orbe.spotify.SpotifyAuthStore;
import com.pegasuscorp.orbe.voice.PiperModelDownloader;
import com.pegasuscorp.orbe.voice.PiperModelImporter;
import com.pegasuscorp.orbe.voice.PiperModelStore;
import com.pegasuscorp.orbe.voice.VoiceWakeClient;
import com.pegasuscorp.orbe.diag.DiagReport;
import com.pegasuscorp.orbe.diag.DiagScriptRunStore;
import com.pegasuscorp.orbe.diag.DiagScriptResult;
import com.pegasuscorp.orbe.diag.DiagScriptRunner;
import com.pegasuscorp.orbe.diag.DiagScripts;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.prefetch.PrefetchScheduler;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Panneau de personnalisation : apparence, launcher, cerveau, voix, mémoire, services, diagnostic.
 */
public class PersonalizationPanel extends FrameLayout {

    public interface Listener {
        void onPersonalizationChanged();
        void onPickSystemWallpaper();
        void onPickGalleryWallpaper();
        void onResetWallpaper();
        void onSetDefaultLauncher();
        void onResetWidgetBoard();
        void onCloudProviderSelected(String provider);
        void onCloudModelSelected(String modelId);
        void onApiKeysSaved();
        void onOpenChatTest();
        void onImportPiperZip();
        void onImportPiperFolder();
        void onDownloadPiper();
        void onDownloadPiperVoice(com.pegasuscorp.orbe.voice.PiperModelStore.Voice voice);
        void onPiperVoiceChanged();
    }

    private Listener listener;
    /** Page en cours de construction (les helpers addXxx y ajoutent leurs vues). */
    private LinearLayout body;
    private ScrollView scroll;
    private LinearLayout root;
    private LinearLayout menuPage;
    private final java.util.LinkedHashMap<String, LinearLayout> pages = new java.util.LinkedHashMap<>();
    private final java.util.List<String[]> menuEntries = new java.util.ArrayList<>();
    /** Page affichée, null = menu des rubriques. */
    private String currentPage;
    private LinearLayout colorRow;
    private LinearLayout iconPackList;
    private LinearLayout cloudModelList;
    private EditText groqKeyField;
    private EditText cerebrasKeyField;
    private EditText openRouterKeyField;
    private EditText geminiKeyField;
    private EditText tavilyKeyField;
    private EditText braveKeyField;
    private EditText newsKeyField;
    private EditText spotifyClientIdField;
    private EditText userCityField;
    private EditText userCoordsField;
    private EditText nasaKeyField;
    private TextView blurValueLabel;
    private TextView piperSpeedLabel;
    private TextView stressTestBtn;
    private TextView scriptRunBtn;
    private TextView scriptStatus;
    private TextView prefetchAlarmBtn;
    private TextView voiceChoiceSummary;
    private int accentMiddle = Color.parseColor("#35D0DD");
    private int colorSizePx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService diagIo = Executors.newSingleThreadExecutor();
    private final DiagScriptRunner.Listener scriptUiListener = new DiagScriptRunner.Listener() {
        @Override
        public void onProgress(int index, int total, String label, String phase) {
            mainHandler.post(() -> refreshScriptUi());
        }

        @Override
        public void onComplete(DiagScriptResult result) {
            mainHandler.post(() -> showScriptResult(result));
        }

        @Override
        public void onCannotStart(String reason) {
            mainHandler.post(() -> {
                if (scriptRunBtn != null) scriptRunBtn.setEnabled(true);
                refreshScriptUi();
                android.widget.Toast.makeText(getContext(), reason,
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }
    };

    public PersonalizationPanel(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        colorSizePx = (int) (40 * density);

        setBackgroundColor(Color.parseColor("#FA0B0E14"));
        setClickable(true);
        setFocusable(true);
        setVisibility(GONE);
        setElevation(24f);

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int statusBarId = context.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        int statusBar = statusBarId > 0
                ? context.getResources().getDimensionPixelSize(statusBarId) : (int) (24 * density);
        root.setPadding(pad, pad + statusBar, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = newPage("appearance", "🎨", "Apparence",
                "Couleur de l'orbe, icônes, fond d'écran, flou");
        // ── 1. Apparence ──────────────────────────────────────────────────────

        body.addView(addSubHeading(context, "Couleur de l'orbe"));
        colorRow = new LinearLayout(context);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        rebuildColorRow();
        body.addView(colorRow, wrapLp());

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Style des icônes"));
        addIconThemeRow(PersonalizationStore.ICON_SYSTEM, "Système");
        addIconThemeRow(PersonalizationStore.ICON_ROUND, "Rond");
        addIconThemeRow(PersonalizationStore.ICON_ROUNDED_SQUARE, "Carré arrondi");

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Pack d'icônes"));
        iconPackList = new LinearLayout(context);
        iconPackList.setOrientation(LinearLayout.VERTICAL);
        body.addView(iconPackList, wrapLp());

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Fond d'écran"));
        addActionRow("Fonds système", () -> {
            if (listener != null) listener.onPickSystemWallpaper();
        });
        addActionRow("Depuis la galerie", () -> {
            if (listener != null) listener.onPickGalleryWallpaper();
        });
        addActionRow("Réinitialiser (système)", () -> {
            if (listener != null) listener.onResetWallpaper();
        });

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Fond Fluid"));
        body.addView(hintLabel(context,
                "Atmosphère vivante selon l'heure (Midday, Soir, Nuit…). Phase actuelle : "
                        + FluidPhase.current().label + "."));

        android.widget.Switch fluidSwitch = new android.widget.Switch(context);
        fluidSwitch.setText("Activer le fond Fluid");
        fluidSwitch.setTextColor(Color.WHITE);
        fluidSwitch.setChecked(PersonalizationStore.isFluidEnabled(context));
        fluidSwitch.setOnCheckedChangeListener((btn, checked) -> {
            PersonalizationStore.setFluidEnabled(context, checked);
            notifyChanged();
        });
        body.addView(fluidSwitch, wrapLp());

        android.widget.Switch fluidAnimSwitch = new android.widget.Switch(context);
        fluidAnimSwitch.setText("Animation Fluid (GPU)");
        fluidAnimSwitch.setTextColor(Color.WHITE);
        fluidAnimSwitch.setChecked(PersonalizationStore.isFluidAnimated(context));
        fluidAnimSwitch.setOnCheckedChangeListener((btn, checked) -> {
            PersonalizationStore.setFluidAnimated(context, checked);
            notifyChanged();
        });
        body.addView(fluidAnimSwitch, wrapLp());

        android.widget.Switch fluidLockSwitch = new android.widget.Switch(context);
        fluidLockSwitch.setText("Appliquer aussi à l'écran de verrouillage");
        fluidLockSwitch.setTextColor(Color.WHITE);
        fluidLockSwitch.setChecked(PersonalizationStore.isFluidLockWallpaperEnabled(context));
        fluidLockSwitch.setOnCheckedChangeListener((btn, checked) -> {
            PersonalizationStore.setFluidLockWallpaperEnabled(context, checked);
            notifyChanged();
        });
        body.addView(fluidLockSwitch, wrapLp());
        body.addView(hintLabel(context,
                "Met à jour le fond verrouillage selon la phase horaire (Nuit, Matin, Soir…)."));

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Flou de l'accueil"));
        blurValueLabel = new TextView(context);
        blurValueLabel.setTextColor(Color.WHITE);
        blurValueLabel.setTextSize(14);
        body.addView(blurValueLabel, wrapLp());

        SeekBar blurSeek = new SeekBar(context);
        blurSeek.setMax(PersonalizationStore.BLUR_MAX);
        blurSeek.setProgress(PersonalizationStore.getHomeBlur(context));
        updateBlurLabel(blurSeek.getProgress());
        blurSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBlurLabel(progress);
                if (fromUser) {
                    PersonalizationStore.setHomeBlur(context, progress);
                    notifyChanged();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        body.addView(blurSeek, wrapLp());

        body = newPage("home", "🏠", "Accueil & routines",
                "Écran d'accueil, orbe flottante, brief, routines, localisation");
        addActionRow("Définir comme écran d'accueil", () -> {
            if (listener != null) listener.onSetDefaultLauncher();
        });
        addActionRow("Réinitialiser l'écran widgets", () -> {
            if (listener != null) listener.onResetWidgetBoard();
        });
        prefetchAlarmBtn = addActionRow(prefetchAlarmLabel(context), this::pickPrefetchAlarmTime);
        addActionRow("Routines du matin", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), RoutineSettingsActivity.class)));
        addActionRow("Localisation & conduite", () ->
                getContext().startActivity(new android.content.Intent(getContext(),
                        com.pegasuscorp.orbe.intentions.location.SituationSettingsActivity.class)));

        addActionRow("Orbe flottante (mode copilote)", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), com.pegasuscorp.orbe.copilot.CopilotSettingsActivity.class)));
        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Ma localisation (météo)"));
        body.addView(hintLabel(context,
                "Ville : nom affiché · GPS : latitude,longitude (ex: 46.1083,5.8261)\n"
                + "Le GPS est prioritaire si renseigné — utile pour les petites communes."));
        userCityField  = addApiKeyField(context, "Ma ville (ex: Valserhône)");
        userCoordsField = addApiKeyField(context, "Coordonnées GPS (lat,lon)");

        body = newPage("brain", "🧠", "Cerveau",
                "Fournisseur, modèle, clés API");

        body.addView(addSubHeading(context, "Fournisseur"));
        body.addView(hintLabel(context,
                "Rotation auto : Groq → Cerebras → OpenRouter (Gemini hors chaîne)."));
        addProviderRow(CloudModelStore.PROVIDER_GROQ, "Groq (prioritaire)");
        addProviderRow(CloudModelStore.PROVIDER_GEMINI, "Gemini (manuel, hors rotation)");

        body.addView(spacer(context, (int) (8 * density)));
        body.addView(addSubHeading(context, "Modèle"));
        cloudModelList = new LinearLayout(context);
        cloudModelList.setOrientation(LinearLayout.VERTICAL);
        body.addView(cloudModelList, wrapLp());
        rebuildCloudModelList();

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Clés API"));
        body.addView(hintLabel(context,
                "Rotation LLM : console.groq.com · cloud.cerebras.ai · openrouter.ai"));
        groqKeyField = addApiKeyField(context, "Clé Groq (gsk_...)");
        cerebrasKeyField = addApiKeyField(context, "Clé Cerebras");
        openRouterKeyField = addApiKeyField(context, "Clé OpenRouter (sk-or-...)");
        body.addView(hintLabel(context,
                "Documents (PDF / image) : clé OpenRouter — Discussion → Joindre un document."));
        body.addView(hintLabel(context, "Gemini (hors rotation) : aistudio.google.com"));
        geminiKeyField = addApiKeyField(context, "Clé Gemini (AIza...)");
        body.addView(hintLabel(context,
                "Recherche privée (prioritaire) : api-dashboard.search.brave.com — pages lues sur le téléphone"));
        braveKeyField = addApiKeyField(context, "Clé Brave Search (BSA...)");
        if (com.pegasuscorp.orbe.search.MoteurPrive.modeleInstalle(context)) {
            body.addView(hintLabel(context, "Modèle de recherche privée : installé ✓"));
        } else {
            addActionRow("Télécharger le modèle de recherche (≈250 Mo, Wi-Fi)", () ->
                    android.widget.Toast.makeText(getContext(),
                            com.pegasuscorp.orbe.search.MoteurPrive.telecharger(getContext())
                                    ? "Téléchargement lancé — suis-le dans les notifications."
                                    : "Téléchargement impossible.",
                            android.widget.Toast.LENGTH_LONG).show());
        }
        body.addView(hintLabel(context, "Recherche de secours : tavily.com · Actualités : newsapi.org"));
        tavilyKeyField = addApiKeyField(context, "Clé Tavily (tvly-...)");
        newsKeyField = addApiKeyField(context, "Clé NewsAPI (pub_...)");
        body.addView(hintLabel(context, "NASA : api.nasa.gov"));
        nasaKeyField = addApiKeyField(context, "Clé NASA (DEMO_KEY par défaut)");
        addActionRow("Enregistrer les clés", this::saveApiKeys);
        addActionRow("Tester Groq / Cerebras / OpenRouter", this::testLlmKeys);
        addActionRow("Tester la discussion (texte)", () -> {
            if (listener != null) listener.onOpenChatTest();
        });

        body = newPage("voice", "🎙️", "Voix",
                "Mot d'éveil, ma voix, synthèse, compréhension");

        // ── Écoute ──
        body.addView(majorSectionTitle(context, "Écoute"));
        addSwitchRow("Écoute « Hey Pégase »",
                "En pause pendant une vidéo ou de la musique",
                com.pegasuscorp.orbe.voice.PegaseWakeStore.isEnabled(context), on -> {
                    com.pegasuscorp.orbe.voice.PegaseWakeStore.setEnabled(context, on);
                    VoiceWakeClient.get().sync(context);
                });
        addSwitchRow("Couper le micro",
                "Coupe tout : mot d'éveil et discussion",
                com.pegasuscorp.orbe.voice.VoiceMuteStore.isMuted(context), muted -> {
                    com.pegasuscorp.orbe.voice.VoiceMuteStore.setMuted(context, muted);
                    VoiceWakeClient.get().sync(context);
                });
        addSwitchRow("Mode doux",
                "Moins de ralentissements, réveil un peu moins vif",
                com.pegasuscorp.orbe.voice.PegaseWakeStore.isGentleMode(context), gentle -> {
                    com.pegasuscorp.orbe.voice.PegaseWakeStore.setGentleMode(context, gentle);
                    VoiceWakeClient.get().setGentleMode(context, gentle);
                    VoiceWakeClient.get().sync(context);
                });

        final String[] owwLabels = {"Moins sensible — moins de faux réveils (0,88)",
                "Recommandée (0,80)", "Plus sensible — répond plus facilement (0,70)"};
        final float[] owwValues = {0.88f,
                com.pegasuscorp.orbe.voice.PegaseWakeStore.DEFAULT_OWW_THRESHOLD, 0.70f};
        TextView[] owwSummary = new TextView[1];
        owwSummary[0] = addNavRow("Sensibilité du mot d'éveil",
                owwLabels[nearest(owwValues,
                        com.pegasuscorp.orbe.voice.PegaseWakeStore.getOwwThreshold(context))],
                () -> choose("Sensibilité du mot d'éveil", owwLabels,
                        nearest(owwValues,
                                com.pegasuscorp.orbe.voice.PegaseWakeStore.getOwwThreshold(context)),
                        i -> {
                            VoiceWakeClient.get().setOwwThreshold(context, owwValues[i]);
                            owwSummary[0].setText(owwLabels[i]);
                        }));

        final String[] gateLabels = {"Stricte (0,72)", "Normale (0,65)",
                "Souple — si elle ne te répond plus (0,55)", "Non, tout le monde peut la réveiller"};
        final float[] gateValues = {0.72f,
                com.pegasuscorp.orbe.voice.WakeVoiceGate.DEFAULT_THRESHOLD, 0.55f, -1f};
        TextView[] gateSummary = new TextView[1];
        gateSummary[0] = addNavRow("Ne répondre qu'à ma voix",
                gateLabels[currentGateIndex(context, gateValues)],
                () -> choose("Ne répondre qu'à ma voix", gateLabels,
                        currentGateIndex(context, gateValues), i -> {
                            boolean on = gateValues[i] > 0f;
                            com.pegasuscorp.orbe.voice.WakeVoiceGate.setEnabled(context, on);
                            if (on) com.pegasuscorp.orbe.voice.WakeVoiceGate
                                    .setThreshold(context, gateValues[i]);
                            gateSummary[0].setText(gateLabels[i]);
                        }));
        body.addView(hintLabel(context,
                "Après « Hey Pégase », elle compare la voix à tes enregistrements "
                        + "et ignore la radio ou la télé."));

        body.addView(addSubHeading(context, "Ma voix"));
        addNavRow("Enregistrer mon « Hey Pégase »",
                com.pegasuscorp.orbe.voice.WakeOwwStore.statusLabel(context), () ->
                        getContext().startActivity(new android.content.Intent(getContext(),
                                com.pegasuscorp.orbe.WakeWordRecordActivity.class)));
        int samples = com.pegasuscorp.orbe.voice.SpeakerProfileStore.getInstance(context)
                .getSampleCount();
        addNavRow("Enregistrer mon empreinte vocale",
                samples >= 3 ? "Empreinte OK" : "Empreinte " + samples + "/3", () ->
                        getContext().startActivity(new android.content.Intent(getContext(),
                                SpeakerEnrollmentActivity.class)));
        com.pegasuscorp.orbe.voice.SpeakerProfileStore speakerProfile =
                com.pegasuscorp.orbe.voice.SpeakerProfileStore.getInstance(context);
        addSwitchRow("Confirmer avec ma voix",
                "Les actions sensibles demandent « Dis Pégase pour confirmer »",
                speakerProfile.isRequireOwnerVoice(), speakerProfile::setRequireOwnerVoice);

        // ── Parole ──
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Parole"));
        ensureAndroidVoiceCache();
        voiceChoiceSummary = addNavRow("Voix de Pégase", currentVoiceLabel(context),
                this::chooseVoice);
        addSwitchRow("Voix hors ligne uniquement",
                "Cache les voix système qui ont besoin d'Internet",
                com.pegasuscorp.orbe.voice.AndroidTtsStore.preferLocalOnly(context), on -> {
                    com.pegasuscorp.orbe.voice.AndroidTtsStore.setPreferLocalOnly(getContext(), on);
                    refreshTtsStatus();
                    if (listener != null) listener.onPiperVoiceChanged();
                });

        piperSpeedLabel = new TextView(context);
        piperSpeedLabel.setTextColor(Color.WHITE);
        piperSpeedLabel.setTextSize(14);
        piperSpeedLabel.setPadding(0, (int) (12 * density), 0, 0);
        updatePiperSpeedLabel(PiperModelStore.getSpeechSpeed(context));
        body.addView(piperSpeedLabel, wrapLp());
        SeekBar piperSpeedSeek = new SeekBar(context);
        piperSpeedSeek.setMax(30);
        piperSpeedSeek.setProgress(speedToProgress(PiperModelStore.getSpeechSpeed(context)));
        piperSpeedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float speed = progressToSpeed(progress);
                    PiperModelStore.setSpeechSpeed(getContext(), speed);
                    updatePiperSpeedLabel(speed);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        body.addView(piperSpeedSeek, wrapLp());
        body.addView(hintLabel(context, "Plus lent = articulation plus claire."));

        addNavRow("Prononciation",
                "Ex. « dis Qwen comme Couène » — modifiable aussi à la voix", () ->
                        getContext().startActivity(new android.content.Intent(getContext(),
                                SpeechRulesSettingsActivity.class)));
        addNavRow("Importer une voix Piper…", "Depuis un ZIP ou un dossier", () ->
                choose("Importer une voix Piper",
                        new String[]{"Fichier ZIP", "Dossier"}, -1, i -> {
                            if (listener == null) return;
                            if (i == 0) listener.onImportPiperZip();
                            else listener.onImportPiperFolder();
                        }));

        // ── Compréhension ──
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Compréhension"));
        addNavRow("Corrections vocales",
                "Mots mal compris qu'elle a appris à corriger (« non, je voulais dire… »)", () ->
                        getContext().startActivity(new android.content.Intent(getContext(),
                                VoiceCorrectionsSettingsActivity.class)));
        addSwitchRow("Apprendre ma façon de parler",
                "Retient tes formulations confirmées pour choisir le bon outil",
                com.pegasuscorp.orbe.voice.LearnModeStore.isEnabled(context),
                on -> com.pegasuscorp.orbe.voice.LearnModeStore.setEnabled(context, on));
        addNavRow("Mon corpus personnel", "Les phrases qu'elle a retenues", () ->
                getContext().startActivity(new android.content.Intent(getContext(),
                        KnowMeSettingsActivity.class)));
        addNavRow("Apprentissage des outils",
                "Importer une conversation et associer phrases → outils", () ->
                        getContext().startActivity(new android.content.Intent(getContext(),
                                RoutingLearningActivity.class)));

        body = newPage("memory", "💾", "Mémoire",
                "Portrait, atlas, souvenirs");
        body.addView(hintLabel(context,
                "Souvenirs, profil et résumés de session — modifiables aussi à la voix "
                        + "(« retiens que… », « oublie… »)."));
        addActionRow("Portrait — ce qu'elle croit savoir", () ->
                PortraitActivity.open(getContext()));
        addActionRow("Atlas des entités (personnes, projets…)", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), AtlasSettingsActivity.class)));
        addActionRow("Voir et modifier la mémoire", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), MemorySettingsActivity.class)));
        addActionRow("Interface Pégase (conversation, outils)", () ->
                PegaseInterfaceState.openOrBringToFront(getContext()));

        body = newPage("services", "🔌", "Services",
                "Spotify, notifications, Orion, Drive, MCP, PC");

        body.addView(addSubHeading(context, "Spotify Premium"));
        body.addView(hintLabel(context,
                "Contrôle vocal : « Mets du Daft Punk », « pause », « qu'est-ce qui joue ? »\n"
                        + "Dashboard : developer.spotify.com/dashboard"));
        spotifyClientIdField = addApiKeyField(context, "Client ID Spotify");
        body.addView(hintLabel(context,
                "Redirect URI : com.pegasuscorp.orbe://spotify-callback"));
        addActionRow(spotifyAuthButtonLabel(context), () -> toggleSpotifyConnection(context));
        addActionRow(spotifyOrbShortcutLabel(context), this::toggleSpotifyOrbShortcut);

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Notifications"));
        body.addView(hintLabel(context,
                "Lecture, ouverture et effacement des notifications système. "
                        + "Autorisation requise une seule fois."));
        addActionRow(com.pegasuscorp.orbe.notifications.NotificationAccess.isEnabled(context)
                ? "Accès notifications activé"
                : "Autoriser l'accès aux notifications", () -> {
            com.pegasuscorp.orbe.notifications.NotificationAccess.openSettings(context);
            android.widget.Toast.makeText(context,
                    "Active « Orbe » ou « Pégase » dans la liste",
                    android.widget.Toast.LENGTH_LONG).show();
        });

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Orion · RunPod"));
        body.addView(hintLabel(context,
                "Pod GPU qwen3-coder — volume configurable (région RunPod)."
                        + " · « lance Orion », « éteins Orion »"));
        addActionRow("Configurer Orion (GPU, budget, clés)", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), OrionSettingsActivity.class)));

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Autres connexions"));
        addActionRow("Synchro Google Drive", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), com.pegasuscorp.orbe.sync.DriveSyncSettingsActivity.class)));
        addActionRow("Serveurs MCP", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), com.pegasuscorp.orbe.mcp.McpSettingsActivity.class)));
        addActionRow("Terminal PC", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), com.pegasuscorp.orbe.pcshell.PcShellSettingsActivity.class)));
        body = newPage("advanced", "🛠️", "Avancé",
                "Dépannage du mot d'éveil, traces, mini-tests");
        body.addView(addSubHeading(context, "Mot d'éveil — dépannage"));
        addActionRow(com.pegasuscorp.orbe.voice.KwsModelStore.statusLabel(context), () ->
                android.widget.Toast.makeText(context,
                        com.pegasuscorp.orbe.voice.KwsModelStore.statusLabel(context),
                        android.widget.Toast.LENGTH_LONG).show());
        addActionRow("Réinitialiser garde KWS (si écoute bloquée)", () -> {
            com.pegasuscorp.orbe.voice.KwsCrashGuard.resetForUser(context);
            VoiceWakeClient.get().sync(context);
            android.widget.Toast.makeText(context,
                    "Garde KWS réinitialisée — relance de l'écoute",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        addActionRow("Exporter logs KWS / crash (JSONL)", () ->
                com.pegasuscorp.orbe.diag.PegaseDiagLog.shareLogs(context));
        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Wake local (Sherpa — filet)"));
        body.addView(hintLabel(context,
                "Filet si openWakeWord n'est pas encore installé (~15 Mo). "
                        + "Téléchargement auto au premier démarrage de :voice."));
        TextView kwsStatus = new TextView(context);
        kwsStatus.setTextColor(Color.parseColor("#88FFFFFF"));
        kwsStatus.setTextSize(12);
        kwsStatus.setTag("kws_status");
        kwsStatus.setText(com.pegasuscorp.orbe.voice.KwsModelStore.statusLabel(context));
        body.addView(kwsStatus, wrapLp());
        addActionRow(com.pegasuscorp.orbe.voice.KwsModelStore.isModelReady(context)
                        || com.pegasuscorp.orbe.voice.KwsModelDownloader.isDownloading()
                ? "Réinstaller le modèle wake"
                : "Télécharger le modèle wake (~15 Mo)", () -> {
            if (com.pegasuscorp.orbe.voice.KwsModelDownloader.isDownloading()) {
                android.widget.Toast.makeText(context, "Téléchargement déjà en cours…",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            android.widget.Toast.makeText(context, "Téléchargement wake Sherpa…",
                    android.widget.Toast.LENGTH_SHORT).show();
            com.pegasuscorp.orbe.voice.KwsModelDownloader.download(context,
                    new com.pegasuscorp.orbe.voice.KwsModelDownloader.Callback() {
                        @Override
                        public void onProgress(int percent) {
                            mainHandler.post(() -> {
                                for (View child : allRows()) {
                                    if ("kws_status".equals(child.getTag())) {
                                        ((TextView) child).setText(
                                                "Téléchargement wake… " + percent + "%");
                                    }
                                }
                            });
                        }

                        @Override
                        public void onComplete(boolean success, String message) {
                            mainHandler.post(() -> {
                                for (View child : allRows()) {
                                    if ("kws_status".equals(child.getTag())) {
                                        ((TextView) child).setText(
                                                com.pegasuscorp.orbe.voice.KwsModelStore
                                                        .statusLabel(getContext()));
                                    }
                                }
                                android.widget.Toast.makeText(getContext(), message,
                                        android.widget.Toast.LENGTH_LONG).show();
                                if (success) {
                                    VoiceWakeClient.get().sync(getContext());
                                }
                            });
                        }
                    });
        });
        addActionRow("Réinitialiser le coupe-circuit wake", () -> {
            if (!com.pegasuscorp.orbe.voice.VoiceWakeClient.get()
                    .getCachedWakeHealth().isProblem()) {
                android.widget.Toast.makeText(context,
                        "Aucun problème wake détecté pour l'instant",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            com.pegasuscorp.orbe.voice.VoiceWakeClient.get().resetKwsCrashGuard(context);
            android.widget.Toast.makeText(context,
                    "Coupe-circuit réinitialisé — relance du wake…",
                    android.widget.Toast.LENGTH_SHORT).show();
            VoiceWakeClient.get().sync(context);
        });

        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Diagnostic"));
        body.addView(hintLabel(context,
                "trace.jsonl + rapport d'anomalies — files/diag/ sur l'appareil"));
        addActionRow("Tableau de bord debug", () ->
                context.startActivity(DebugDashboardActivity.intent(context)));
        body.addView(hintLabel(context,
                "voix ou texte : « mode test » pour marquer les sessions poussées"));
        stressTestBtn = addActionRow(stressTestLabel(), this::toggleStressTest);

        body.addView(spacer(context, (int) (8 * density)));
        body.addView(addSubHeading(context, "Mini-tests automatiques"));
        body.addView(hintLabel(context,
                "3 requêtes · pause " + (DiagScripts.COOLDOWN_MS / 1000)
                        + " s · mémoire sauvegardée puis vidée · restauration auto à la fin"));
        scriptStatus = hintLabel(context, "Prêt — appuie pour lancer une trace propre à analyser.");
        scriptRunBtn = addActionRow("Lancer mini-tests diagnostic", this::startMiniDiagSuite);
        addActionRow("Analyser les traces", this::runDiagToast);
        addActionRow("Réinitialiser les traces", this::confirmClearTraceFiles);
        addActionRow("Partager trace.jsonl", this::shareTraceFile);
        addActionRow("Partager le rapport JSON", this::shareDiagReport);

        buildMenu(context);
        showMenu();
    }

    // ── Pages ────────────────────────────────────────────────────────────────

    private LinearLayout newPage(String key, String icon, String title, String summary) {
        LinearLayout page = new LinearLayout(getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        pages.put(key, page);
        menuEntries.add(new String[]{key, icon, title, summary});
        return page;
    }

    private void buildMenu(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        menuPage = new LinearLayout(context);
        menuPage.setOrientation(LinearLayout.VERTICAL);
        TextView title = majorSectionTitle(context, "Réglages");
        title.setTextSize(20);
        menuPage.addView(title, wrapLp());
        for (String[] e : menuEntries) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = (int) (14 * density);
            row.setPadding(p, p, p, p);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(14 * density);
            bg.setColor(Color.parseColor("#14FFFFFF"));
            row.setBackground(bg);

            TextView icon = new TextView(context);
            icon.setText(e[1]);
            icon.setTextSize(22);
            row.addView(icon, new LinearLayout.LayoutParams(
                    (int) (44 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(context);
            name.setText(e[2]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            name.setTypeface(null, Typeface.BOLD);
            texts.addView(name);
            TextView summary = new TextView(context);
            summary.setText(e[3]);
            summary.setTextColor(Color.parseColor("#88FFFFFF"));
            summary.setTextSize(12);
            texts.addView(summary);
            row.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chevron = new TextView(context);
            chevron.setText("›");
            chevron.setTextColor(Color.parseColor("#66FFFFFF"));
            chevron.setTextSize(22);
            row.addView(chevron);

            final String key = e[0];
            final String pageTitle = e[1] + "  " + e[2];
            row.setOnClickListener(v -> openPage(key, pageTitle));
            LinearLayout.LayoutParams lp = wrapLp();
            lp.topMargin = (int) (10 * density);
            menuPage.addView(row, lp);
        }
    }

    private void showMenu() {
        currentPage = null;
        hideKeyboard();
        root.removeAllViews();
        root.addView(menuPage, wrapLp());
        scroll.scrollTo(0, 0);
    }

    private void openPage(String key, String title) {
        LinearLayout page = pages.get(key);
        if (page == null) return;
        currentPage = key;
        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        root.removeAllViews();

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(context);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(28);
        back.setPadding((int) (4 * density), 0, (int) (16 * density), (int) (4 * density));
        back.setContentDescription("Retour aux réglages");
        back.setOnClickListener(v -> showMenu());
        header.addView(back);
        TextView name = majorSectionTitle(context, title);
        name.setTextSize(19);
        name.setTextColor(Color.WHITE);
        name.setPadding(0, 0, 0, 0);
        header.addView(name);
        LinearLayout.LayoutParams hlp = wrapLp();
        hlp.bottomMargin = (int) (10 * density);
        root.addView(header, hlp);

        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        root.addView(page, wrapLp());
        scroll.scrollTo(0, 0);
    }

    /** Retour Android : depuis une rubrique, revient au menu. @return true si consommé. */
    public boolean handleBack() {
        if (!isVisiblePanel() || currentPage == null) return false;
        showMenu();
        return true;
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    /** Vues de premier niveau de toutes les rubriques (recherche par tag). */
    private java.util.List<View> allRows() {
        java.util.List<View> out = new java.util.ArrayList<>();
        for (LinearLayout page : pages.values()) {
            for (int i = 0; i < page.getChildCount(); i++) out.add(page.getChildAt(i));
        }
        return out;
    }

    private String prefetchAlarmLabel(Context context) {
        return "Heure du brief — " + PrefetchScheduler.formatTimeLabel(context);
    }

    private void pickPrefetchAlarmTime() {
        Context context = getContext();
        new TimePickerDialog(context, (view, hour, minute) -> {
            PrefetchScheduler.setAlarmTime(context, hour, minute);
            if (prefetchAlarmBtn != null) {
                prefetchAlarmBtn.setText(prefetchAlarmLabel(context));
            }
            android.widget.Toast.makeText(context,
                    "Brief programmé à " + PrefetchScheduler.formatTimeLabel(context),
                    android.widget.Toast.LENGTH_SHORT).show();
        }, PrefetchScheduler.getHour(context), PrefetchScheduler.getMinute(context), true).show();
    }

    private void updateBlurLabel(int progress) {
        if (progress <= 0) blurValueLabel.setText("Désactivé");
        else blurValueLabel.setText("Intensité : " + progress);
    }

    private void updatePiperSpeedLabel(float speed) {
        if (piperSpeedLabel == null) return;
        int pct = Math.round(speed * 100f);
        piperSpeedLabel.setText("Vitesse Piper : " + pct + " %");
    }

    private static int speedToProgress(float speed) {
        return Math.round((speed - 0.80f) / 0.01f);
    }

    private static float progressToSpeed(int progress) {
        return 0.80f + progress * 0.01f;
    }

    private void rebuildIconPackList() {
        Context context = getContext();
        iconPackList.removeAllViews();
        addIconPackRow("", "Icônes système", null);
        for (IconPackManager.PackInfo pack : IconPackManager.discoverInstalled(context)) {
            addIconPackRow(pack.packageName, pack.label, pack.icon);
        }
    }

    public void refreshPiperStatus() {
        for (View child : allRows()) {
            if ("piper_status".equals(child.getTag())) {
                ((TextView) child).setText(PiperModelStore.statusLabel(getContext()));
            }
        }
        refreshTtsStatus();
    }

    private void refreshTtsStatus() {
        if (voiceChoiceSummary != null) voiceChoiceSummary.setText(currentVoiceLabel(getContext()));
        if (pages.isEmpty()) return;
        for (View child : allRows()) {
            if ("tts_engine_status".equals(child.getTag())) {
                ((TextView) child).setText(
                        com.pegasuscorp.orbe.voice.AndroidTtsStore.statusLabel(getContext())
                                + "\n" + PiperModelStore.statusLabel(getContext()));
            }
        }
    }

    private void rebuildSystemVoiceRows() {
        if (pages.isEmpty()) return;
        LinearLayout list = null;
        for (View child : allRows()) {
            if ("system_voice_list".equals(child.getTag()) && child instanceof LinearLayout) {
                list = (LinearLayout) child;
                break;
            }
        }
        if (list == null) {
            refreshTtsStatus();
            return;
        }
        list.removeAllViews();
        Context context = getContext();
        boolean localOnly = com.pegasuscorp.orbe.voice.AndroidTtsStore.preferLocalOnly(context);
        java.util.List<com.pegasuscorp.orbe.voice.AndroidTtsStore.VoiceInfo> voices =
                com.pegasuscorp.orbe.voice.AndroidTtsStore.getCachedVoices();
        if (voices.isEmpty()) {
            TextView empty = hintLabel(context,
                    "Liste vide — ouvre Orbe une fois (init TTS) puis reviens ici.");
            list.addView(empty);
            ensureAndroidVoiceCache();
            return;
        }
        String selected = com.pegasuscorp.orbe.voice.AndroidTtsStore.getVoiceName(context);
        int shown = 0;
        for (com.pegasuscorp.orbe.voice.AndroidTtsStore.VoiceInfo v : voices) {
            if (localOnly && v.networkRequired) continue;
            shown++;
            boolean active = selected.equals(v.name);
            String label = (active ? "✓ " : "") + v.displayLabel();
            TextView row = new TextView(context);
            row.setText(label);
            row.setTextColor(Color.WHITE);
            row.setTextSize(13);
            row.setPadding(0, (int) (10 * context.getResources().getDisplayMetrics().density),
                    0, (int) (10 * context.getResources().getDisplayMetrics().density));
            row.setOnClickListener(view -> {
                com.pegasuscorp.orbe.voice.AndroidTtsStore.setVoiceName(getContext(), v.name);
                PiperModelStore.setUsePiper(getContext(), false);
                refreshTtsStatus();
                rebuildSystemVoiceRows();
                if (listener != null) listener.onPiperVoiceChanged();
                android.widget.Toast.makeText(getContext(),
                        "Voix système : " + v.name, android.widget.Toast.LENGTH_SHORT).show();
            });
            list.addView(row);
        }
        if (shown == 0) {
            list.addView(hintLabel(context, "Aucune voix locale — désactive « local uniquement »."));
        }
    }

    /** Remplit le cache si SpeechOutput n'a pas encore tourné. */
    private void ensureAndroidVoiceCache() {
        if (!com.pegasuscorp.orbe.voice.AndroidTtsStore.getCachedVoices().isEmpty()) return;
        final Context app = getContext().getApplicationContext();
        final android.speech.tts.TextToSpeech[] holder = new android.speech.tts.TextToSpeech[1];
        holder[0] = new android.speech.tts.TextToSpeech(app, status -> {
            android.speech.tts.TextToSpeech tts = holder[0];
            if (tts == null) return;
            try {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts.setLanguage(java.util.Locale.FRENCH);
                    com.pegasuscorp.orbe.voice.AndroidTtsStore.updateCachedVoices(
                            com.pegasuscorp.orbe.voice.AndroidTtsStore.listFrenchVoices(tts, false));
                    mainHandler.post(this::rebuildSystemVoiceRows);
                }
            } finally {
                try { tts.shutdown(); } catch (Exception ignored) {}
            }
        });
    }

    private TextView hintLabel(Context context, String text) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(Color.parseColor("#66FFFFFF"));
        label.setTextSize(11);
        label.setPadding(0, 0, 0, (int) (6 * density));
        return label;
    }

    private EditText addApiKeyField(Context context, String hint) {
        float density = context.getResources().getDisplayMetrics().density;
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setHintTextColor(Color.parseColor("#55FFFFFF"));
        field.setTextColor(Color.WHITE);
        field.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        field.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        boolean secret = hint.startsWith("Clé") || hint.startsWith("Client ID");
        field.setSingleLine(true);
        // Intitulé permanent : le hint disparaît dès que le champ est rempli.
        TextView caption = new TextView(context);
        caption.setText(hint);
        caption.setTextColor(Color.parseColor("#AAFFFFFF"));
        caption.setTextSize(12);
        caption.setPadding(0, (int) (8 * density), 0, 0);
        body.addView(caption, wrapLp());
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = (int) (6 * density);
        if (!secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT);
            body.addView(field, lp);
            return field;
        }
        // setSingleLine() remplace la transformation : masquer APRÈS (sinon clés en clair).
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(field, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView eye = new TextView(context);
        eye.setText("👁");
        eye.setTextSize(18);
        eye.setAlpha(0.5f);
        eye.setPadding((int) (12 * density), (int) (8 * density), (int) (8 * density), (int) (8 * density));
        eye.setContentDescription("Afficher ou masquer la clé");
        eye.setOnClickListener(v -> {
            boolean hidden = field.getTransformationMethod()
                    instanceof android.text.method.PasswordTransformationMethod;
            field.setTransformationMethod(hidden ? null
                    : android.text.method.PasswordTransformationMethod.getInstance());
            field.setSelection(field.getText().length());
            eye.setAlpha(hidden ? 1f : 0.5f);
        });
        wrap.addView(eye);
        body.addView(wrap, lp);
        return field;
    }

    private void loadApiKeysIntoFields() {
        if (groqKeyField != null) groqKeyField.setText(ApiKeyStore.getGroqKey(getContext()));
        if (cerebrasKeyField != null) cerebrasKeyField.setText(ApiKeyStore.getCerebrasKey(getContext()));
        if (openRouterKeyField != null) {
            openRouterKeyField.setText(ApiKeyStore.getOpenRouterKey(getContext()));
        }
        if (geminiKeyField != null) geminiKeyField.setText(ApiKeyStore.getGeminiKey(getContext()));
        if (userCityField != null) userCityField.setText(ApiKeyStore.getUserCity(getContext()));
        if (userCoordsField != null) userCoordsField.setText(ApiKeyStore.getUserCoords(getContext()));
        if (tavilyKeyField != null) tavilyKeyField.setText(ApiKeyStore.getTavilyKey(getContext()));
        if (braveKeyField != null) braveKeyField.setText(ApiKeyStore.getBraveKey(getContext()));
        if (newsKeyField != null) newsKeyField.setText(ApiKeyStore.getNewsApiKey(getContext()));
        if (spotifyClientIdField != null) {
            spotifyClientIdField.setText(ApiKeyStore.getSpotifyClientId(getContext()));
        }
        if (nasaKeyField != null) nasaKeyField.setText(ApiKeyStore.getNasaApiKey(getContext()));
    }

    private String spotifyOrbShortcutLabel(Context context) {
        if (!ShortcutStore.isSpotifyInstalled(context)) {
            return "Spotify non installé sur ce téléphone";
        }
        return ShortcutStore.isSpotifyPinned(context)
                ? "Retirer Spotify du menu de l'orbe"
                : "Ajouter Spotify au menu de l'orbe";
    }

    private void toggleSpotifyOrbShortcut() {
        Context context = getContext();
        if (!ShortcutStore.isSpotifyInstalled(context)) {
            android.widget.Toast.makeText(context,
                    "Installe l'app Spotify d'abord",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (ShortcutStore.isSpotifyPinned(context)) {
            for (int i = 0; i < ShortcutStore.SLOT_COUNT; i++) {
                if ("com.spotify.music".equals(ShortcutStore.getPackage(context, i))) {
                    ShortcutStore.clearSlot(context, i);
                }
            }
            android.widget.Toast.makeText(context,
                    "Spotify retiré du menu de l'orbe",
                    android.widget.Toast.LENGTH_SHORT).show();
        } else if (ShortcutStore.pinSpotify(context)) {
            android.widget.Toast.makeText(context,
                    "Spotify ajouté en haut de l'orbe — tape l'orbe pour le voir",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        notifyChanged();
    }

    private String spotifyAuthButtonLabel(Context context) {
        return SpotifyAuthStore.isConnected(context)
                ? "Déconnecter Spotify"
                : "Connecter mon compte Spotify Premium";
    }

    private void toggleSpotifyConnection(Context context) {
        if (spotifyClientIdField != null) {
            ApiKeyStore.setSpotifyClientId(context, spotifyClientIdField.getText().toString());
        }
        if (SpotifyAuthStore.isConnected(context)) {
            SpotifyAuthStore.clear(context);
            android.widget.Toast.makeText(context, "Spotify déconnecté",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ApiKeyStore.hasSpotifyClientId(context)) {
            android.widget.Toast.makeText(context,
                    "Colle d'abord ton Client ID Spotify, puis enregistre.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            SpotifyAuthHelper.launchAuthorization(context);
            android.widget.Toast.makeText(context,
                    "Connecte-toi dans le navigateur…",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(context, "Spotify : " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void saveApiKeys() {
        ApiKeyStore.setGroqKey(getContext(), groqKeyField.getText().toString());
        if (cerebrasKeyField != null) {
            ApiKeyStore.setCerebrasKey(getContext(), cerebrasKeyField.getText().toString());
        }
        if (openRouterKeyField != null) {
            ApiKeyStore.setOpenRouterKey(getContext(), openRouterKeyField.getText().toString());
        }
        ApiKeyStore.setGeminiKey(getContext(), geminiKeyField.getText().toString());
        ApiKeyStore.setUserCity(getContext(), userCityField != null ? userCityField.getText().toString() : "");
        ApiKeyStore.setUserCoords(getContext(), userCoordsField != null ? userCoordsField.getText().toString() : "");
        ApiKeyStore.setTavilyKey(getContext(), tavilyKeyField.getText().toString());
        if (braveKeyField != null) ApiKeyStore.setBraveKey(getContext(), braveKeyField.getText().toString());
        ApiKeyStore.setNewsApiKey(getContext(), newsKeyField.getText().toString());
        if (spotifyClientIdField != null) {
            ApiKeyStore.setSpotifyClientId(getContext(), spotifyClientIdField.getText().toString());
        }
        ApiKeyStore.setNasaApiKey(getContext(), nasaKeyField.getText().toString());

        String coords = userCoordsField != null ? userCoordsField.getText().toString() : "";
        String city   = userCityField   != null ? userCityField.getText().toString()   : "";
        String msg = "Clés sauvegardées"
                + (city.isEmpty()   ? "" : "\nVille : " + city)
                + (coords.isEmpty() ? "" : "\nGPS : "   + coords);
        android.widget.Toast.makeText(getContext(), msg,
                android.widget.Toast.LENGTH_LONG).show();

        if (listener != null) listener.onApiKeysSaved();
    }

    private void testLlmKeys() {
        saveApiKeys();
        android.content.Context ctx = getContext();
        android.widget.Toast.makeText(ctx, "Test des clés LLM…",
                android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.util.List<ProviderKeyProbe.Result> results = ProviderKeyProbe.probeChain(ctx);
            String report = ProviderKeyProbe.formatReport(results);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    new androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle("Test des clés LLM")
                            .setMessage(report)
                            .setPositiveButton("OK", null)
                            .show());
        }, "llm-key-probe").start();
    }

    private void addProviderRow(String provider, String label) {
        float density = getContext().getResources().getDisplayMetrics().density;
        TextView row = new TextView(getContext());
        row.setText(label);
        row.setTextColor(Color.WHITE);
        row.setTextSize(15);
        row.setPadding((int) (12 * density), (int) (12 * density),
                (int) (12 * density), (int) (12 * density));
        row.setTag("provider:" + provider);
        styleModelRow(row, provider.equals(CloudModelStore.getActiveProvider(getContext())));
        row.setOnClickListener(v -> {
            CloudModelStore.setActiveProvider(getContext(), provider);
            refreshProviderRows();
            rebuildCloudModelList();
            if (listener != null) listener.onCloudProviderSelected(provider);
        });
        LinearLayout.LayoutParams rowLp = wrapLp();
        rowLp.topMargin = (int) (8 * density);
        body.addView(row, rowLp);
    }

    private void refreshProviderRows() {
        String selected = CloudModelStore.getActiveProvider(getContext());
        for (View child : allRows()) {
            Object tag = child.getTag();
            if (tag instanceof String && ((String) tag).startsWith("provider:")) {
                String provider = ((String) tag).substring("provider:".length());
                styleModelRow(child, provider.equals(selected));
            }
        }
    }

    private void rebuildCloudModelList() {
        if (cloudModelList == null) return;
        cloudModelList.removeAllViews();
        String provider = CloudModelStore.getActiveProvider(getContext());
        String selected = CloudModelStore.getActiveModelId(getContext());
        float density = getContext().getResources().getDisplayMetrics().density;
        for (String[] entry : CloudModelStore.modelsForProvider(provider)) {
            TextView row = new TextView(getContext());
            row.setText(entry[1]);
            row.setTextColor(Color.WHITE);
            row.setTextSize(15);
            row.setPadding((int) (12 * density), (int) (12 * density),
                    (int) (12 * density), (int) (12 * density));
            row.setTag("cloud:" + entry[0]);
            styleModelRow(row, entry[0].equals(selected));
            row.setOnClickListener(v -> {
                CloudModelStore.setActiveModelId(getContext(), entry[0]);
                refreshCloudModelRows();
                if (listener != null) listener.onCloudModelSelected(entry[0]);
            });
            LinearLayout.LayoutParams rowLp = wrapLp();
            rowLp.topMargin = (int) (8 * density);
            cloudModelList.addView(row, rowLp);
        }
    }

    private void refreshCloudModelRows() {
        if (cloudModelList == null) return;
        String selected = CloudModelStore.getActiveModelId(getContext());
        for (int i = 0; i < cloudModelList.getChildCount(); i++) {
            View child = cloudModelList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && ((String) tag).startsWith("cloud:")) {
                String modelId = ((String) tag).substring("cloud:".length());
                styleModelRow(child, modelId.equals(selected));
            }
        }
    }

    private void styleModelRow(View row, boolean selected) {
        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(selected ? Color.parseColor("#2835D0DD") : Color.parseColor("#14FFFFFF"));
        if (selected) bg.setStroke((int) (1.5f * density), accentMiddle);
        row.setBackground(bg);
    }

    // ── Lignes « interrupteur » et « choix » ─────────────────────────────────

    private void addSwitchRow(String title, String summary, boolean checked,
                              java.util.function.Consumer<Boolean> onChange) {
        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (12 * density);
        row.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        row.setBackground(bg);
        row.addView(twoLines(context, title, summary), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch sw = new android.widget.Switch(context);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> onChange.accept(on));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = (int) (8 * density);
        body.addView(row, lp);
    }

    /** Ligne cliquable titre + valeur ; renvoie la vue de la valeur (pour la mettre à jour). */
    private TextView addNavRow(String title, String summary, Runnable action) {
        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (12 * density);
        row.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        row.setBackground(bg);
        LinearLayout texts = twoLines(context, title, summary);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextColor(Color.parseColor("#66FFFFFF"));
        chevron.setTextSize(20);
        row.addView(chevron);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = (int) (8 * density);
        body.addView(row, lp);
        return (TextView) texts.getChildAt(1);
    }

    private LinearLayout twoLines(Context context, String title, String summary) {
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        texts.addView(t);
        TextView s = new TextView(context);
        s.setText(summary);
        s.setTextColor(Color.parseColor("#88FFFFFF"));
        s.setTextSize(12);
        texts.addView(s);
        return texts;
    }

    private void choose(String title, String[] items, int checked,
                        java.util.function.IntConsumer onPick) {
        AlertDialog.Builder b = new AlertDialog.Builder(getContext()).setTitle(title);
        if (checked >= 0) {
            b.setSingleChoiceItems(items, checked, (d, i) -> {
                d.dismiss();
                onPick.accept(i);
            });
        } else {
            b.setItems(items, (d, i) -> onPick.accept(i));
        }
        b.setNegativeButton("Annuler", null).show();
    }

    private static int nearest(float[] values, float v) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) best = i;
        }
        return best;
    }

    private static int currentGateIndex(Context context, float[] values) {
        if (!com.pegasuscorp.orbe.voice.WakeVoiceGate.isEnabled(context)) return values.length - 1;
        float[] on = java.util.Arrays.copyOf(values, values.length - 1);
        return nearest(on, com.pegasuscorp.orbe.voice.WakeVoiceGate.getThreshold(context));
    }

    private String currentVoiceLabel(Context context) {
        if (PiperModelStore.usePiper(context)) {
            return "Piper · " + PiperModelStore.getSelectedVoice(context).label
                    + (PiperModelDownloader.isDownloading() ? " (téléchargement…)" : "");
        }
        String name = com.pegasuscorp.orbe.voice.AndroidTtsStore.getVoiceName(context);
        for (com.pegasuscorp.orbe.voice.AndroidTtsStore.VoiceInfo v
                : com.pegasuscorp.orbe.voice.AndroidTtsStore.getCachedVoices()) {
            if (v.name.equals(name)) return "Système · " + v.displayLabel();
        }
        return "Système" + (name.isEmpty() ? "" : " · " + name);
    }

    /** Une seule liste : voix Piper puis voix système (filtrées si hors ligne uniquement). */
    private void chooseVoice() {
        Context context = getContext();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        int checked = -1;
        boolean piper = PiperModelStore.usePiper(context);
        PiperModelStore.Voice selPiper = PiperModelStore.getSelectedVoice(context);
        for (PiperModelStore.Voice voice : PiperModelStore.ALL_VOICES) {
            if (piper && voice.id.equals(selPiper.id)) checked = labels.size();
            labels.add("Piper · " + voice.label
                    + (PiperModelStore.isVoiceReady(context, voice) ? "" : " (à télécharger)"));
            actions.add(() -> {
                PiperModelStore.setSelectedVoice(getContext(), voice);
                PiperModelStore.setUsePiper(getContext(), true);
                if (!PiperModelStore.isVoiceReady(getContext(), voice)) {
                    if (listener != null) listener.onDownloadPiperVoice(voice);
                } else if (listener != null) {
                    listener.onPiperVoiceChanged();
                }
                refreshPiperStatus();
            });
        }
        boolean localOnly = com.pegasuscorp.orbe.voice.AndroidTtsStore.preferLocalOnly(context);
        String selSys = com.pegasuscorp.orbe.voice.AndroidTtsStore.getVoiceName(context);
        for (com.pegasuscorp.orbe.voice.AndroidTtsStore.VoiceInfo v
                : com.pegasuscorp.orbe.voice.AndroidTtsStore.getCachedVoices()) {
            if (localOnly && v.networkRequired) continue;
            if (!piper && v.name.equals(selSys)) checked = labels.size();
            labels.add("Système · " + v.displayLabel());
            actions.add(() -> {
                com.pegasuscorp.orbe.voice.AndroidTtsStore.setVoiceName(getContext(), v.name);
                PiperModelStore.setUsePiper(getContext(), false);
                refreshTtsStatus();
                if (listener != null) listener.onPiperVoiceChanged();
            });
        }
        choose("Voix de Pégase", labels.toArray(new String[0]), Math.max(checked, 0),
                i -> actions.get(i).run());
    }

    private TextView addActionRow(String label, Runnable action) {
        float density = getContext().getResources().getDisplayMetrics().density;
        TextView row = new TextView(getContext());
        row.setText(label);
        row.setTextColor(Color.WHITE);
        row.setTextSize(15);
        row.setPadding((int) (12 * density), (int) (12 * density),
                (int) (12 * density), (int) (12 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(Color.parseColor("#14FFFFFF"));
        row.setBackground(bg);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams rowLp = wrapLp();
        rowLp.topMargin = (int) (8 * density);
        body.addView(row, rowLp);
        return row;
    }

    private void addIconPackRow(String packPkg, String label, Drawable packIcon) {
        float density = getContext().getResources().getDisplayMetrics().density;
        String selected = PersonalizationStore.getIconPack(getContext());
        boolean isSelected = packPkg.equals(selected == null ? "" : selected);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        row.setTag("pack:" + packPkg);

        ImageView preview = new ImageView(getContext());
        int previewSize = (int) (32 * density);
        if (packIcon != null) {
            preview.setImageDrawable(packIcon);
        } else {
            preview.setImageDrawable(buildPreviewIcon(previewSize, PersonalizationStore.ICON_SYSTEM));
        }
        row.addView(preview, new LinearLayout.LayoutParams(previewSize, previewSize));

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.leftMargin = (int) (12 * density);
        row.addView(tv, tvLp);

        stylePackRow(row, isSelected);
        row.setOnClickListener(v -> {
            PersonalizationStore.setIconPack(getContext(), packPkg);
            refreshIconPackRows();
            notifyChanged();
        });

        LinearLayout.LayoutParams rowLp = wrapLp();
        rowLp.topMargin = (int) (8 * density);
        iconPackList.addView(row, rowLp);
    }

    private void refreshIconPackRows() {
        String selected = PersonalizationStore.getIconPack(getContext());
        if (selected == null) selected = "";
        for (int i = 0; i < iconPackList.getChildCount(); i++) {
            View child = iconPackList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                String packPkg = ((String) tag).substring("pack:".length());
                stylePackRow(child, packPkg.equals(selected));
            }
        }
    }

    private void stylePackRow(View row, boolean selected) {
        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(selected ? Color.parseColor("#2835D0DD") : Color.parseColor("#14FFFFFF"));
        if (selected) bg.setStroke((int) (1.5f * density), accentMiddle);
        row.setBackground(bg);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setAccentColor(int middle) {
        accentMiddle = middle;
        refreshIconThemeRows();
        refreshIconPackRows();
    }

    public void show() {
        animate().cancel();
        Trace.init(getContext().getApplicationContext());
        DiagScriptRunner.get().setListener(scriptUiListener);
        rebuildIconPackList();
        loadApiKeysIntoFields();
        refreshProviderRows();
        refreshCloudModelRows();
        refreshPiperStatus();
        refreshScriptUi();
        if (prefetchAlarmBtn != null) {
            prefetchAlarmBtn.setText(prefetchAlarmLabel(getContext()));
        }
        if (menuPage != null) showMenu();
        setVisibility(VISIBLE);
        bringToFront();
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).setListener(null).start();
    }

    public void hide() {
        animate().cancel();
        DiagScriptRunner.get().setListener(null);
        animate().alpha(0f).setDuration(140).setListener(null).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
        }).start();
    }

    public boolean isVisiblePanel() {
        return getVisibility() == VISIBLE;
    }

    @Override
    protected void onDetachedFromWindow() {
        DiagScriptRunner.get().setListener(null);
        diagIo.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void notifyChanged() {
        if (listener != null) listener.onPersonalizationChanged();
    }

    private void rebuildColorRow() {
        Context context = getContext();
        colorRow.removeAllViews();
        int selected = PersonalizationStore.getColorIndex(context);
        int gap = (int) (10 * context.getResources().getDisplayMetrics().density);
        for (int i = 0; i < OrbThemes.ALL.length; i++) {
            OrbThemes.Palette palette = OrbThemes.ALL[i];
            View swatch = buildColorSwatch(palette, i == selected);
            final int index = i;
            swatch.setOnClickListener(v -> {
                PersonalizationStore.setColorIndex(context, index);
                accentMiddle = palette.middle;
                rebuildColorRow();
                refreshIconThemeRows();
                notifyChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(colorSizePx, colorSizePx);
            if (i > 0) lp.leftMargin = gap;
            colorRow.addView(swatch, lp);
        }
    }

    private View buildColorSwatch(OrbThemes.Palette palette, boolean selected) {
        Context context = getContext();
        FrameLayout wrap = new FrameLayout(context);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColors(new int[]{palette.core, palette.middle, palette.edge});
        fill.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        fill.setGradientRadius(colorSizePx * 0.7f);
        View circle = new View(context);
        circle.setBackground(fill);
        wrap.addView(circle, new FrameLayout.LayoutParams(colorSizePx, colorSizePx));

        if (selected) {
            View ring = new View(context);
            GradientDrawable stroke = new GradientDrawable();
            stroke.setShape(GradientDrawable.OVAL);
            stroke.setStroke((int) (2 * context.getResources().getDisplayMetrics().density),
                    Color.WHITE);
            ring.setBackground(stroke);
            int ringPad = (int) (3 * context.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(
                    colorSizePx + ringPad * 2, colorSizePx + ringPad * 2);
            ringLp.gravity = Gravity.CENTER;
            wrap.addView(ring, ringLp);
        }
        return wrap;
    }

    private void addIconThemeRow(int theme, String label) {
        float density = getContext().getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        row.setTag(theme);

        ImageView preview = new ImageView(getContext());
        int previewSize = (int) (32 * density);
        preview.setImageDrawable(buildPreviewIcon(previewSize, theme));
        row.addView(preview, new LinearLayout.LayoutParams(previewSize, previewSize));

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.leftMargin = (int) (12 * density);
        row.addView(tv, tvLp);

        row.setOnClickListener(v -> {
            PersonalizationStore.setIconTheme(getContext(), theme);
            refreshIconThemeRows();
            notifyChanged();
        });

        LinearLayout.LayoutParams rowLp = wrapLp();
        rowLp.topMargin = (int) (8 * density);
        styleIconThemeRow(row, theme == PersonalizationStore.getIconTheme(getContext()));
        body.addView(row, rowLp);
    }

    private void refreshIconThemeRows() {
        int selected = PersonalizationStore.getIconTheme(getContext());
        for (View child : allRows()) {
            if (child.getTag() instanceof Integer) {
                styleIconThemeRow(child, ((Integer) child.getTag()) == selected);
            }
        }
    }

    private void styleIconThemeRow(View row, boolean selected) {
        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(selected ? Color.parseColor("#2835D0DD") : Color.parseColor("#14FFFFFF"));
        if (selected) {
            bg.setStroke((int) (1.5f * density), accentMiddle);
        }
        row.setBackground(bg);
    }

    private Drawable buildPreviewIcon(int size, int theme) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(Color.parseColor("#55FFFFFF"));
        if (theme == PersonalizationStore.ICON_ROUND) {
            base.setShape(GradientDrawable.OVAL);
            return base;
        }
        if (theme == PersonalizationStore.ICON_ROUNDED_SQUARE) {
            base.setCornerRadius(size * 0.22f);
            return base;
        }
        base.setCornerRadius(4);
        return base;
    }

    private TextView majorSectionTitle(Context context, String text) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#35D0DD"));
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, (int) (4 * density), 0, (int) (8 * density));
        return tv;
    }

    private TextView addSubHeading(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#AAFFFFFF"));
        tv.setTextSize(13);
        tv.setPadding(0, (int) (6 * context.getResources().getDisplayMetrics().density), 0, 0);
        return tv;
    }

    private void addSectionSeparator(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        View sep = new View(context);
        sep.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * density));
        lp.topMargin = (int) (16 * density);
        lp.bottomMargin = (int) (8 * density);
        body.addView(sep, lp);
    }

    private View spacer(Context context, int h) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ── Diagnostic ──────────────────────────────────────────────────────────────

    private void refreshScriptUi() {
        if (scriptStatus == null || scriptRunBtn == null) return;
        Context ctx = getContext();
        DiagScriptRunStore.syncRunning(ctx, DiagScriptRunner.get().isRunning());
        boolean running = DiagScriptRunner.get().isRunning();
        scriptRunBtn.setEnabled(!running);
        scriptRunBtn.setText(running ? "Mini-tests en cours…" : "Lancer mini-tests diagnostic");
        scriptStatus.setText(DiagScriptRunStore.statusLine(ctx));
    }

    private void startMiniDiagSuite() {
        if (DiagScriptRunner.get().isRunning()) {
            android.widget.Toast.makeText(getContext(),
                    "Suite déjà en cours — voir le statut ci-dessous.",
                    android.widget.Toast.LENGTH_SHORT).show();
            refreshScriptUi();
            return;
        }
        scriptRunBtn.setEnabled(false);
        refreshScriptUi();
        android.widget.Toast.makeText(getContext(),
                "Mini-tests lancés (~2 min). Tu peux quitter cet écran — le test continue.",
                android.widget.Toast.LENGTH_LONG).show();
        DiagScriptRunner.get().runMiniSuite(getContext().getApplicationContext(), scriptUiListener);
    }

    private void showScriptResult(DiagScriptResult result) {
        refreshScriptUi();
        String title = result.clean ? "Trace propre" : "Problèmes détectés";
        if (scriptStatus != null) scriptStatus.setText(result.summaryLine());
        StringBuilder body = new StringBuilder();
        body.append(result.summaryLine()).append("\n\n");
        if (result.clean) {
            body.append("Aucune anomalie dans la session stress. Historique isolé (mini_diag_v2).\n");
        } else {
            body.append("Consulte diag/orbe-diag-report.json (section stress) pour le détail "
                    + "des anomalies.\n");
        }
        body.append("\nDurée totale : ").append(result.durationMs / 1000).append(" s");
        Context ctx = getContext();
        if (ctx instanceof Activity) {
            new AlertDialog.Builder((Activity) ctx)
                    .setTitle(title)
                    .setMessage(body.toString())
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Partager rapport", (d, w) -> shareDiagReport())
                    .show();
        }
    }

    private String stressTestLabel() {
        return Trace.isStressTest() ? "Mode test trace (actif)" : "Mode test trace (inactif)";
    }

    private void toggleStressTest() {
        Trace.setStressTest(!Trace.isStressTest());
        if (stressTestBtn != null) stressTestBtn.setText(stressTestLabel());
        android.widget.Toast.makeText(getContext(),
                Trace.isStressTest()
                        ? "Mode test activé — événements marqués stress"
                        : "Mode test désactivé — usage réel",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void runDiagToast() {
        android.widget.Toast.makeText(getContext(), "Analyse en cours…",
                android.widget.Toast.LENGTH_SHORT).show();
        diagIo.execute(() -> {
            try {
                java.io.File report = DiagReport.generate(getContext().getApplicationContext());
                String raw = new String(java.nio.file.Files.readAllBytes(report.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(raw);
                int anomalies = json.optInt("anomalies_total", 0);
                int events = json.optInt("events_real", json.optInt("events_total", 0));
                int stressEvents = json.optInt("events_stress", 0);
                int stressAnomalies = 0;
                JSONObject stress = json.optJSONObject("stress");
                if (stress != null) stressAnomalies = stress.optInt("anomalies_total", 0);
                final String toastMsg;
                if (stressEvents > 0) {
                    toastMsg = anomalies + " anomalie(s) réelles sur " + events + " événements"
                            + " · stress : " + stressAnomalies + " sur " + stressEvents
                            + " — diag/orbe-diag-report.json";
                } else {
                    toastMsg = anomalies + " anomalie(s) réelles sur " + events + " événements"
                            + " — diag/orbe-diag-report.json";
                }
                mainHandler.post(() -> android.widget.Toast.makeText(getContext(), toastMsg,
                        android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> android.widget.Toast.makeText(getContext(),
                        "Diag : " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmClearTraceFiles() {
        Context ctx = getContext();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle("Supprimer les traces ?")
                .setMessage("Efface trace.jsonl, le rapport diag et les archives locales. "
                        + "Cette action est définitive.")
                .setPositiveButton("Supprimer", (d, w) -> clearTraceFiles())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void clearTraceFiles() {
        Context ctx = getContext();
        if (ctx == null) return;
        Trace.clear(ctx.getApplicationContext());
        android.widget.Toast.makeText(ctx,
                "Traces effacées — prochaine session repart à zéro.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void shareTraceFile() {
        diagIo.execute(() -> {
            try {
                Trace.share(getContext().getApplicationContext());
            } catch (Exception e) {
                mainHandler.post(() -> android.widget.Toast.makeText(getContext(),
                        "Trace : " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void shareDiagReport() {
        diagIo.execute(() -> {
            try {
                DiagReport.generateAndShare(getContext().getApplicationContext());
            } catch (Exception e) {
                mainHandler.post(() -> android.widget.Toast.makeText(getContext(),
                        "Partage : " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }
}
