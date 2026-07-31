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
    private LinearLayout body;
    private LinearLayout colorRow;
    private LinearLayout iconPackList;
    private LinearLayout cloudModelList;
    private EditText groqKeyField;
    private EditText cerebrasKeyField;
    private EditText openRouterKeyField;
    private EditText geminiKeyField;
    private EditText tavilyKeyField;
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

        setBackgroundColor(Color.parseColor("#E60B0E14"));
        setClickable(true);
        setFocusable(true);
        setVisibility(GONE);
        setElevation(24f);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 1. Apparence ──────────────────────────────────────────────────────
        body.addView(majorSectionTitle(context, "Apparence"));

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

        // ── 2. Launcher ─────────────────────────────────────────────────────────
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Launcher"));
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

        // ── 3. Pégase Cerveau ───────────────────────────────────────────────────
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Pégase Cerveau"));

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
        body.addView(addSubHeading(context, "Ma localisation (météo)"));
        body.addView(hintLabel(context,
                "Ville : nom affiché · GPS : latitude,longitude (ex: 46.1083,5.8261)\n"
                + "Le GPS est prioritaire si renseigné — utile pour les petites communes."));
        userCityField  = addApiKeyField(context, "Ma ville (ex: Valserhône)");
        userCoordsField = addApiKeyField(context, "Coordonnées GPS (lat,lon)");

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Clés API"));
        body.addView(hintLabel(context,
                "Rotation LLM : console.groq.com · cloud.cerebras.ai · openrouter.ai"));
        groqKeyField = addApiKeyField(context, "Clé Groq (gsk_...)");
        cerebrasKeyField = addApiKeyField(context, "Clé Cerebras");
        openRouterKeyField = addApiKeyField(context, "Clé OpenRouter (sk-or-...)");
        body.addView(hintLabel(context,
                "Vision (image / PDF) : même clé OpenRouter — menu Discussion → Analyser."));
        body.addView(hintLabel(context, "Gemini (hors rotation) : aistudio.google.com"));
        geminiKeyField = addApiKeyField(context, "Clé Gemini (AIza...)");
        body.addView(hintLabel(context, "Recherche : tavily.com · Actualités : newsapi.org"));
        tavilyKeyField = addApiKeyField(context, "Clé Tavily (tvly-...)");
        newsKeyField = addApiKeyField(context, "Clé NewsAPI (pub_...)");
        body.addView(hintLabel(context, "NASA : api.nasa.gov"));
        nasaKeyField = addApiKeyField(context, "Clé NASA (DEMO_KEY par défaut)");
        addActionRow("Enregistrer les clés", this::saveApiKeys);
        addActionRow("Tester Groq / Cerebras / OpenRouter", this::testLlmKeys);
        addActionRow("Tester la discussion (texte)", () -> {
            if (listener != null) listener.onOpenChatTest();
        });

        // ── 4. Pégase Voix ──────────────────────────────────────────────────────
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Pégase Voix"));

        body.addView(addSubHeading(context, "Dictionnaire vocal"));
        body.addView(hintLabel(context,
                "Prononciation Piper/TTS — modifiable aussi à la voix "
                        + "(« dis Qwen comme Couène », « vitesse à 85 % »)."));
        addActionRow("Voir et modifier la prononciation", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), SpeechRulesSettingsActivity.class)));

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Compréhension vocale"));
        body.addView(hintLabel(context,
                "Corrections STT apprises — modifiables aussi à la voix "
                        + "(« non, je voulais dire… »)."));
        addActionRow("Voir et modifier les corrections vocales", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), VoiceCorrectionsSettingsActivity.class)));
        body.addView(hintLabel(context,
                "Apprends à me connaître — le routeur mémorise tes formulations confirmées "
                        + "(pas le LLM)."));
        addActionRow(com.pegasuscorp.orbe.voice.LearnModeStore.isEnabled(context)
                ? "Désactiver « Apprends à me connaître »"
                : "Activer « Apprends à me connaître »", () -> {
            boolean enable = !com.pegasuscorp.orbe.voice.LearnModeStore.isEnabled(context);
            com.pegasuscorp.orbe.voice.LearnModeStore.setEnabled(context, enable);
            android.widget.Toast.makeText(context,
                    enable
                            ? "Pégase va apprendre ta façon de parler"
                            : "Apprentissage du routeur désactivé",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        addActionRow("Voir mon corpus personnel", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), KnowMeSettingsActivity.class)));
        body.addView(hintLabel(context,
                "Routing UserExamples — importe une conversation, valide phrase → outil "
                        + "(tous les outils du registre). Matching sémantique avant les règles "
                        + "hardcodées (~30 j d'usage)."));
        addActionRow("Apprentissage du routing", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), RoutingLearningActivity.class)));

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Mot d'éveil « Pégase »"));
        body.addView(hintLabel(context,
                "Écoute via la reconnaissance vocale Android (plus de wake keyword natif). "
                        + "Chaque activation du micro peut provoquer un court lag — "
                        + "le mode doux espace les sessions. "
                        + "Pendant une vidéo ou de la musique, l'écoute se met en pause."));
        addActionRow(com.pegasuscorp.orbe.voice.PegaseWakeStore.isEnabled(context)
                ? "Désactiver l'écoute « Pégase »"
                : "Activer l'écoute « Pégase »", () -> {
            boolean enable = !com.pegasuscorp.orbe.voice.PegaseWakeStore.isEnabled(context);
            com.pegasuscorp.orbe.voice.PegaseWakeStore.setEnabled(context, enable);
            PegaseWakeService.sync(context);
            android.widget.Toast.makeText(context,
                    enable ? "Pégase écoute en arrière-plan" : "Écoute désactivée",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        addActionRow(com.pegasuscorp.orbe.voice.PegaseWakeStore.isGentleMode(context)
                ? "Mode doux ON — moins de lag (moins réactif)"
                : "Mode doux OFF — wake plus réactif (plus de lag)", () -> {
            boolean gentle = !com.pegasuscorp.orbe.voice.PegaseWakeStore.isGentleMode(context);
            com.pegasuscorp.orbe.voice.PegaseWakeStore.setGentleMode(context, gentle);
            com.pegasuscorp.orbe.voice.VoiceWakeClient.get().setGentleMode(context, gentle);
            PegaseWakeService.sync(context);
            android.widget.Toast.makeText(context,
                    gentle ? "Mode doux : sessions micro espacées"
                            : "Mode réactif : wake plus vif, lag possible",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        addActionRow(com.pegasuscorp.orbe.voice.VoiceMuteStore.isMuted(context)
                ? "Réactiver le micro Pégase"
                : "Couper le micro Pégase (global)", () -> {
            boolean muted = !com.pegasuscorp.orbe.voice.VoiceMuteStore.isMuted(context);
            com.pegasuscorp.orbe.voice.VoiceMuteStore.setMuted(context, muted);
            PegaseWakeService.sync(context);
            android.widget.Toast.makeText(context,
                    muted ? "Micro coupé — wake et discussion désactivés"
                            : "Micro réactivé",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Wake local (Sherpa)"));
        body.addView(hintLabel(context,
                "Remplace la boucle STT par un mot d'éveil on-device (~15 Mo). "
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
                                for (int i = 0; i < body.getChildCount(); i++) {
                                    android.view.View child = body.getChildAt(i);
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
                                for (int i = 0; i < body.getChildCount(); i++) {
                                    android.view.View child = body.getChildAt(i);
                                    if ("kws_status".equals(child.getTag())) {
                                        ((TextView) child).setText(
                                                com.pegasuscorp.orbe.voice.KwsModelStore
                                                        .statusLabel(getContext()));
                                    }
                                }
                                android.widget.Toast.makeText(getContext(), message,
                                        android.widget.Toast.LENGTH_LONG).show();
                                if (success) {
                                    com.pegasuscorp.orbe.PegaseWakeService.sync(getContext());
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
            PegaseWakeService.sync(context);
        });

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Ma voix"));
        body.addView(hintLabel(context,
                "Enregistre ta voix pour que seul toi puisses activer Pégase "
                        + "(mot d'éveil ou discussion)."));
        TextView speakerStatus = new TextView(context);
        speakerStatus.setTextColor(Color.parseColor("#88FFFFFF"));
        speakerStatus.setTextSize(12);
        int samples = com.pegasuscorp.orbe.voice.SpeakerProfileStore.getInstance(context)
                .getSampleCount();
        speakerStatus.setText(com.pegasuscorp.orbe.voice.SpeakerModelStore.statusLabel(context)
                + " · " + (samples >= 3 ? "empreinte OK" : "empreinte " + samples + "/3"));
        body.addView(speakerStatus, wrapLp());
        addActionRow("Enregistrer ma voix", () ->
                getContext().startActivity(
                        new android.content.Intent(getContext(), SpeakerEnrollmentActivity.class)));
        com.pegasuscorp.orbe.voice.SpeakerProfileStore speakerProfile =
                com.pegasuscorp.orbe.voice.SpeakerProfileStore.getInstance(context);
        addActionRow(speakerProfile.isRequireOwnerVoice()
                ? "Désactiver « Dis Pégase pour confirmer »"
                : "Activer la vérification vocale", () -> {
            speakerProfile.setRequireOwnerVoice(!speakerProfile.isRequireOwnerVoice());
            android.widget.Toast.makeText(context,
                    speakerProfile.isRequireOwnerVoice()
                            ? "Vérification vocale activée"
                            : "Vérification vocale désactivée",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        body.addView(spacer(context, (int) (12 * density)));
        body.addView(addSubHeading(context, "Synthèse vocale"));
        body.addView(hintLabel(context,
                "Piper (local) ou TTS système Google. La voix système est choisie par "
                        + "son nom stable (pas un numéro). Repli Piper si la voix manque."));
        TextView ttsEngineStatus = new TextView(context);
        ttsEngineStatus.setTextColor(Color.parseColor("#88FFFFFF"));
        ttsEngineStatus.setTextSize(12);
        ttsEngineStatus.setTag("tts_engine_status");
        ttsEngineStatus.setText(com.pegasuscorp.orbe.voice.AndroidTtsStore.statusLabel(context)
                + "\n" + PiperModelStore.statusLabel(context));
        body.addView(ttsEngineStatus, wrapLp());

        addActionRow(PiperModelStore.usePiper(context)
                ? "Moteur actif : Piper — passer au système"
                : "Moteur actif : Système — passer à Piper", () -> {
            boolean nextPiper = !PiperModelStore.usePiper(getContext());
            PiperModelStore.setUsePiper(getContext(), nextPiper);
            refreshTtsStatus();
            if (listener != null) listener.onPiperVoiceChanged();
            android.widget.Toast.makeText(getContext(),
                    nextPiper ? "Moteur : Piper" : "Moteur : TTS système",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        addActionRow(com.pegasuscorp.orbe.voice.AndroidTtsStore.preferLocalOnly(context)
                ? "Voix système : local uniquement ✓"
                : "Voix système : local + réseau ✓", () -> {
            boolean next = !com.pegasuscorp.orbe.voice.AndroidTtsStore.preferLocalOnly(getContext());
            com.pegasuscorp.orbe.voice.AndroidTtsStore.setPreferLocalOnly(getContext(), next);
            refreshTtsStatus();
            rebuildSystemVoiceRows();
            if (listener != null) listener.onPiperVoiceChanged();
        });

        body.addView(hintLabel(context, "Voix système françaises :"));
        LinearLayout systemVoiceList = new LinearLayout(context);
        systemVoiceList.setOrientation(LinearLayout.VERTICAL);
        systemVoiceList.setTag("system_voice_list");
        body.addView(systemVoiceList, wrapLp());
        rebuildSystemVoiceRows();

        body.addView(spacer(context, (int) (8 * density)));
        body.addView(addSubHeading(context, "Voix Piper"));
        TextView piperStatus = new TextView(context);
        piperStatus.setTextColor(Color.parseColor("#88FFFFFF"));
        piperStatus.setTextSize(12);
        piperStatus.setTag("piper_status");
        piperStatus.setText(PiperModelStore.statusLabel(context));
        body.addView(piperStatus, wrapLp());
        PiperModelStore.Voice selectedVoice = PiperModelStore.getSelectedVoice(context);
        for (PiperModelStore.Voice voice : PiperModelStore.ALL_VOICES) {
            boolean active = voice.id.equals(selectedVoice.id);
            boolean ready = PiperModelStore.isVoiceReady(context, voice);
            String label = (active ? "✓ " : "") + voice.label
                    + (ready ? " (installée)" : " (non installée)");
            addActionRow(label, () -> {
                PiperModelStore.setSelectedVoice(getContext(), voice);
                PiperModelStore.setUsePiper(getContext(), true);
                if (!PiperModelStore.isVoiceReady(getContext(), voice)) {
                    if (listener != null) listener.onDownloadPiperVoice(voice);
                } else {
                    if (listener != null) listener.onPiperVoiceChanged();
                }
                refreshTtsStatus();
                refreshPiperStatus();
            });
        }
        addActionRow("Importer modèle Piper (ZIP)", () -> {
            if (listener != null) listener.onImportPiperZip();
        });
        addActionRow("Importer dossier Piper", () -> {
            if (listener != null) listener.onImportPiperFolder();
        });
        body.addView(hintLabel(context,
                PiperModelDownloader.isDownloading()
                        ? "Téléchargement en cours…"
                        : "Voix Piper : " + PiperModelStore.getSelectedVoice(context).label));

        piperSpeedLabel = new TextView(context);
        piperSpeedLabel.setTextColor(Color.WHITE);
        piperSpeedLabel.setTextSize(14);
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
        body.addView(hintLabel(context,
                "Vitesse plus lente = articulation plus claire (tirets, fin de mots)."));

        // ── 5. Pégase Mémoire ──────────────────────────────────────────────────
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Pégase Mémoire"));
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

        // ── 6. Services connectés ───────────────────────────────────────────────
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Services connectés"));

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

        // ── 7. Diagnostic ───────────────────────────────────────────────────────
        addSectionSeparator(context);
        body.addView(majorSectionTitle(context, "Diagnostic"));
        body.addView(hintLabel(context,
                "trace.jsonl + rapport d'anomalies — files/diag/ sur l'appareil"));
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
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            if ("piper_status".equals(child.getTag())) {
                ((TextView) child).setText(PiperModelStore.statusLabel(getContext()));
            }
        }
        refreshTtsStatus();
    }

    private void refreshTtsStatus() {
        if (body == null) return;
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            if ("tts_engine_status".equals(child.getTag())) {
                ((TextView) child).setText(
                        com.pegasuscorp.orbe.voice.AndroidTtsStore.statusLabel(getContext())
                                + "\n" + PiperModelStore.statusLabel(getContext()));
            }
        }
    }

    private void rebuildSystemVoiceRows() {
        if (body == null) return;
        LinearLayout list = null;
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            if ("system_voice_list".equals(child.getTag()) && child instanceof LinearLayout) {
                list = (LinearLayout) child;
                break;
            }
        }
        if (list == null) return;
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
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = (int) (6 * density);
        body.addView(field, lp);
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
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
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
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
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
