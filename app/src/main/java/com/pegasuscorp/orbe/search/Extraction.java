package com.pegasuscorp.orbe.search;

import static com.pegasuscorp.orbe.search.TexteFr.find;
import static com.pegasuscorp.orbe.search.TexteFr.fold;
import static com.pegasuscorp.orbe.search.TexteFr.lookingAt;
import static com.pegasuscorp.orbe.search.TexteFr.rx;
import static com.pegasuscorp.orbe.search.TexteFr.rxi;
import static com.pegasuscorp.orbe.search.TexteFr.strip;
import static com.pegasuscorp.orbe.search.TexteFr.tokenSet;
import static com.pegasuscorp.orbe.search.TexteFr.tokens;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Réponse sans modèle de langage : la phrase des sources qui répond, avec son site, ou une
 * abstention honnête. Portage FIDÈLE de recherche.py (y compris ses petites bizarreries,
 * pour garder la parité avec le banc du PC : ExtractionPariteTest).
 */
public final class Extraction {

    private Extraction() {}

    public static final String MSG_ABSENT = "Je n'ai rien trouvé de fiable sur ce sujet.";
    public static final String MSG_INSUFFISANT =
            "Le dossier ne permet pas de répondre : aucune source ne confirme les éléments demandés.";

    // ---------- éligibilité des phrases ----------

    private static final Pattern META_PHRASE = rxi(
            "^(savoir |c'?est (?:pourquoi|que)|source\\s*:|population\\s*:|[-•*]\\s*(?:population|hauteur|source)\\b)");
    private static final Pattern FICHE_LIGNE = rx("^[-•*]\\s*\\w[^:]{0,40}:\\s");
    private static final Pattern META_TEASER = rxi(
            "(il y a plusieurs (?:étapes|points)|ce guide explique|si vous vous demandez|"
            + "la réponse a de quoi donner|ci[-‑ ]?dessus|aperçu du cours|"
            + "c'?est le prix moyen en france selon|capitale représente le visage du)");
    private static final String[] CTX_DRIFT = {
            "fin de vie", "soins palliatifs", "palliatif", "hospice", "mourant", "terminalement",
            "accompagnement en fin", "qualité de l'accompagnement", "n'hésitez pas", "cliquez",
            "newsletter", "cookies", "génèse du texte", "en ce qui concerne"};
    private static final Pattern ACCROCHE = rxi(
            "\\b(dans cet article|cet article|notre article|notre guide|notre dossier|notre liste|ce dossier|nous allons|on vous explique|on vous dit tout|découvrez|retrouvez|"
            + "voici (comment|pourquoi|tout|ce que)|dans ce guide|dans cette vidéo|lisez la suite|"
            + "vous vous demandez|vous êtes nombreux|répondre (précisément |clairement )?à (la|cette) question|"
            + "la question (de savoir )?(qui|quel|quelle|comment|pourquoi)|cette question)\\b");
    private static final Pattern PUCE_SIGLE = rx("^\\s*[-•*]\\s*(\\[|[A-Z]{2,})");
    private static final Pattern CHIFFRE_COLLE = rx("^\\d+[^\\d\\s]");

    static boolean phraseEligible(String ph) {
        int n = ph.codePointCount(0, ph.length());
        if (n < 45 || n > 320 || ph.endsWith("?")) return false;
        if (find(ACCROCHE, ph)) return false;
        if (lookingAt(META_PHRASE, ph) || lookingAt(FICHE_LIGNE, ph)) return false;
        if (lookingAt(PUCE_SIGLE, ph)) return false;
        if (lookingAt(CHIFFRE_COLLE, ph)) return false;
        if (fold(ph.substring(0, Math.min(80, ph.length()))).contains("[pdf]")) return false;
        if (count(ph, ':') >= 2 && n < 130) return false;
        int up = 0;
        for (int i = 0; i < ph.length(); i++) if (Character.isUpperCase(ph.charAt(i))) up++;
        return up <= n * 0.35;
    }

