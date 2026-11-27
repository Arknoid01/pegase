package com.pegasuscorp.orbe.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Partie RÉSEAU d'une recherche (format des collectes figées du prototype PC :
 * question, envoyee, retire, pages = [url, titre, texte, cache], proposition…).
 */
public final class Collecte {

    public static final class Page {
        public final String url, titre, texte;

        public Page(String url, String titre, String texte) {
            this.url = url;
            this.titre = titre;
            this.texte = texte;
        }
    }

    public String question = "";
    public String envoyee = "";
    public final List<String> retire = new ArrayList<>();
    public final List<Page> pages = new ArrayList<>();
    public String questionOriginale;
    /** Requête corrigée par Brave (faute de frappe), sinon vide. */
    public String corrigee = "";
    public String proposition;
    public String sourceWeb = "";
    /** Dossier déjà fait sans le web (refus de confidentialité, calcul local). */
    public Dossier direct;
    public long msRecherche, msPages;
    /** Octets téléchargés pour lire les pages (budget data). */
    public long octetsPages;
}
