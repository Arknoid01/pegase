package com.pegasuscorp.orbe.search;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outils de texte du moteur de recherche privé — portage fidèle de recherche.py
 * (/mnt/jeux/dev/recherche). Toute regex est compilée avec {@link #U} : en Python, \w et \b
 * reconnaissent les lettres accentuées ; en Java il faut UNICODE_CHARACTER_CLASS.
 */
public final class TexteFr {

    private TexteFr() {}

    /**
     * Sur Android, le moteur de regex (ICU) traite DÉJÀ \w et \b en Unicode et REFUSE le drapeau
     * UNICODE_CHARACTER_CLASS (IllegalArgumentException → plantage, vu le 2026-11-23) ; sur la
     * JVM des tests, il faut ce drapeau pour avoir le même comportement que Python.
     */
    private static final boolean ANDROID =
            String.valueOf(System.getProperty("java.vm.name")).toLowerCase(java.util.Locale.ROOT).contains("dalvik")
            || String.valueOf(System.getProperty("java.vendor")).toLowerCase(java.util.Locale.ROOT).contains("android");
    /** Drapeaux Unicode (équivalent du comportement Python 3 par défaut). */
    public static final int U = ANDROID ? 0 : Pattern.UNICODE_CHARACTER_CLASS;
    /** + insensible à la casse (re.I). */
    public static final int UI = U | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    public static Pattern rx(String r) {
        return Pattern.compile(r, U);
    }

    public static Pattern rxi(String r) {
        return Pattern.compile(r, UI);
    }

    public static final Set<String> STOP = new HashSet<>(Arrays.asList((
            "le la les un une des du de d l au aux et ou a à en dans sur pour par avec sans ce cet cette ces "
            + "que qui quoi dont où est sont il elle on nous vous ils elles je tu se sa son ses leur leurs ne pas plus "
            + "peut peuvent combien comment pourquoi quel quelle quels quelles y t s c n qu j m").split(" ")));

    /** Valeur + unité (« 327 734 habitants », « 2,15 € »). Groupes : 1 = valeur, 2 = unité. */
    public static final Pattern NUM = Pattern.compile(
            "(\\d{1,3}(?:[   .]\\d{3})+|\\d+(?:[.,]\\d+)?)\\s*(habitants|personnes|jours?|semaines?|mois|ans?|années?|heures?|h|minutes?|min|"
            + "secondes?|%|kg|g|km|m|cm|°c|degrés|litres?|l|calories|kcal|euros?|€)\\b", UI);

    private static final Pattern MN = Pattern.compile("\\p{Mn}+");
    private static final Pattern MOTS = Pattern.compile("[a-z0-9]+");

    /** Minuscules sans accents (œ → oe). */
    public static String fold(String s) {
        if (s == null) return "";
        String l = s.toLowerCase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae");
        return MN.matcher(Normalizer.normalize(l, Normalizer.Form.NFD)).replaceAll("");
    }

    /** Mots significatifs (sans mots vides, ≥ 2 lettres), dans l'ordre. */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = MOTS.matcher(fold(s));
        while (m.find()) {
            String w = m.group();
            if (w.length() > 1 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    public static Set<String> tokenSet(String s) {
        return new HashSet<>(tokens(s));
    }

    /** str.strip() de Python : retire aussi les espaces insécables. */
    public static String strip(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    public static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Hôte sans « www. ». */
    public static String domain(String url) {
        try {
            String h = new URI(url).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean find(Pattern p, String s) {
        return p.matcher(s).find();
    }

    /** re.match : ancré au début. */
    public static boolean lookingAt(Pattern p, String s) {
        return p.matcher(s).lookingAt();
    }

    // ---------- passages et phrases ----------

    private static final Pattern LIGNES = Pattern.compile("\n+");

    /** Paragraphes regroupés en passages d'environ 380 caractères (titres et menus écartés). */
    public static List<String> passages(String text) {
        final int size = 380;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : LIGNES.split(text == null ? "" : text)) {
            String para = strip(raw);
            if (para.length() < 80 || (para.endsWith("?") && para.length() < 140)) continue;
            if (cur.length() + para.length() > size && cur.length() > 0) {
                out.add(strip(cur.toString()));
                cur.setLength(0);
            }
            cur.append(' ').append(para);
        }
        if (!strip(cur.toString()).isEmpty()) out.add(strip(cur.toString()));
        return out;
    }

    private static final Pattern SENT_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-ZÀ-Ý0-9«\"])", U);

    public static List<String> decouperPhrases(String texte) {
        List<String> out = new ArrayList<>();
        for (String c : SENT_SPLIT.split(texte == null ? "" : texte)) {
            String chunk = strip(c);
            if (chunk.isEmpty()) continue;
            if (chunk.length() > 220 && chunk.contains(";")) {
                for (String sub : chunk.split(";", -1)) {
                    sub = strip(sub);
                    if (sub.length() >= 45) out.add(sub);
                }
            } else {
                out.add(chunk);
            }
        }
        return out;
    }

    /** Valeur numérique d'un « 327 734 » / « 2,15 ». */
    public static double valeur(String v) {
        return Double.parseDouble(v.replaceAll("[   ]", "").replace(",", "."));
    }

    public static String valeurTexte(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
    }
}
