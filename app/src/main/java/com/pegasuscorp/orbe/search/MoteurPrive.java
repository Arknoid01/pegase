package com.pegasuscorp.orbe.search;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import java.io.File;

/**
 * Côté Android du moteur de recherche privé : modèle d'embeddings (≈250 Mo, téléchargé en
 * Wi-Fi depuis Hugging Face), chargé à la demande et libéré après 3 min sans recherche
 * (la RAM du téléphone est partagée avec Qwen).
 */
public final class MoteurPrive {

    private static final String TAG = "MoteurPrive";
    public static final String DOSSIER = "minilm-multi";
    private static final long INACTIF_MS = 3 * 60_000L;

    private static MultilingualEmbedder embedder;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable LIBERER = MoteurPrive::liberer;

    private MoteurPrive() {}

    /** Android/data/…/files/models/minilm-multi (dossier du bouton « Télécharger »). */
    public static File dossierModele(Context ctx) {
        File ext = ctx.getApplicationContext().getExternalFilesDir("models");
        File base = ext != null ? ext : new File(ctx.getFilesDir(), "models");
        return new File(base, DOSSIER);
    }

    public static boolean modeleInstalle(Context ctx) {
        return MultilingualEmbedder.isInstalled(dossierModele(ctx));
    }

    /** Prêt = modèle présent + clé Brave (sans Brave, le web Bing est trop pauvre : Tavily reste). */
    public static boolean pret(Context ctx) {
        return ApiKeyStore.hasBraveKey(ctx) && modeleInstalle(ctx);
    }

    /** Lance les deux téléchargements (DownloadManager, Wi-Fi seulement). @return false si impossible. */
    public static boolean telecharger(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    app.getSystemService(Context.DOWNLOAD_SERVICE);
            for (String f : new String[]{MultilingualEmbedder.TOKENIZER_FILE, MultilingualEmbedder.MODEL_FILE}) {
                File cible = new File(dossierModele(app), f);
                if (cible.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    cible.delete();   // reste d'un essai interrompu
                }
                android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(
                        android.net.Uri.parse(MultilingualEmbedder.HF_BASE + f))
                        .setTitle("Recherche privée Pégase — " + f)
                        .setDescription("Modèle de compréhension des pages (≈250 Mo au total)")
                        .setNotificationVisibility(
                                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(app, "models", DOSSIER + "/" + f)
                        .setAllowedOverMetered(false);
                dm.enqueue(req);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "téléchargement", e);
            return false;
        }
    }

    private static synchronized MultilingualEmbedder embedder(Context ctx) throws Exception {
        MAIN.removeCallbacks(LIBERER);
        if (embedder == null) {
            long t = System.currentTimeMillis();
            embedder = MultilingualEmbedder.open(dossierModele(ctx));
            Log.i(TAG, "modèle chargé en " + (System.currentTimeMillis() - t) + " ms");
        }
        MAIN.postDelayed(LIBERER, INACTIF_MS);
        return embedder;
    }

    private static synchronized void liberer() {
        if (embedder == null) return;
        try {
            embedder.close();
        } catch (Exception ignored) {
        }
        embedder = null;
        Log.i(TAG, "modèle libéré (inactif)");
    }

    /** Recherche complète, BLOQUANTE (fil de fond). */
    public static Dossier chercher(Context ctx, String question) throws Exception {
        MoteurRecherche.Reglages r = new MoteurRecherche.Reglages();
        r.cleBrave = ApiKeyStore.getBraveKey(ctx);
        final MultilingualEmbedder e = embedder(ctx);
        try {
            return new MoteurRecherche(r, e::embed).chercher(question);
        } finally {
            MAIN.removeCallbacks(LIBERER);
            MAIN.postDelayed(LIBERER, INACTIF_MS);
        }
    }
}
