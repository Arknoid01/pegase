package com.pegasuscorp.orbe.search;

import java.util.ArrayList;
import java.util.List;

/** Résultat d'une recherche : passages classés, chiffres recoupés, réponse extraite. */
public final class Dossier {

    public static final class Passage {
        public final String texte, site, url;

        public Passage(String texte, String site, String url) {
            this.texte = texte;
            this.site = site;
            this.url = url;
        }
    }

    /** Phrase candidate ; sim < 0 = pas calculée (Python : p.get("sim", 0.3)). */
    public static final class Phrase {
        public final String texte, site;
        public final double sim, score;

        public Phrase(String texte, String site, double sim, double score) {
            this.texte = texte;
            this.site = site;
            this.sim = sim;
            this.score = score;
        }

        double simOu(double def) {
            return sim < 0 ? def : sim;
        }
    }

    public static final class Chiffre {
        public final String valeur, unite;
        public final List<String> sources;

        public Chiffre(String valeur, String unite, List<String> sources) {
            this.valeur = valeur;
            this.unite = unite;
            this.sources = sources;
        }
    }

    /** Question traitée (sans la proposition éventuelle). */
    public String question = "";
    public String questionOriginale = "";
    /** « …, Thomas Edison ? » → « Thomas Edison ». */
    public String proposition;
    public String requeteEnvoyee = "";
    public String refus;
    public final List<String> retire = new ArrayList<>();
    public final List<Passage> passages = new ArrayList<>();
    public final List<Chiffre> chiffres = new ArrayList<>();
    public final List<String> sources = new ArrayList<>();
    public int pagesLues;
    public boolean calculLocal;
    /** Source web principale utilisée (brave, bing…) ; vide si aucune. */
    public String sourceWeb = "";
    public long msRecherche, msPages, msTri;
    /** Octets téléchargés pour lire les pages. */
    public long octetsPages;

    /** Réponse extraite (phrase sourcée) — remplie par {@link Extraction#extractive}. */
    public String reponse = "";
    /** Vrai si la réponse est une abstention (« je n'ai rien trouvé… »). */
    public boolean abstention;
}
