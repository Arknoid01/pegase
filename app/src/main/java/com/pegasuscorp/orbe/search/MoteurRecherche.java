package com.pegasuscorp.orbe.search;

import static com.pegasuscorp.orbe.search.TexteFr.fold;
import static com.pegasuscorp.orbe.search.TexteFr.rx;
import static com.pegasuscorp.orbe.search.TexteFr.rxi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moteur de recherche privé de Pégase (portage du prototype PC /mnt/jeux/dev/recherche,
 * « mode téléphone ») : Brave (web) + Wikipédia + Bing Actu → lecture des pages sur le
 * téléphone → passages classés → phrase sourcée ou abstention honnête.
 * Mesuré sur le banc du PC (2026-11-23) : corpus 85,9 %, actu 100 %, inédites 80 %.
 */
public final class MoteurRecherche {

    public static final class Reglages {
        public String cleBrave = "";
        public List<String> termesPrives = Collections.emptyList();
        public int nPages = ReglagesRecherche.PAGES_WIFI;
        /** Taille maximale lue par page (budget data). */
        public int maxOctetsPage = ReglagesRecherche.OCTETS_WIFI;
        /** Budget lecture des pages (le PC : 4 s pour toutes). */
        public int msPages = 4_000;
    }

    private final Reglages reglages;
    private final Assemblage.Vecteurs vecteurs;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(10, r -> {
        Thread t = new Thread(r, "recherche-privee");
        t.setDaemon(true);
        return t;
    });

    public MoteurRecherche(Reglages reglages, Assemblage.Vecteurs vecteurs) {
        this.reglages = reglages;
        this.vecteurs = vecteurs;
    }

    /** Recherche complète (bloquante : appeler hors du fil principal). */
    public Dossier chercher(String question) throws Exception {
        Collecte col = collecter(question);
        Dossier d = Assemblage.assembler(col, vecteurs);
        Extraction.extractive(d, Extraction.phrasesCles(d, vecteurs));
        presentationWikipedia(d, col);
        return d;
    }

