package com.pegasuscorp.orbe.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

/**
 * Partie DÉTERMINISTE (portage de assembler() de recherche.py) : passages classés
 * (BM25 pour présélectionner, embeddings pour classer, 2 par site au plus), chiffres recoupés.
 */
public final class Assemblage {

    /** Vecteurs L2-normalisés ; implémenté par {@link MultilingualEmbedder}. */
    public interface Vecteurs {
        float[][] embed(List<String> textes) throws Exception;
    }

    private Assemblage() {}

    public static Dossier assembler(Collecte col, Vecteurs vec) throws Exception {
        if (col.direct != null) return col.direct;
        final int nPassages = 6;
        long t0 = System.currentTimeMillis();
        String question = col.envoyee;

        List<String[]> cands = new ArrayList<>();   // texte, url, domaine
        for (Collecte.Page p : col.pages) {
            for (String pa : TexteFr.passages(p.texte)) {
                cands.add(new String[]{pa, p.url, TexteFr.domain(p.url)});
            }
        }
        List<String[]> ranked = new ArrayList<>();
        if (!cands.isEmpty()) {
            List<List<String>> corpus = new ArrayList<>();
            for (String[] c : cands) corpus.add(TexteFr.tokens(c[0]));
            final double[] bm = new Bm25(corpus).scores(TexteFr.tokens(question));
            List<Integer> ordre = new ArrayList<>();
            for (int i = 0; i < cands.size(); i++) ordre.add(i);
            ordre.sort((a, b) -> Double.compare(bm[b], bm[a]));   // stable comme sorted()
            List<Integer> pre = new ArrayList<>();
            for (int i : ordre) {
                if (bm[i] > 0) pre.add(i);
                if (pre.size() >= 40) break;
            }
            if (!pre.isEmpty()) {
                List<String> textes = new ArrayList<>();
                textes.add(question);
                for (int i : pre) textes.add(cands.get(i)[0]);
                float[][] v = vec.embed(textes);
                double bmax = 0;
                for (int i : pre) bmax = Math.max(bmax, bm[i]);
                if (bmax == 0) bmax = 1.0;
                final Map<Integer, Double> mix = new HashMap<>();
                final Map<Integer, Double> cos = new HashMap<>();
                for (int k = 0; k < pre.size(); k++) {
                    int i = pre.get(k);
                    double c = dot(v[k + 1], v[0]);
                    cos.put(i, c);
                    mix.put(i, 0.75 * c + 0.25 * (bm[i] / bmax));
                }
                List<Integer> parMix = new ArrayList<>(pre);
                parMix.sort((a, b) -> Double.compare(mix.get(b), mix.get(a)));
                Map<String, Integer> parSite = new HashMap<>();
                for (int i : parMix) {
                    String[] c = cands.get(i);
                    if (parSite.getOrDefault(c[2], 0) >= 2 || cos.get(i) < 0.35) continue;   // hors sujet
                    parSite.merge(c[2], 1, Integer::sum);
                    ranked.add(c);
                    if (ranked.size() >= nPassages) break;
                }
            }
        }
        long tTri = System.currentTimeMillis() - t0;

        // Recoupement des chiffres : même valeur + unité citée par plusieurs sites
        Map<String, Set<String>> facts = new LinkedHashMap<>();
        Map<String, String[]> cles = new HashMap<>();
        for (String[] c : ranked) {
            Matcher m = TexteFr.NUM.matcher(c[0]);
            while (m.find()) {
                String u = m.group(2);
                String unit = u.length() > 2 ? rstripS(TexteFr.fold(u)) : TexteFr.fold(u);
                String val = m.group(1).replaceAll("[   ]", "").replace(",", ".");
                String key = val + "\u0000" + unit;
                facts.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(c[2]);
                cles.put(key, new String[]{val, unit});
            }
        }
        List<Dossier.Chiffre> chiffres = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : facts.entrySet()) {
            if (e.getValue().size() >= 2) {
                String[] vu = cles.get(e.getKey());
                chiffres.add(new Dossier.Chiffre(vu[0], vu[1], new ArrayList<>(new TreeSet<>(e.getValue()))));
            }
        }
        chiffres.sort((a, b) -> Integer.compare(b.sources.size(), a.sources.size()));

        Dossier d = new Dossier();
        d.question = col.question;
        d.questionOriginale = col.questionOriginale != null ? col.questionOriginale : col.question;
        d.proposition = col.proposition;
        d.requeteEnvoyee = question;
        d.retire.addAll(col.retire);
        d.sourceWeb = col.sourceWeb;
        TreeSet<String> sites = new TreeSet<>();
        for (String[] c : ranked) {
            d.passages.add(new Dossier.Passage(c[0], c[2], c[1]));
            sites.add(c[2]);
        }
        d.chiffres.addAll(chiffres.subList(0, Math.min(8, chiffres.size())));
        d.sources.addAll(sites);
        for (Collecte.Page p : col.pages) if (!p.texte.isEmpty()) d.pagesLues++;
        d.msRecherche = col.msRecherche;
        d.msPages = col.msPages;
        d.octetsPages = col.octetsPages;
        d.msTri = tTri;
        return d;
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    static String rstripS(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == 's') e--;
        return s.substring(0, e);
    }
}
