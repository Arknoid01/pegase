package com.pegasuscorp.orbe.search;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ApiKeyStore;

import java.io.File;

/**
 * Côté Android du moteur de recherche privé. Empreintes : mpnet int8, la MÊME instance que le
 * chatbot (ChatbotStore) — depuis 2026-11-27, à la place de MiniLM (banc figé phone-v3 :
 * 77,6 % contre 75,3 %, mêmes seuils ; un seul modèle en mémoire au lieu de deux).
 */
public final class MoteurPrive {

    private static final String TAG = "MoteurPrive";
    public static final String DOSSIER = com.pegasuscorp.orbe.chatbot.ChatbotStore.MODEL_DIR;
    private static final String HF_MPNET =
            "https://huggingface.co/Xenova/paraphrase-multilingual-mpnet-base-v2/resolve/main/";


    private MoteurPrive() {}

    /** Android/data/…/files/models/minilm-multi (dossier du bouton « Télécharger »). */
    public static File dossierModele(Context ctx) {
        File ext = ctx.getApplicationContext().getExternalFilesDir("models");
        File base = ext != null ? ext : new File(ctx.getFilesDir(), "models");
        return new File(base, DOSSIER);
    }

    public static boolean modeleInstalle(Context ctx) {
        return com.pegasuscorp.orbe.chatbot.ChatbotStore.modelInstalled(ctx);
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
            String modele = com.pegasuscorp.orbe.chatbot.ChatbotStore.MODEL_FILE;
            for (String f : new String[]{MultilingualEmbedder.TOKENIZER_FILE, modele}) {
                File cible = new File(dossierModele(app), f);
                if (cible.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    cible.delete();   // reste d'un essai interrompu
                }
                android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(
                        android.net.Uri.parse(HF_MPNET + (f.equals(modele) ? "onnx/" : "") + f))
                        .setTitle("Recherche privée Pégase — " + f)
                        .setDescription("Modèle de compréhension des pages (≈300 Mo au total)")
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

    /** Recherche complète, BLOQUANTE (fil de fond). */
    public static Dossier chercher(Context ctx, String question) throws Exception {
        MoteurRecherche.Reglages r = new MoteurRecherche.Reglages();
        r.cleBrave = ApiKeyStore.getBraveKey(ctx);
        r.termesPrives = ReglagesRecherche.termesPrives(ctx);
        if (ReglagesRecherche.reseauFacture(ctx)) {   // 4G : moitié moins de pages, pages plus courtes
            r.nPages = ReglagesRecherche.PAGES_4G;
            r.maxOctetsPage = ReglagesRecherche.OCTETS_4G;
        }
        final com.pegasuscorp.orbe.chatbot.ChatbotStore store =
                com.pegasuscorp.orbe.chatbot.ChatbotStore.of(ctx);
        store.hold();
        try {
            long t0 = System.currentTimeMillis();
            Dossier d = new MoteurRecherche(r, store::embedTexts).chercher(question);
            // Pas la question dans le journal système : seulement les mesures
            Log.i(TAG, "recherche " + (System.currentTimeMillis() - t0) + " ms, web=" + d.sourceWeb
                    + ", pages=" + d.pagesLues + "/" + r.nPages + ", " + (d.octetsPages / 1024) + " Ko"
                    + (r.nPages == ReglagesRecherche.PAGES_4G ? " (4G)" : " (Wi-Fi)")
                    + (d.retire.isEmpty() ? "" : ", retiré : " + d.retire.size())
                    + (d.refus != null ? ", REFUS (trop personnel)" : d.abstention ? ", abstention" : ""));
            Log.i(TAG, "passage 1 : " + (d.passages.isEmpty() ? "aucun" : d.passages.get(0).site)
                    + ", réponse extraite " + d.reponse.length() + " car.");
            return d;
        } finally {
            store.unhold();
        }
    }
}