    private static final Pattern QUI_EST = Pattern.compile(
            "\\b(qui est|qui [ée]tait|c'est qui|qui c'est)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * « Qui est X ? » : la présentation de Wikipédia passe en tête (2 premières phrases), sinon
     * le modèle répond avec une phrase d'article people (« passionné, sensible et plein
     * d'élégance… », 2026-11-27). Seulement si le titre de l'article reprend un mot du nom demandé.
     */
    static void presentationWikipedia(Dossier d, Collecte col) {
        if (d == null || col == null || col.pages.isEmpty() || d.refus != null) return;
        String q = col.question == null ? "" : col.question;
        if (!QUI_EST.matcher(q).find()) return;
        Collecte.Page w = col.pages.get(0);
        if (w.url == null || !w.url.contains("wikipedia.org") || w.texte == null) return;
        if (!titreCorrespond(w.titre, q)) return;
        String intro = premieresPhrases(w.texte, 2);
        if (intro.length() < 40) return;
        Dossier.Passage p = new Dossier.Passage(intro, "fr.wikipedia.org", w.url);
        d.passages.removeIf(x -> x.url != null && x.url.equals(w.url));
        d.passages.add(0, p);
        // La « réponse extraite » est lue en premier par le modèle : pour « qui est », c'est la
        // définition de Wikipédia qui doit y être, pas la phrase la plus proche de la question.
        d.reponse = premieresPhrases(w.texte, 1);
        d.abstention = false;
    }

    static boolean titreCorrespond(String titre, String question) {
        if (titre == null) return false;
        java.util.Set<String> q = TexteFr.tokenSet(question);
        for (String mot : TexteFr.tokens(titre.replaceAll("\\(.*?\\)", " "))) {
            if (mot.length() >= 4 && q.contains(mot)) return true;
        }
        return false;
    }

    static String premieresPhrases(String texte, int n) {
        String t = texte.replaceAll("\\s+", " ").trim();
        int fin = 0;
        for (int k = 0; k < n; k++) {
            int i = t.indexOf(". ", fin);
            if (i < 0) return t.length() > 400 ? t.substring(0, 400).trim() : t;
            fin = i + 1;
        }
        return t.substring(0, fin).trim();
    }

    // ---------- collecte ----------

    public Collecte collecter(String question) throws Exception {
        long t0 = System.currentTimeMillis();
        String[] sp = Extraction.scinderProposition(question);
        if (sp[1] != null) {
            Collecte col = collecter(sp[0]);
            col.questionOriginale = question;
            col.proposition = sp[1];
            if (col.direct != null) {
                col.direct.questionOriginale = question;
                col.direct.proposition = sp[1];
            }
            return col;
        }
        Collecte col = new Collecte();
        col.question = question;
        String envoyee = Confidentialite.nettoyer(question, reglages.termesPrives, col.retire);
        if (envoyee == null) {
            Dossier d = new Dossier();
            d.question = question;
            d.questionOriginale = question;
            d.refus = "requête trop personnelle une fois nettoyée";
            d.retire.addAll(col.retire);
            col.direct = d;
            return col;
        }
        Dossier calc = Extraction.dossierCalcul(question, col.retire);
        if (calc != null) {
            col.direct = calc;
            return col;
        }
        col.envoyee = envoyee;
        boolean actu = estActualite(question);
        final List<String> reformulees = requetesTelephone(envoyee);
        final String sansCadre = reformulees.get(reformulees.size() - 1);
        final boolean brave = reglages.cleBrave != null && !reglages.cleBrave.isEmpty();

        // Web + Wikipédia + Actu en parallèle
        List<Future<List<SourcesWeb.Resultat>>> webF = new ArrayList<>();
        Future<SourcesWeb.BraveReponse> braveF = null;
        if (brave) {
            braveF = POOL.submit(() -> SourcesWeb.braveAvecCorrection(envoyee, reglages.cleBrave, 10));
        } else {
            for (String q : reformulees) webF.add(POOL.submit(() -> SourcesWeb.bingWeb(q, 10)));
        }
        Future<List<SourcesWeb.Resultat>> wikiF = POOL.submit(() -> SourcesWeb.wiki(sansCadre, 2));
        // (remplacé plus bas si Brave corrige l'orthographe)
        Future<List<SourcesWeb.Resultat>> actuF = POOL.submit(() -> SourcesWeb.actu(sansCadre, 6));

        List<List<SourcesWeb.Resultat>> listes = new ArrayList<>();
        if (braveF != null) {
            SourcesWeb.BraveReponse br = attendre(braveF, 11_000);
            if (br != null) {
                listes.add(br.resultats);
                // Faute de frappe corrigée par Brave (« qui est charcle leclerc ») : on garde la
                // version corrigée pour Wikipédia et pour l'extraction (sinon « charcle »,
                // absent des pages, fait abstenir le moteur).
                String c = br.corrigee.trim();
                if (!c.isEmpty() && !c.equalsIgnoreCase(envoyee.trim())) {
                    if (col.questionOriginale == null) col.questionOriginale = question;
                    col.corrigee = c;
                    col.question = c;
                    List<String> refo = requetesTelephone(c);
                    String sc = refo.get(refo.size() - 1);
                    wikiF.cancel(true);
                    wikiF = POOL.submit(() -> SourcesWeb.wiki(sc, 2));
                }
            }
        }
        for (Future<List<SourcesWeb.Resultat>> f : webF) {
            List<SourcesWeb.Resultat> l = attendre(f, 11_000);
            if (l != null) listes.add(l);
        }
        List<SourcesWeb.Resultat> web = entrelacer(listes);
        if (web.isEmpty() && brave) {   // clé épuisée / réseau : Bing en secours
            List<List<SourcesWeb.Resultat>> secours = new ArrayList<>();
            for (String q : reformulees) {
                try {
                    secours.add(SourcesWeb.bingWeb(q, 10));
                } catch (Exception ignored) {
                }
            }
            web = entrelacer(secours);
        }
        List<SourcesWeb.Resultat> wiki = orVide(attendre(wikiF, 9_000));
        List<SourcesWeb.Resultat> news = orVide(attendre(actuF, 9_000));
        col.sourceWeb = web.isEmpty() ? "" : web.get(0).source;

        // Actu : articles qui parlent vraiment de la question ; en tête dès qu'il y en a 2
        List<SourcesWeb.Resultat> newsOk = new ArrayList<>();
        for (SourcesWeb.Resultat r : news) if (articlePertinent(envoyee, r)) newsOk.add(r);
        List<SourcesWeb.Resultat> ordre = new ArrayList<>();
        if (actu || newsOk.size() >= 2) {
            ordre.addAll(newsOk.subList(0, Math.min(4, newsOk.size())));
            ordre.addAll(web);
        } else {
            ordre.addAll(web);
            ordre.addAll(newsOk.subList(0, Math.min(2, newsOk.size())));
        }
        List<SourcesWeb.Resultat> hits = new ArrayList<>();
        Set<String> domaines = new HashSet<>();
        for (SourcesWeb.Resultat r : ordre) {
            String dom = TexteFr.domain(r.url);
            if (SKIP.matcher(r.url).find() || domaines.contains(dom) || dom.contains("wikipedia.org")) continue;
            domaines.add(dom);
            hits.add(r);
            if (hits.size() >= reglages.nPages) break;
        }
        col.msRecherche = System.currentTimeMillis() - t0;

        // Lecture des pages en parallèle, budget total msPages
        Map<String, Future<String[]>> lus = new LinkedHashMap<>();
        for (SourcesWeb.Resultat r : hits) {
            lus.put(r.url, POOL.submit((Callable<String[]>) () ->
                    LecteurPages.lire(r.url, 3_000, reglages.msPages, reglages.maxOctetsPage)));
        }
        long limite = System.currentTimeMillis() + reglages.msPages;
        Map<String, String[]> textes = new LinkedHashMap<>();
        for (Map.Entry<String, Future<String[]>> e : lus.entrySet()) {
            long reste = Math.max(1, limite - System.currentTimeMillis());
            String[] r = attendre(e.getValue(), reste);
            if (r != null) {
                textes.put(e.getKey(), r);
                try {
                    col.octetsPages += Long.parseLong(r[2]);
                } catch (Exception ignored) {
                }
            } else {
                e.getValue().cancel(true);
            }
        }
        if (!wiki.isEmpty()) {
            SourcesWeb.Resultat w = wiki.get(0);
            col.pages.add(new Collecte.Page(w.url, w.titre, w.extrait));
        }
        for (SourcesWeb.Resultat r : hits) {
            String[] lu = textes.get(r.url);
            String titre = lu != null && !lu[0].isEmpty() ? lu[0] : r.titre;
            String texte = lu != null ? lu[1] : "";
            if (texte.length() < 200) texte = r.extrait;   // page illisible : le résumé du moteur
            if (!r.date.isEmpty() && !texte.isEmpty()) texte = "[" + r.date + "] " + texte;
            if (!texte.isEmpty()) col.pages.add(new Collecte.Page(r.url, titre, texte));
        }
        col.msPages = System.currentTimeMillis() - t0 - col.msRecherche;
        return col;
    }

    private static <T> T attendre(Future<T> f, long ms) {
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> List<T> orVide(List<T> l) {
        return l != null ? l : new ArrayList<>();
    }

    private static List<SourcesWeb.Resultat> entrelacer(List<List<SourcesWeb.Resultat>> listes) {
        if (listes.size() == 1) return new ArrayList<>(listes.get(0));
        List<SourcesWeb.Resultat> out = new ArrayList<>();
        int max = 0;
        for (List<SourcesWeb.Resultat> l : listes) max = Math.max(max, l.size());
        for (int i = 0; i < max; i++) for (List<SourcesWeb.Resultat> l : listes) if (i < l.size()) out.add(l.get(i));
        return out;
    }

    private static final Pattern SKIP = Pattern.compile(
            "(youtube|facebook|instagram|tiktok|pinterest|twitter|x\\.com|amazon|linkedin|reddit\\.com/r/.*/comments)");

    // ---------- actu, reformulation (portage de recherche.py) ----------

    private static final Pattern RECENT = rxi(
            "\\b(hier|aujourd'hui|aujourd hui|ce matin|ce soir|cette semaine|ce week-?end|derni[eè]re?s?"
            + "|r[ée]sultats?|actus?|actualit[ée]s?|news|d[ée]clarations?|r[ée]actions?|annonc[ée]s?"
            + "|vient de|en ce moment|actuellement|cours de|bourse|score|classement|grand prix|gp)\\b");

    static boolean estActualite(String question) {
        return RECENT.matcher(question == null ? "" : question).find();
    }

    private static final Pattern PROPRE = rx(
            "\\b[A-ZÀ-Ý0-9][\\w'’-]*(?:\\s+(?:de |du |d'|la |le )?[A-ZÀ-Ý0-9][\\w'’-]*)*");
    private static final Pattern MOTS = Pattern.compile("[a-z0-9]+");

    static boolean articlePertinent(String question, SourcesWeb.Resultat r) {
        String texte = fold(r.titre + " " + r.extrait);
        Set<String> motsTexte = new HashSet<>();
        Matcher m = MOTS.matcher(texte);
        while (m.find()) motsTexte.add(m.group());
        List<String> propres = new ArrayList<>();
        Matcher mp = PROPRE.matcher(question);
        while (mp.find()) if (mp.group().trim().length() > 1) propres.add(fold(mp.group()));
        Set<String> propresMots = new HashSet<>();
        for (String p : propres) {
            Matcher mm = MOTS.matcher(p);
            while (mm.find()) propresMots.add(mm.group());
        }
        List<String> autres = new ArrayList<>();
        for (String w : TexteFr.tokens(question)) if (!propresMots.contains(w)) autres.add(w);
        if (!propres.isEmpty()) {
            for (String p : propres) {
                Matcher mm = MOTS.matcher(p);
                while (mm.find()) if (!motsTexte.contains(mm.group())) return false;
            }
            if (autres.isEmpty()) return true;
            for (String w : autres) if (motsTexte.contains(w)) return true;
            return false;
        }
        int k = 0;
        for (String w : new HashSet<>(autres)) if (motsTexte.contains(w)) k++;
        return k >= 3;
    }

    private static final Pattern CADRE = rxi(
            "^(?:(?:dis[- ]moi|tu sais|sais[- ]tu|est[- ]ce que|est[- ]ce qu')\\s*)?"
            + "(?:qu'est[- ]ce que|qu'est[- ]ce qu'|c'est quoi|qu'(?:a|ont|avait)\\s+|que\\s+(?:dit|pense)\\s+|"
            + "quel(?:le)?s?\\s+(?:est|sont|était|étaient)"
            + "|quel(?:le)?s?|qui\\s+(?:a|ont|est|était|sont)|qui|combien\\s+(?:de|d')|combien"
            + "|à quoi (?:sert|servent)|a quoi (?:sert|servent)|pourquoi|comment|quand|où|ou est)\\s*");
    private static final Pattern PREP = rxi(
            "\\s(?:de la|de l'|du|des|de|d'|dans une|dans un|dans le|dans la|dans)\\s*");
    private static final Pattern ARTICLE = rxi("^(?:le|la|les|l'|l’|un|une|des)\\s*");
    private static final Pattern MOT_RESTE = rx("[\\w'’-]+");

    /**
     * Pour Bing (secours) : sujet d'abord, puis la question sans son cadre. Le DERNIER élément
     * (question sans cadre) sert aussi pour Wikipédia et l'actu.
     */
    static List<String> requetesTelephone(String envoyee) {
        String q = envoyee.trim().replaceAll("\\s*[?!.]+$", "");
        Matcher m = CADRE.matcher(q);
        boolean cadreTrouve = m.lookingAt();
        String cadre = cadreTrouve ? fold(m.group()) : "";
        String sansCadre = (cadreTrouve ? q.substring(m.end()) : q).replaceAll("^[ ,']+|[ ,']+$", "");
        if (sansCadre.isEmpty()) sansCadre = q;
        String suffixe = cadre.contains("quoi sert") || cadre.contains("quoi servent") ? " sert à quoi" : "";
        List<String> propres = new ArrayList<>();
        Matcher mp = PROPRE.matcher(sansCadre);
        while (mp.find()) if (mp.group().trim().length() > 1) propres.add(mp.group().trim());
        String sujet, reste;
        if (!propres.isEmpty()) {
            reste = sansCadre;
            for (String p : propres) reste = reste.replace(p, " ");
            sujet = String.join(" ", propres);
        } else {
            String[] parts = PREP.split(sansCadre, -1);
            if (parts.length > 1 && !parts[parts.length - 1].trim().isEmpty()) {
                sujet = ARTICLE.matcher(parts[parts.length - 1].trim()).replaceFirst("");
                reste = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
            } else {
                String[] mots = sansCadre.split("\\s+");
                if (mots.length > 1) {
                    sujet = mots[mots.length - 1];
                    reste = String.join(" ", java.util.Arrays.copyOf(mots, mots.length - 1));
                } else {
                    sujet = sansCadre;
                    reste = "";
                }
            }
        }
        List<String> resteKw = new ArrayList<>();
        Matcher mr = MOT_RESTE.matcher(reste);
        while (mr.find()) {
            String w = mr.group();
            String nu = w.replaceAll("^['’]+|['’]+$", "");
            if (!TexteFr.STOP.contains(fold(nu)) && nu.length() > 1) resteKw.add(w);
        }
        List<String> out = new ArrayList<>();
        StringBuilder premiere = new StringBuilder(sujet);
        for (String w : resteKw) premiere.append(' ').append(w);
        out.add((premiere + suffixe).trim());
        String entiere = (sansCadre + suffixe).trim();
        if (!fold(entiere).equals(fold(out.get(0)))) out.add(entiere);
        return out;
    }

    // ---------- pour le gros modèle ----------

    /** Bloc lu par le LLM : réponse extraite + passages sourcés et datés (il ne doit rien inventer). */
    public static String contexteLlm(Dossier d) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Recherche web privée — croise les sources, cite le site, n'invente rien ; ")
                .append("si les sources ne répondent pas, dis-le]\n");
        sb.append("Question : ").append(d.questionOriginale.isEmpty() ? d.question : d.questionOriginale).append('\n');
        if (!d.reponse.isEmpty()) sb.append("Réponse extraite : ").append(d.reponse).append('\n');
        int i = 1;
        for (Dossier.Passage p : d.passages) {
            sb.append("\nSource ").append(i++).append(" — ").append(p.site).append(" : ").append(p.texte);
        }
        return sb.toString().trim();
    }
}