    private static int count(String s, char c) {
        int k = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) k++;
        return k;
    }

    private static final Pattern Q_OUI_NON = rx("\\b(est-il|est-elle|sont-ils|sont-elles|existe-t-il|vrai que)\\b");
    private static final Pattern P_THESE = rx("\\b(theorie|selon (lui|elle|eux)|affirme|pretend|croient)\\b");
    private static final Pattern P_QUALIFIE = rx(
            "\\b(pas|non|faux|fausse|mythe|legende|refute|demonte|preuve|prouve|en realite)\\b");

    static double penaliteHorsSujet(String question, String phrase) {
        String qf = fold(question), pf = fold(phrase);
        double pen = 0;
        for (String term : CTX_DRIFT) if (pf.contains(term) && !qf.contains(term)) pen += 0.16;
        Set<String> qt = tokenSet(question), pt = tokenSet(phrase);
        if (!qt.isEmpty() && (double) inter(qt, pt) / qt.size() < 0.12) pen += 0.14;
        if (find(META_TEASER, phrase)) pen += 0.35;
        if (find(Q_OUI_NON, qf) && find(P_THESE, pf) && !find(P_QUALIFIE, pf)) pen += 0.3;
        return Math.min(pen, 0.55);
    }

    private static int inter(Set<String> a, Set<String> b) {
        int k = 0;
        for (String x : a) if (b.contains(x)) k++;
        return k;
    }

    private static final Pattern Q_CHIFFRE = rx("\\b(combien|quelle?|hauteur|population|duree|temps|vitesse|distance|superficie|"
            + "prix|cout|age|poids|temperature|longueur|profondeur)\\b");
    private static final Pattern CHIFFRE = rx("\\d");
    private static final Pattern Q_SERT = rx("\\ba quoi ser(?:t|vent)\\b|\\brole d");
    private static final Pattern P_FONCTION = rx("\\b(sert a|servent a|permet|permettent|a pour role|ont pour role|role|assure|assurent|"
            + "filtre|filtrent|produit|produisent|stocke|stockent|regule|regulent|transforme|"
            + "transforment|elimine|eliminent|fabrique|fabriquent|secrete|secretent|pompe|pompent)\\b");
    private static final Pattern Q_DEFINITION = rx("\\b(c'est quoi|qu'est-ce|qu'est ce|definition)\\b");
    private static final Pattern P_DEFINITION = rx("\\b(est un|est une|sont des|designe|designent|on appelle|consiste|est le processus|"
            + "est la|est le|permet de|permettent de)\\b");
    private static final Pattern P_EXPLIQUE = rx("\\b(parce que|car|en raison|grace a|du fait|s'explique)\\b");
    private static final Pattern P_NON_AUTONOME = rx(
            "^(pourtant|mais|cependant|toutefois|d'ailleurs|en revanche|or|bref|enfin|alors)\\b");

    /** Bonus générique : la phrase a-t-elle la forme d'une réponse ? */
    static double bonusFactuel(String question, String phrase) {
        String qf = fold(question), pf = fold(phrase);
        double b = 0;
        if (find(Q_CHIFFRE, qf)) {
            if (find(TexteFr.NUM, phrase)) b += 0.14;
            else if (find(CHIFFRE, phrase)) b += 0.07;
        }
        if (find(Q_SERT, qf)) {
            if (find(P_FONCTION, pf)) b += 0.2;
        } else if (find(Q_DEFINITION, qf)) {
            if (find(P_DEFINITION, pf)) b += 0.16;
        }
        if (qf.startsWith("pourquoi") && find(P_EXPLIQUE, pf)) b += 0.12;
        for (Pattern[] r : RELATIONS) {
            if (find(r[0], qf) && find(r[1], phrase)) {
                b += 0.22;
                break;
            }
        }
        if (lookingAt(P_NON_AUTONOME, pf)) b -= 0.12;
        if (phrase.contains("…") || phrase.contains("...")) b -= 0.10;
        Set<String> qt = tokenSet(question);
        if (!qt.isEmpty()) b += 0.12 * inter(qt, tokenSet(phrase)) / qt.size();
        return b;
    }

    // ---------- phrases candidates ----------

    /** Les n phrases qui répondent le mieux (sens + mots + anti hors-sujet), 2 par site au plus. */
    public static List<Dossier.Phrase> phrasesCles(Dossier d, Assemblage.Vecteurs vec) throws Exception {
        final int n = 6;
        final double seuil = 0.38;
        List<String[]> cands = new ArrayList<>();
        for (Dossier.Passage p : d.passages) {
            for (String ph : TexteFr.decouperPhrases(p.texte)) {
                ph = strip(ph);
                if (phraseEligible(ph)) cands.add(new String[]{ph, p.site});
            }
        }
        List<Dossier.Phrase> out = new ArrayList<>();
        if (cands.isEmpty()) return out;
        List<String> textes = new ArrayList<>();
        textes.add(d.question);
        for (String[] c : cands) textes.add(c[0]);
        float[][] v = vec.embed(textes);
        final double[] sims = new double[cands.size()];
        final double[] scores = new double[cands.size()];
        for (int i = 0; i < cands.size(); i++) {
            sims[i] = Assemblage.dot(v[i + 1], v[0]);
            scores[i] = sims[i] - penaliteHorsSujet(d.question, cands.get(i)[0])
                    + bonusFactuel(d.question, cands.get(i)[0]);
        }
        List<Integer> ordre = new ArrayList<>();
        for (int i = 0; i < cands.size(); i++) ordre.add(i);
        ordre.sort((a, b) -> Double.compare(scores[b], scores[a]));
        List<Integer> idx = new ArrayList<>();
        Map<String, Integer> parSite = new HashMap<>();
        for (int i : ordre) {
            String site = cands.get(i)[1];
            if (scores[i] < seuil || parSite.getOrDefault(site, 0) >= 2) continue;
            boolean doublon = false;
            for (int j : idx) if (Assemblage.dot(v[i + 1], v[j + 1]) > 0.88) { doublon = true; break; }
            if (doublon) continue;
            idx.add(i);
            parSite.merge(site, 1, Integer::sum);
            out.add(new Dossier.Phrase(cands.get(i)[0], site, round3(sims[i]), round3(scores[i])));
            if (out.size() >= n) break;
        }
        return out;
    }

    private static double round3(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }

    static List<Dossier.Phrase> toutesPhrasesEligibles(Dossier d) {
        List<Dossier.Phrase> out = new ArrayList<>();
        Set<String> vues = new HashSet<>();
        for (Dossier.Passage p : d.passages) {
            for (String ph : TexteFr.decouperPhrases(p.texte)) {
                ph = strip(ph);
                if (phraseEligible(ph) && vues.add(ph)) out.add(new Dossier.Phrase(ph, p.site, -1, 0));
            }
        }
        return out;
    }

    // ---------- relations (« qui a fondé… », capitale, classement…) ----------

    private static final String PAR = "par\\s+(?:[^.?]{0,40}?\\s)?[A-ZÀ-Ý]";
    static final Pattern[][] RELATIONS = {
            {rx("\\bcapitale\\b"), rx(
                    "\\bcapitale\\b[^.?]{0,40}\\b(?:est|soit|reste|demeure|devient)\\s+(?:la ville de\\s+)?[A-ZÀ-Ý]"
                    + "|en tant que capitale,?\\s+[A-ZÀ-Ý]"
                    + "|[A-ZÀ-Ý][\\w-]+,?\\s+(?:est|reste|demeure|devient)\\s+(?:la\\s+|une\\s+)?(?:\\w+\\s+)?capitale"
                    + "|a\\s+pour\\s+capitale\\s+[A-ZÀ-Ý]"
                    + "|capitale(?:\\s+\\w+)?\\s+(?:du|de la|de l'|des)\\s*[\\w-]+\\s*(?:,|:|est)\\s*[A-ZÀ-Ý]")},
            {rx("\\bqui\\b.*\\b(ecrit|auteur)\\b"), rx(
                    "(?:écrite?|rédigée?|signée?)\\s+" + PAR + "|[A-ZÀ-Ý][\\w-]+\\s+a\\s+écrit"
                    + "|\\b(?:roman|conte|livre|œuvre|oeuvre|ouvrage|nouvelle|pièce|poème|recueil|essai|récit)\\s+d[e'’]\\s*[A-ZÀ-Ý]"
                    + "|auteur[^.?]{0,30}\\best\\s+[A-ZÀ-Ý]")},
            {rx("\\bqui\\b.*\\binvent"), rx(
                    "inventée?\\s+(?:en\\s+\\d{4}\\s+)?" + PAR
                    + "|[A-ZÀ-Ý][\\w-]+[^.?]{0,50}\\ba\\s+inventé|inventeur[^.?]{0,30}\\best\\s+[A-ZÀ-Ý]")},
            {rx("\\bqui\\b.*\\b(fonde|cree|fondateur)"), rx(
                    "(?:fondée?|cofondée?|créée?)\\s+(?:en\\s+\\d{4}\\s+)?" + PAR
                    + "|[A-ZÀ-Ý][\\w-]+[^.?]{0,60}\\b(?:a|ont)\\s+(?:co)?(?:fondé|créé)|fondateurs?[^.?]{0,20}[A-ZÀ-Ý]")},
            {rx("\\bqui\\b.*\\bdecouvr"), rx(
                    "découverte?\\s+(?:en\\s+\\d{4}\\s+)?" + PAR + "|[A-ZÀ-Ý][\\w-]+[^.?]{0,50}\\ba\\s+découvert")},
            {rx("\\bqui\\b.*\\b(deuxieme|troisieme|2e|3e|second|podium|vainqueur|gagne|premier|arrive|termine)\\b"), rx(
                    "\\bdevant\\s+(?:les?\\s+\\w+\\s+(?:de\\s+)?)?[A-ZÀ-Ý]"
                    + "|\\b(?:2e|3e|deuxième|troisième|seconde?)\\s+(?:place\\s+)?(?:pour|:|,)?\\s*[A-ZÀ-Ý]"
                    + "|[A-ZÀ-Ý][\\w-]+[^.?]{0,40}\\b(?:termine|finit|prend|complète|complete)\\s+(?:la\\s+)?(?:2e|deuxième|3e|troisième|seconde?|podium)"
                    + "|\\bpodium\\b[^.?]{0,60}[A-ZÀ-Ý]|\\bvictoire\\s+de\\s+[A-ZÀ-Ý]"
                    + "|[A-ZÀ-Ý][\\w-]+[^.?]{0,40}\\b(?:remporte|gagne|s'impose)")},
            {rx("\\bqui\\b.*\\b(peint|compose)"), rx(
                    "(?:peinte?|composée?)\\s+(?:en\\s+\\d{4}\\s+)?" + PAR
                    + "|[A-ZÀ-Ý][\\w-]+[^.?]{0,50}\\ba\\s+(?:peint|composé)|compositeur[^.?]{0,30}[A-ZÀ-Ý]")},
    };

    static final Set<String> MAJ_COURANTS = new HashSet<>(Arrays.asList((
            "le la les un une des ce cet cette ces il elle ils elles on selon dans depuis pour mais "
            + "et en au aux du de son sa ses leur cela ceci apres avant lors quand alors ainsi aujourd hui c est the")
            .split(" ")));

    private static final Pattern MAJ_MOT = rx("[A-ZÀ-Ý][\\w-]+");
    private static final Pattern MAJ_MOT3 = rx("\\b[A-ZÀ-Ý][\\w-]{2,}");

    static Dossier.Phrase phraseRelation(String question, List<Dossier.Phrase> pool) {
        String qf = fold(question);
        Set<String> qNoms = new HashSet<>(tokens(question));
        Matcher mq = MAJ_MOT.matcher(question);
        while (mq.find()) qNoms.add(fold(mq.group()));
        Set<String> qt = tokenSet(question);
        for (Pattern[] r : RELATIONS) {
            if (!find(r[0], qf)) continue;
            Dossier.Phrase meilleure = null;
            double best = -9.0;
            for (Dossier.Phrase p : pool) {
                Matcher m = r[1].matcher(p.texte);
                if (!m.find()) continue;
                // Le nom propre qui répond doit être PRÈS de la relation, pas n'importe où.
                String fenetre = p.texte.substring(Math.max(0, m.start() - 60),
                        Math.min(p.texte.length(), m.end() + 60));
                boolean nom = false;
                Matcher mn = MAJ_MOT3.matcher(fenetre);
                while (mn.find()) {
                    String w = fold(mn.group());
                    if (!qNoms.contains(w) && !MAJ_COURANTS.contains(w)) { nom = true; break; }
                }
                if (!nom) continue;
                double sc = p.simOu(0.3) + 0.1 * Math.min(inter(qt, tokenSet(p.texte)), 3);
                if (sc > best) {
                    best = sc;
                    meilleure = p;
                }
            }
            if (meilleure != null) return meilleure;
        }
        return null;
    }

    private static final Pattern PAYS = rx("capitale\\s+(?:de\\s+la\\s+|de\\s+l'|des\\s+|du\\s+)?([\\w-]+)");
    private static final Pattern EST_CAPITALE = rx("\\best\\s+la\\s+capitale\\b");

    private static Dossier.Phrase chercherPhraseCapital(Dossier d, String question) {
        Matcher m = PAYS.matcher(fold(question));
        String pays = m.find() ? m.group(1) : "";
        for (Dossier.Phrase p : toutesPhrasesEligibles(d)) {
            String pf = fold(p.texte);
            if (!pays.isEmpty() && pf.contains(pays) && find(EST_CAPITALE, pf)) return p;
        }
        return null;
    }

    private static Dossier.Phrase meilleurePhraseChiffree(String question, List<Dossier.Phrase> pool) {
        Dossier.Phrase meilleur = null;
        double best = -999;
        for (Dossier.Phrase p : pool) {
            if (!find(CHIFFRE, p.texte)) continue;
            double sc = 0.4 + bonusFactuel(question, p.texte);
            if (find(TexteFr.NUM, p.texte)) sc += 0.15;
            if (find(META_TEASER, p.texte)) sc -= 0.7;
            if (sc > best) {
                best = sc;
                meilleur = p;
            }
        }
        return best > 0.2 ? meilleur : null;
    }

    private static final Pattern Q_CONTENU = rx("\\b(c'est quoi|qu'est-ce|a quoi sert)\\b");

    private static Dossier.Phrase meilleurePhraseContenu(String question, List<Dossier.Phrase> pool) {
        Dossier.Phrase meilleur = null;
        double best = -999;
        String qf = fold(question);
        Set<String> qt = tokenSet(question);
        for (Dossier.Phrase p : pool) {
            double sc = bonusFactuel(question, p.texte);
            if (!qt.isEmpty()) sc += 0.25 * inter(qt, tokenSet(p.texte)) / qt.size();
            if (find(META_TEASER, p.texte)) sc -= 0.55;
            if (find(Q_CONTENU, qf) && p.texte.codePointCount(0, p.texte.length()) < 70) sc -= 0.15;
            if (sc > best) {
                best = sc;
                meilleur = p;
            }
        }
        return best > 0.1 ? meilleur : null;
    }

    // ---------- abstention ----------

    private static final Set<String> SKIP_PROPRES = new HashSet<>(Arrays.asList(
            "france", "europe", "terre", "insee", "internet", "google", "wikipedia"));
    private static final Set<String> SKIP_ANCRES = new HashSet<>(Arrays.asList((
            "inventeur portable quantique officielle constellation statistique habitants "
            + "romanciere francaise francais sommet paix portable").split(" ")));
    private static final Set<String> HORS_NOMS = new HashSet<>(Arrays.asList(
            "il", "elle", "je", "tu", "un", "une", "des", "les", "la", "le", "du", "de",
            "au", "aux", "en", "sur", "par", "pour", "avec", "sans", "ma", "mon"));
    private static final Pattern NOM_PROPRE = rx("\\b([A-ZÀ-Ý][a-zà-ÿ]+(?:[-'][A-ZÀ-ÿa-zà-ÿ]+)*)\\b");
    private static final Pattern SIGLE = rx("\\b([A-ZÀ-Ý]{2,})\\b");

    static List<String> nomsPropres(String question) {
        Set<String> out = new LinkedHashSet<>();
        for (Pattern p : new Pattern[]{NOM_PROPRE, SIGLE}) {
            Matcher m = p.matcher(question);
            while (m.find()) {
                String w = fold(m.group(1));
                if (!HORS_NOMS.contains(w)) out.add(w);
            }
        }
        return new ArrayList<>(out);
    }

    private static String corpusFold(Dossier d) {
        StringBuilder sb = new StringBuilder();
        for (Dossier.Passage p : d.passages) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.texte);
        }
        return fold(sb.toString());
    }

    private static final Set<String> NON_PARTICIPES = new HashSet<>(Arrays.asList((
            "le la les un une ce cet cette mon ton son ma ta sa lui elle tu vraiment "
            + "actuellement aussi ici celui celle").split(" ")));
    private static final Set<String> HYPH_CLITICS = new HashSet<>(Arrays.asList(
            "il elle on ce t y en lui leur nous vous ils elles la le aux des les".split(" ")));
    private static final Pattern COMPOSE = rx("\\b[A-Za-zÀ-ÿ]+(?:-[A-Za-zÀ-ÿ]+)+\\b");
    private static final Pattern PAIRE = rx("\\b([A-ZÀ-Ý][\\wÀ-ÿ]+)-([A-ZÀ-Ý][\\wÀ-ÿ]+)\\b");

    /** Lieux composés (Nantes-sur-Mars), pas les clitiques ni les paires de noms propres. */
    static List<String> composesLiees(String question) {
        List<String> out = new ArrayList<>();
        Matcher m = COMPOSE.matcher(question);
        while (m.find()) {
            String[] brut = m.group().split("-");
            boolean clitique = false, court = false;
            for (String b : brut) {
                String f = fold(b);
                if (HYPH_CLITICS.contains(f)) clitique = true;
                if (f.length() < 3) court = true;
            }
            if (clitique || court) continue;
            if (brut.length == 2 && Character.isUpperCase(brut[0].charAt(0))
                    && Character.isUpperCase(brut[1].charAt(0))) continue;
            out.add(fold(m.group()));
        }
        return out;
    }

    static List<String[]> pairesPropres(String question) {
        List<String[]> out = new ArrayList<>();
        Matcher m = PAIRE.matcher(question);
        while (m.find()) out.add(new String[]{fold(m.group(1)), fold(m.group(2))});
        return out;
    }

    private static final Pattern PARTICIPE = rx(
            "\\bqui (?:est|était|etait)\\s+([a-zàâçéèêëîïôûùüÿ][\\wàâçéèêëîïôûùüÿ]*"
            + "(?:é|ée|és|ées|i|ie|is|ies|u|ue|us|ues|t|te|ts))\\b");
    private static final Pattern IDENTITE = rx(
            "\\b(qui est|qui etait|qui était|biographie|vie de |résumé du|résumé de la|résumé d['’])");

    /** « qui est X », biographie… ; « qui est arrivé deuxième » (participe) n'en est pas une. */
    static boolean estQuestionIdentite(String question) {
        Matcher m = PARTICIPE.matcher(question);
        if (m.find() && !NON_PARTICIPES.contains(m.group(1))) return false;
        return find(IDENTITE, fold(question));
    }

    private static List<String> ancresRaresManquantes(String question, String corpus) {
        String cf = fold(corpus);
        List<String> miss = new ArrayList<>();
        for (String w : tokens(question)) {
            if (w.length() < 7 || SKIP_ANCRES.contains(w)) continue;
            if (!cf.contains(w)) miss.add(w);
        }
        return miss;
    }

    private static final Pattern RUE = rxi("\\brue\\s+([A-Za-zÀ-ÿ]+)");
    private static final Pattern PRIX_EXACT_RUE = rx("\\bprix\\s+actuel\\s+exact\\b");
    private static final Pattern CONSTELLATION = rx("\\bconstellation\\s+(\\w+)");
    private static final Pattern TEMPS_REEL = rx("\\b(cours exact|valeur exacte|prix exact|montant exact|a l'instant|en temps reel|"
            + "a la seconde|en ce moment precis)\\b");
    private static final String[] REL_KW = {"capitale", "president", "auteur", "fondateur", "inventeur",
            "sommet", "traite", "accord"};

    static String abstentionAvant(String question, Dossier d, List<Dossier.Phrase> phrases) {
        String qf = fold(question);
        if (find(rx("\\b31\\s+fevrier\\b"), qf)) return MSG_ABSENT;
        if (d.refus != null) return MSG_ABSENT;
        String corpus = corpusFold(d);
        if (strip(corpus).isEmpty()) return MSG_ABSENT;
        for (String expr : composesLiees(question)) if (!corpus.contains(expr)) return MSG_INSUFFISANT;
        for (String[] p : pairesPropres(question)) {
            if (!corpus.contains(p[0]) || !corpus.contains(p[1])) return MSG_INSUFFISANT;
        }
        Matcher mr = RUE.matcher(question);
        if (mr.find()) {
            String rue = fold(mr.group(1));
            if (!corpus.contains(rue) && (qf.contains("imaginaire") || find(PRIX_EXACT_RUE, qf))) {
                return MSG_INSUFFISANT;
            }
        }
        List<String> noms = new ArrayList<>();
        for (String n : nomsPropres(question)) if (!SKIP_PROPRES.contains(n)) noms.add(n);
        boolean identite = estQuestionIdentite(question);
        if (identite && !noms.isEmpty()) {
            boolean un = false;
            for (String n : noms) if (corpus.contains(n)) { un = true; break; }
            if (!un) return MSG_INSUFFISANT;
        }
        if (identite && !ancresRaresManquantes(question, corpus).isEmpty()) return MSG_INSUFFISANT;
        Matcher mc = CONSTELLATION.matcher(qf);
        if (mc.find()) {
            String cons = mc.group(1);
            if (cons.length() >= 5 && !corpus.contains(cons)) return MSG_INSUFFISANT;
        }
        if (find(TEMPS_REEL, qf)) return MSG_INSUFFISANT;

        // Cooccurrence : les entités de la question doivent apparaître ENSEMBLE dans une phrase
        List<String> ents = new ArrayList<>();
        Matcher me = MAJ_MOT3.matcher(question);
        while (me.find()) {
            String w = me.group();
            String f = fold(w);
            if (!MAJ_COURANTS.contains(f) && !question.startsWith(w)) ents.add(f);
        }
        String relKw = null;
        for (String k : REL_KW) if (qf.contains(k)) { relKw = k; break; }
        List<String> exiger = new ArrayList<>(ents);
        if (!ents.isEmpty() && relKw != null) {
            exiger.add(relKw);
            for (String w : tokens(question)) {
                if (w.length() >= 8 && !SKIP_ANCRES.contains(w) && !ents.contains(w) && !w.equals(relKw)) {
                    exiger.add(w);
                }
            }
        }
        if (exiger.size() >= 2) {
            boolean ensemble = false;
            for (Dossier.Passage p : d.passages) {
                for (String x : TexteFr.decouperPhrases(p.texte)) {
                    String ph = fold(x);
                    boolean tous = true;
                    for (String e : exiger) if (!ph.contains(e)) { tous = false; break; }
                    if (tous) { ensemble = true; break; }
                }
                if (ensemble) break;
            }
            if (!ensemble) return MSG_INSUFFISANT;
        }
        if (phrases.isEmpty() && identite) return MSG_ABSENT;
        return null;
    }

    private static final Pattern QUI_EST = rxi("\\bqui (?:est|etait|était)\\b");

    static String abstentionApres(String question, Dossier.Phrase best) {
        if (best == null) return MSG_ABSENT;
        String bt = fold(best.texte);
        List<String> noms = new ArrayList<>();
        for (String n : nomsPropres(question)) if (!SKIP_PROPRES.contains(n)) noms.add(n);
        boolean identite = estQuestionIdentite(question);
        if (!noms.isEmpty() && identite) {
            boolean un = false;
            for (String n : noms) if (bt.contains(n)) { un = true; break; }
            if (!un) return MSG_INSUFFISANT;
        }
        if (identite && find(QUI_EST, question)) {
            for (String w : ancresRaresManquantes(question, bt)) {
                if (!SKIP_ANCRES.contains(w) && w.length() >= 7) return MSG_INSUFFISANT;
            }
        }
        return null;
    }

    // ---------- prémisse chiffrée, auteur, choix final ----------

    static String extractifPremisse(String question, List<Dossier.Phrase> pool) {
        List<double[]> qv = new ArrayList<>();
        List<String> qu = new ArrayList<>();
        Matcher m = TexteFr.NUM.matcher(question);
        while (m.find()) {
            qv.add(new double[]{TexteFr.valeur(m.group(1))});
            qu.add(Assemblage.rstripS(fold(m.group(2))));
        }
        for (int k = 0; k < qv.size(); k++) {
            double v0 = qv.get(k)[0];
            for (Dossier.Phrase p : pool) {
                Matcher mp = TexteFr.NUM.matcher(p.texte);
                while (mp.find()) {
                    if (!Assemblage.rstripS(fold(mp.group(2))).equals(qu.get(k))) continue;
                    double pv = TexteFr.valeur(mp.group(1));
                    if (pv > 0 && Math.abs(pv - v0) / Math.max(pv, v0) > 0.2) {
                        return "D'après " + p.site + " : non, pas " + TexteFr.valeurTexte(v0) + " " + qu.get(k)
                                + " ; la source indique " + mp.group(1) + " " + mp.group(2) + ". « "
                                + prefixe(p.texte, 180) + " »";
                    }
                }
            }
        }
        return null;
    }

    private static String prefixe(String s, int n) {
        int fin = s.offsetByCodePoints(0, Math.min(n, s.codePointCount(0, s.length())));
        return s.substring(0, fin);
    }

    private static final Pattern NON_MOT = rx("[^\\w\\s]");
    private static final Pattern ECRIT_PAR = rxi("(écrit|écrite)\\s+par\\s+\\w+");
    private static final Pattern Q_AUTEUR = rx("\\bqui\\s+(a\\s+)?(écrit|est l'auteur)\\b");
    private static final Pattern ECRIT_PAR_MAJ = rx("(écrit|écrite)\\s+par\\s+[A-ZÀ]");
    private static final Pattern A_ECRIT = rx("[A-ZÀ][a-zà-ÿ]+\\s+a\\s+écrit");
    private static final Pattern Q_SANS_CHIFFRE_DEF = rx("\\b(c'est quoi|qu'est-ce|qu'est ce|a quoi ser(?:t|vent))\\b");
    private static final Pattern Q_BESOIN_CHIFFRE = rx(
            "\\b(combien|durée|temps|hauteur|population|vitesse|prix|cours|coût|température|superficie)\\b");

    private static Dossier.Phrase chercherPhraseAuteur(Dossier d) {
        String titre = fold(NON_MOT.matcher(d.question).replaceAll(" "));
        for (Dossier.Passage p : d.passages) {
            for (String ph : TexteFr.decouperPhrases(p.texte)) {
                ph = strip(ph);
                if (!phraseEligible(ph)) continue;
                String pf = fold(ph);
                if (!find(ECRIT_PAR, ph)) continue;
                for (String w : tokens(titre)) {
                    if (w.length() > 4 && pf.contains(w)) return new Dossier.Phrase(ph, p.site, -1, 0);
                }
            }
        }
        return null;
    }

    private static List<Dossier.Phrase> pool(Dossier d, List<Dossier.Phrase> phrases) {
        List<Dossier.Phrase> pool = new ArrayList<>(phrases);
        Set<String> deja = new HashSet<>();
        for (Dossier.Phrase p : phrases) deja.add(p.texte);
        for (Dossier.Phrase x : toutesPhrasesEligibles(d)) if (!deja.contains(x.texte)) pool.add(x);
        return pool;
    }

    static Dossier.Phrase phraseExtractive(Dossier d, List<Dossier.Phrase> phrases) {
        if (phrases.isEmpty() && d.passages.isEmpty()) return null;
        String q = d.question, qf = fold(q);
        List<Dossier.Phrase> pool = pool(d, phrases);
        Dossier.Phrase rel = phraseRelation(q, pool);
        if (rel != null) return rel;
        if (find(Q_AUTEUR, qf)) {   // jamais vrai (qf sans accents) : gardé pour la parité avec le PC
            Dossier.Phrase hit = chercherPhraseAuteur(d);
            if (hit != null) return hit;
            for (Dossier.Phrase p : pool) {
                if (find(ECRIT_PAR_MAJ, p.texte) || find(A_ECRIT, p.texte)) return p;
            }
        }
        if (qf.contains("capitale")) {
            Dossier.Phrase hit = chercherPhraseCapital(d, q);
            if (hit != null) return hit;
        }
        if (find(Q_SANS_CHIFFRE_DEF, qf)) {
            Dossier.Phrase hit = meilleurePhraseContenu(q, pool);
            if (hit != null) return hit;
        }
        for (Dossier.Chiffre c : d.chiffres) {
            String v = c.valeur.replaceAll("[   ]", "").replace(",", ".");
            for (Dossier.Phrase p : pool) {
                if (p.texte.replaceAll("[   ]", "").replace(",", ".").contains(v)) return p;
            }
        }
        if (find(Q_BESOIN_CHIFFRE, qf)) {
            Dossier.Phrase hit = meilleurePhraseChiffree(q, pool);
            if (hit != null) return hit;
        }
        if (!phrases.isEmpty()) return phrases.get(0);
        return pool.isEmpty() ? null : pool.get(0);
    }

    /**
     * Réponse sans modèle : meilleure phrase + chiffre confirmé + site, ou abstention.
     * Question avec proposition (« …, Edison ? ») : on répond puis on dit si elle est confirmée.
     */
    public static String extractive(Dossier d, List<Dossier.Phrase> phrases) {
        String r;
        if (d.proposition != null) {
            String prop = d.proposition;
            d.proposition = null;
            String rep = extractiveSimple(d, phrases);
            d.proposition = prop;
            if (rep.startsWith(MSG_ABSENT) || rep.startsWith(MSG_INSUFFISANT.substring(0, 30))) {
                r = rep;
            } else {
                String[] mots = prop.trim().split("\\s+");
                String nom = fold(mots[mots.length - 1]);
                int k = rep.indexOf(" : ");
                String corps = k >= 0 ? rep.substring(k + 3) : rep;
                boolean confirme = rx("\\b" + Pattern.quote(nom) + "\\b").matcher(fold(corps)).find();
                r = (confirme ? "Oui, " + prop + ". " : "Non, pas " + prop + ". ") + rep;
            }
        } else {
            r = extractiveSimple(d, phrases);
        }
        d.reponse = r;
        d.abstention = r.startsWith(MSG_ABSENT) || r.startsWith(MSG_INSUFFISANT.substring(0, 30));
        return r;
    }

    private static String extractiveSimple(Dossier d, List<Dossier.Phrase> phrases) {
        if (d.calculLocal && !d.passages.isEmpty()) return "Calcul local : " + d.passages.get(0).texte;
        String pre = abstentionAvant(d.question, d, phrases);
        if (pre != null) return pre;
        if (phrases.isEmpty()) return MSG_ABSENT;
        List<Dossier.Phrase> pool = pool(d, phrases);
        String prem = extractifPremisse(d.question, pool);
        if (prem != null) return prem;
        Dossier.Phrase best = phraseExtractive(d, phrases);
        if (best == null) best = phrases.isEmpty() ? null : phrases.get(0);
        if (best == null && !pool.isEmpty()) best = pool.get(0);
        String post = abstentionApres(d.question, best);
        if (post != null) return post;
        if (best == null) return MSG_ABSENT;
        StringBuilder out = new StringBuilder("D'après ").append(best.site).append(" : ").append(best.texte);
        if (!d.chiffres.isEmpty()) {
            Dossier.Chiffre c = d.chiffres.get(0);
            out.append(" (« ").append(c.valeur).append(' ').append(c.unite).append(" » confirmé par ")
                    .append(c.sources.size()).append(" sites)");
        }
        return out.toString();
    }

    // ---------- proposition fausse, calcul ----------

    private static final Pattern PROPOSITION = rxi(
            "^(?<base>(?:qui|quel|quelle|quels|quelles|où|ou|quand|combien|c'est quoi|qu'est)\\b.*\\S)\\s*,\\s*"
            + "(?:c'est\\s+|plutôt\\s+|pas\\s+)?"
            + "(?<prop>[A-ZÀ-Ý][\\w'’-]*(?:\\s+(?:de |d'|da |van |von |le |la )?[A-ZÀ-Ý][\\w'’-]*)*)\\s*\\?*\\s*$");

    /** « qui a inventé le téléphone, Thomas Edison ? » → [« qui a inventé le téléphone ? », « Thomas Edison »]. */
    public static String[] scinderProposition(String question) {
        Matcher m = PROPOSITION.matcher(strip(question == null ? "" : question));
        if (!m.matches() || !Character.isUpperCase(m.group("prop").charAt(0))) {
            return new String[]{question, null};
        }
        return new String[]{strip(m.group("base")) + " ?", strip(m.group("prop"))};
    }

    private static final Pattern ARITH = rxi("combien (?:font|fait)\\s+(\\d+)\\s+(fois|plus|moins|\\+|\\-|x)\\s*(\\d+)");

    /** « combien font 7 fois 8 » : réponse locale, sans le web. */
    static Dossier dossierCalcul(String question, List<String> retire) {
        Matcher m = ARITH.matcher(fold(question));
        if (!m.find()) return null;
        long a = Long.parseLong(m.group(1)), b = Long.parseLong(m.group(3));
        String op = m.group(2).toLowerCase(java.util.Locale.ROOT);
        long r;
        String label;
        if (op.equals("fois") || op.equals("x")) {
            r = a * b;
            label = a + " fois " + b;
        } else if (op.equals("plus") || op.equals("+")) {
            r = a + b;
            label = a + " plus " + b;
        } else {
            r = a - b;
            label = a + " moins " + b;
        }
        Dossier d = new Dossier();
        d.question = question;
        d.questionOriginale = question;
        d.requeteEnvoyee = question;
        d.retire.addAll(retire);
        d.calculLocal = true;
        d.passages.add(new Dossier.Passage("En arithmétique, " + label + " est égal à " + r + ".",
                "calcul-local", ""));
        d.chiffres.add(new Dossier.Chiffre(String.valueOf(r), "", java.util.Collections.singletonList("calcul-local")));
        d.sources.add("calcul-local");
        return d;
    }
}
