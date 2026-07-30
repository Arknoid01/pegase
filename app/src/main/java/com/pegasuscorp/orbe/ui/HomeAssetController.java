package com.pegasuscorp.orbe.ui;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.pegasuscorp.orbe.AppDrawerPanel;
import com.pegasuscorp.orbe.AppListCache;
import com.pegasuscorp.orbe.ChatTestActivity;
import com.pegasuscorp.orbe.LauncherHelper;
import com.pegasuscorp.orbe.PersonalizationStore;
import com.pegasuscorp.orbe.WidgetBoardHelper;
import com.pegasuscorp.orbe.chat.CloudModelStore;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.llm.LlmEngineManager;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.voice.AndroidTtsStore;
import com.pegasuscorp.orbe.voice.PiperModelDownloader;
import com.pegasuscorp.orbe.voice.PiperModelImporter;
import com.pegasuscorp.orbe.voice.PiperModelStore;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;
import com.pegasuscorp.orbe.voice.VoiceOutputHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Imports HOME (fond d'écran, GGUF, Piper), personnalisation drawer,
 * et rechargement modèle / conversation.
 */
public final class HomeAssetController implements AppDrawerPanel.PersonalizationListener {

    public interface Host {
        AppCompatActivity activity();
        OrbUiController orbUi();
        VoiceOutputHandler voiceOutput();
        AppDrawerPanel drawerPanel();
        VoiceInputHandler voiceInput();
        PegaseSession pegaseSession();
        void setConversation(ConversationManager conversation);
        HomeLauncherActions homeLaunchers();
    }

    private final Host host;
    private final ExecutorService importIo = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> galleryWallpaperLauncher;
    private ActivityResultLauncher<String[]> ggufImportLauncher;
    private ActivityResultLauncher<String[]> piperZipLauncher;
    private ActivityResultLauncher<Uri> piperFolderLauncher;

    public HomeAssetController(Host host) {
        this.host = host;
    }

    // —— PersonalizationListener ——

    @Override
    public void onPersonalizationChanged() {
        AppCompatActivity activity = host.activity();
        OrbUiController orbUi = host.orbUi();
        if (orbUi != null) orbUi.applyPersonalization();
        AppListCache.invalidate();
        AppListCache.warmUp(activity);
    }

    @Override
    public void onPickSystemWallpaper() {
        host.homeLaunchers().pickWallpaper();
    }

    @Override
    public void onPickGalleryWallpaper() {
        launchGalleryWallpaper();
    }

    @Override
    public void onResetWallpaper() {
        resetCustomWallpaper();
    }

    @Override
    public void onSetDefaultLauncher() {
        AppCompatActivity activity = host.activity();
        if (LauncherHelper.isDefaultLauncher(activity)) {
            Toast.makeText(activity, "Orbe est déjà votre écran d'accueil", Toast.LENGTH_SHORT).show();
        } else {
            LauncherHelper.requestDefaultLauncher(activity);
        }
    }

    @Override
    public void onResetWidgetBoard() {
        confirmResetWidgetBoard();
    }

    @Override
    public void onCloudProviderSelected(String provider) {
        recreateConversationBackend();
        Toast.makeText(host.activity(), CloudModelStore.displayNameForActive(host.activity()),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCloudModelSelected(String modelId) {
        recreateConversationBackend();
        Toast.makeText(host.activity(), CloudModelStore.displayNameForActive(host.activity()),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onApiKeysSaved() {
        recreateConversationBackend();
        Toast.makeText(host.activity(), "Clés API enregistrées", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onOpenChatTest() {
        host.activity().startActivity(new Intent(host.activity(), ChatTestActivity.class));
    }

    @Override
    public void onImportPiperZip() {
        launchPiperZipPicker();
    }

    @Override
    public void onImportPiperFolder() {
        launchPiperFolderPicker();
    }

    @Override
    public void onDownloadPiper() {
        startPiperDownload();
    }

    @Override
    public void onDownloadPiperVoice(PiperModelStore.Voice voice) {
        startPiperVoiceDownload(voice);
    }

    @Override
    public void onPiperVoiceChanged() {
        VoiceOutputHandler voiceOutput = host.voiceOutput();
        if (voiceOutput != null) {
            voiceOutput.reloadPiperModel();
            voiceOutput.applyAndroidTtsSettings();
        }
        Toast.makeText(host.activity(),
                PiperModelStore.usePiper(host.activity())
                        ? ("Voix Piper : " + PiperModelStore.getSelectedVoice(host.activity()).label)
                        : ("Voix système : " + AndroidTtsStore.getVoiceName(host.activity())),
                Toast.LENGTH_SHORT).show();
    }

    public ExecutorService executor() {
        return importIo;
    }

    public void shutdown() {
        importIo.shutdownNow();
    }

    public void registerLaunchers(AppCompatActivity activity) {
        galleryWallpaperLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) saveGalleryWallpaper(uri); });
        ggufImportLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importGgufModel(uri); });
        piperZipLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importPiperZip(uri); });
        piperFolderLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> { if (uri != null) importPiperFolder(uri); });
    }

    public void launchGalleryWallpaper() {
        galleryWallpaperLauncher.launch("image/*");
    }

    public void launchPiperZipPicker() {
        piperZipLauncher.launch(new String[] {"application/zip", "application/x-zip-compressed"});
    }

    public void launchPiperFolderPicker() {
        piperFolderLauncher.launch(null);
    }

    public void launchGgufPicker() {
        ggufImportLauncher.launch(new String[] {"application/octet-stream", "*/*"});
    }

    private File customWallpaperFile() {
        return new File(host.activity().getFilesDir(), "custom_wallpaper.jpg");
    }

    public void saveGalleryWallpaper(Uri uri) {
        AppCompatActivity activity = host.activity();
        File outFile = customWallpaperFile();
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IllegalStateException("Flux illisible");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            PersonalizationStore.setCustomWallpaperPath(activity, outFile.getAbsolutePath());
            OrbUiController orbUi = host.orbUi();
            if (orbUi != null) orbUi.applyPersonalization();
            Toast.makeText(activity, "Fond d'écran mis à jour", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Impossible d'importer l'image", Toast.LENGTH_SHORT).show();
        }
    }

    public void resetCustomWallpaper() {
        AppCompatActivity activity = host.activity();
        PersonalizationStore.clearCustomWallpaper(activity);
        File file = customWallpaperFile();
        if (file.exists()) file.delete();
        OrbUiController orbUi = host.orbUi();
        if (orbUi != null) orbUi.applyPersonalization();
        Toast.makeText(activity, "Fond d'écran système restauré", Toast.LENGTH_SHORT).show();
    }

    public void importGgufModel(Uri uri) {
        AppCompatActivity activity = host.activity();
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}

        String filename = "modele-importe.gguf";
        String segment = uri.getLastPathSegment();
        if (segment != null) {
            int slash = segment.lastIndexOf('/');
            if (slash >= 0) segment = segment.substring(slash + 1);
            if (segment.toLowerCase().endsWith(".gguf")) filename = segment;
        }

        File dest = new File(ModelStore.modelsDir(activity), filename);
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalStateException("Flux illisible");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            ModelStore.setCustomModelPath(activity, dest.getAbsolutePath());
            ModelStore.setActivePreset(activity, ModelStore.PRESET_CUSTOM);
            Toast.makeText(activity, "GGUF importé : " + filename, Toast.LENGTH_SHORT).show();
            ConversationManager conversation = host.pegaseSession().recreate(activity);
            host.setConversation(conversation);
            VoiceInputHandler voiceInput = host.voiceInput();
            if (voiceInput != null) voiceInput.setConversation(conversation);
            reloadLlmModel();
        } catch (Exception e) {
            Toast.makeText(activity, "Impossible d'importer le GGUF", Toast.LENGTH_LONG).show();
        }
    }

    public void importPiperZip(Uri uri) {
        AppCompatActivity activity = host.activity();
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        Toast.makeText(activity, "Import Piper en cours…", Toast.LENGTH_SHORT).show();
        importIo.execute(() -> {
            PiperModelImporter.Result result = PiperModelImporter.importZip(activity, uri);
            activity.runOnUiThread(() -> onPiperImportDone(result));
        });
    }

    public void importPiperFolder(Uri treeUri) {
        AppCompatActivity activity = host.activity();
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        Toast.makeText(activity, "Import Piper en cours…", Toast.LENGTH_SHORT).show();
        importIo.execute(() -> {
            PiperModelImporter.Result result = PiperModelImporter.importFolder(activity, treeUri);
            activity.runOnUiThread(() -> onPiperImportDone(result));
        });
    }

    public void onPiperImportDone(PiperModelImporter.Result result) {
        AppCompatActivity activity = host.activity();
        if (result.success) {
            VoiceOutputHandler voiceOutput = host.voiceOutput();
            if (voiceOutput != null) voiceOutput.reloadPiperModel();
            AppDrawerPanel drawerPanel = host.drawerPanel();
            if (drawerPanel != null) drawerPanel.refreshPiperStatus();
            Toast.makeText(activity, "✓ " + result.message, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show();
        }
    }

    public void startPiperDownload() {
        startPiperVoiceDownload(PiperModelStore.getSelectedVoice(host.activity()));
    }

    public void startPiperVoiceDownload(PiperModelStore.Voice voice) {
        AppCompatActivity activity = host.activity();
        if (PiperModelDownloader.isDownloading()) {
            Toast.makeText(activity, "Téléchargement Piper déjà en cours…", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "Téléchargement " + voice.label + " (~75 Mo)…", Toast.LENGTH_SHORT).show();
        PiperModelDownloader.download(activity, voice, new PiperModelDownloader.Callback() {
            @Override
            public void onProgress(int percent, String fileName) {
                activity.runOnUiThread(() -> {
                    AppDrawerPanel drawerPanel = host.drawerPanel();
                    if (drawerPanel != null) drawerPanel.refreshPiperStatus();
                });
            }

            @Override
            public void onComplete(PiperModelImporter.Result result) {
                activity.runOnUiThread(() -> {
                    VoiceOutputHandler voiceOutput = host.voiceOutput();
                    if (voiceOutput != null) voiceOutput.reloadPiperModel();
                    onPiperImportDone(result);
                });
            }
        });
    }

    public void maybeOfferPiperDownload() {
        AppCompatActivity activity = host.activity();
        if (PiperModelStore.isModelReady(activity)) return;
        if (!PiperModelStore.usePiper(activity)) return;
        if (PiperModelStore.wasDownloadOffered(activity)) return;
        PiperModelStore.markDownloadOffered(activity);
        new AlertDialog.Builder(activity)
                .setTitle("Voix Piper")
                .setMessage("Télécharger la voix française de Pégase ?\n"
                        + "Environ 75 Mo, une seule fois, via Wi-Fi de préférence.")
                .setPositiveButton("Télécharger", (d, w) -> startPiperDownload())
                .setNegativeButton("Plus tard", null)
                .show();
    }

    public void recreateConversationBackend() {
        VoiceInputHandler voiceInput = host.voiceInput();
        if (voiceInput != null) {
            voiceInput.recreateConversationBackend(host.pegaseSession());
            host.setConversation(voiceInput.getConversation());
        }
    }

    public void reloadLlmModel() {
        AppCompatActivity activity = host.activity();
        if (!ModelStore.useLocalLlm(activity)) return;
        LlmEngineManager.getInstance().reloadActiveModel(activity,
                new com.pegasuscorp.orbe.llm.LocalLlmEngine.LoadCallback() {
                    @Override
                    public void onLoaded() {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Modèle chargé : " + ModelStore.displayNameForPreset(
                                        ModelStore.getActivePreset(activity)),
                                Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onError(String error) {
                        activity.runOnUiThread(() -> Toast.makeText(
                                activity, error, Toast.LENGTH_LONG).show());
                    }
                });
    }

    public void confirmResetWidgetBoard() {
        AppCompatActivity activity = host.activity();
        new AlertDialog.Builder(activity)
                .setTitle("Réinitialiser l'écran widgets ?")
                .setMessage("Tous les widgets placés seront supprimés.")
                .setPositiveButton("Réinitialiser", (d, w) -> {
                    WidgetBoardHelper.resetAll(activity);
                    Toast.makeText(activity, "Écran widgets réinitialisé", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
