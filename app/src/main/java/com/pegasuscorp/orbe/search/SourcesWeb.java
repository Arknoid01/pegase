package com.pegasuscorp.orbe.search;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sources du moteur privé (portage de sources/phone.py) : Brave Search API (web, avec clé),
 * Wikipédia (extraits d'intro), Bing Actualités RSS (articles datés) et Bing RSS (secours web).
 * Les réponses de Brave ne sont JAMAIS mises en cache (conditions de l'offre Search).
 */
public final class SourcesWeb {

    private SourcesWeb() {}

    public static final class Resultat {
        public final String url, titre, extrait, date, source;

        Resultat(String url, String titre, String extrait, String date, String source) {
            this.url = url;
            this.titre = titre;
            this.extrait = extrait;
            this.date = date;
            this.source = source;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String get(String url, Map<String, String> entetes, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", LecteurPages.UA);
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9");
        if (entetes != null) for (Map.Entry<String, String> e : entetes.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------- Brave ----------

    /** Web général. La question telle quelle (Brave comprend le langage naturel) : 1 requête = 1 crédit. */
    public static List<Resultat> brave(String question, String cle, int n) throws Exception {
        Map<String, String> h = new TreeMap<>();
        h.put("Accept", "application/json");
        h.put("X-Subscription-Token", cle);
        String url = "https://api.search.brave.com/res/v1/web/search?q=" + enc(question)
                + "&country=fr&search_lang=fr&ui_lang=fr-FR&count=" + n
                + "&extra_snippets=true&safesearch=moderate";
        JSONObject j = new JSONObject(get(url, h, 10_000));
        List<Resultat> out = new ArrayList<>();
        JSONObject web = j.optJSONObject("web");
        JSONArray res = web != null ? web.optJSONArray("results") : null;
        if (res == null) return out;
        for (int i = 0; i < res.length() && out.size() < n; i++) {
            JSONObject it = res.getJSONObject(i);
            String u = it.optString("url");
            if (!u.startsWith("http")) continue;
            StringBuilder ex = new StringBuilder(LecteurPages.nettoyer(it.optString("description")));
            JSONArray extra = it.optJSONArray("extra_snippets");
            if (extra != null) for (int k = 0; k < extra.length(); k++) {
                String e = LecteurPages.nettoyer(extra.optString(k));
                if (!e.isEmpty()) ex.append(' ').append(e);
            }
            String age = it.optString("page_age", "");
            out.add(new Resultat(u, LecteurPages.nettoyer(it.optString("title")), ex.toString().trim(),
                    age.length() >= 10 ? age.substring(0, 10) : "", "brave"));
        }
        return out;
    }

    // ---------- Wikipédia ----------

    /** Recherche + extraits d'intro en un seul appel (texte brut, rien à télécharger). */
    public static List<Resultat> wiki(String requete, int n) throws Exception {
        String url = "https://fr.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch="
                + enc(requete) + "&gsrlimit=" + n
                + "&prop=extracts%7Cinfo&exintro=1&explaintext=1&inprop=url&redirects=1";
        JSONObject q = new JSONObject(get(url, null, 8_000)).optJSONObject("query");
        List<Resultat> out = new ArrayList<>();
        if (q == null || q.optJSONObject("pages") == null) return out;
        JSONObject pages = q.getJSONObject("pages");
        List<JSONObject> l = new ArrayList<>();
        for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) l.add(pages.getJSONObject(it.next()));
        l.sort((a, b) -> Integer.compare(a.optInt("index", 99), b.optInt("index", 99)));
        for (JSONObject p : l) {
            String txt = p.optString("extract", "").trim();
            if (txt.length() < 80) continue;
            String u = p.optString("fullurl", "https://fr.wikipedia.org/wiki/" + p.optString("title"));
            out.add(new Resultat(u, p.optString("title"), txt, "", "wiki"));
        }
        return out;
    }

    // ---------- Bing (RSS) ----------

    private static final Pattern ITEM = Pattern.compile("(?s)<item>(.*?)</item>");

    private static String balise(String item, String nom) {
        Matcher m = Pattern.compile("(?s)<" + nom + ">(.*?)</" + nom + ">").matcher(item);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[")) v = v.substring(9, v.length() - 3);
        return v;
    }

    /** Liens Bing Actu « apiclick.aspx?…&url=… » → adresse réelle. */
    static String urlBing(String lien) {
        String l = lien.replace("&amp;", "&");
        int i = l.indexOf("apiclick");
        if (l.contains("bing.com") && i > 0) {
            Matcher m = Pattern.compile("[?&]url=([^&]+)").matcher(l);
            if (m.find()) {
                try {
                    return URLDecoder.decode(m.group(1), "UTF-8");
                } catch (Exception ignored) {
                }
            }
        }
        return l;
    }

    private static List<Resultat> rss(String xml, String source, int n) {
        List<Resultat> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml);
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        while (m.find() && out.size() < n) {
            String it = m.group(1);
            String u = urlBing(balise(it, "link"));
            if (!u.startsWith("http")) continue;
            String date = "";
            try {
                Date d = rfc.parse(balise(it, "pubDate"));
                if (d != null) date = iso.format(d);
            } catch (Exception ignored) {
                // Bing web : dates en français, on s'en passe
            }
            out.add(new Resultat(u, LecteurPages.nettoyer(balise(it, "title")),
                    LecteurPages.nettoyer(balise(it, "description")), date, source));
        }
        return out;
    }

    public static List<Resultat> actu(String requete, int n) throws Exception {
        return rss(get("https://www.bing.com/news/search?q=" + enc(requete)
                + "&format=rss&setlang=fr&cc=fr", null, 8_000), "news", n);
    }

    /** Secours web sans clé (médiocre sur les phrases-questions : requêtes « sujet d'abord »). */
    public static List<Resultat> bingWeb(String requete, int n) throws Exception {
        return rss(get("https://www.bing.com/search?q=" + enc(requete)
                + "&format=rss&setlang=fr&cc=fr", null, 8_000), "bing", n);
    }
}
