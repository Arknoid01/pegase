package com.pegasuscorp.orbe.search;

import com.pegasuscorp.orbe.tools.knowledge.WebReadTool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Télécharge une page et en garde le TEXTE DE L'ARTICLE (paragraphes, un par ligne) —
 * équivalent simplifié de trafilatura (prototype PC). https seulement, pas de réseau local
 * (WebReadTool.checkUrl), 600 Ko au plus.
 */
public final class LecteurPages {

    private LecteurPages() {}

    public static final String UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";
    private static final int MAX_OCTETS = 600_000;

    /** [titre, texte] ; texte vide si illisible. */
    public static String[] lire(String url, int connectMs, int readMs) {
        try {
            String u = url;
            for (int saut = 0; saut <= 3; saut++) {
                WebReadTool.checkUrl(u);
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9");
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    u = new URL(new URL(u), loc).toString();
                    c.disconnect();
                    continue;
                }
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
                if (code != 200 || !type.contains("html")) {
                    c.disconnect();
                    return new String[]{"", ""};
                }
                byte[] corps = lireBorne(c.getInputStream());
                c.disconnect();
                String html = new String(corps, charset(type, corps));
                return new String[]{titre(html), extraireArticle(html)};
            }
        } catch (Exception ignored) {
        }
        return new String[]{"", ""};
    }

    private static byte[] lireBorne(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_OCTETS) break;
        }
        in.close();
        return out.toByteArray();
    }

    private static final Pattern CS_EN_TETE = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CS_META = Pattern.compile(
            "<meta[^>]+charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private static Charset charset(String type, byte[] corps) {
        Matcher m = CS_EN_TETE.matcher(type);
        String nom = m.find() ? m.group(1) : null;
        if (nom == null) {
            String debut = new String(corps, 0, Math.min(corps.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher mm = CS_META.matcher(debut);
            if (mm.find()) nom = mm.group(1);
        }
        try {
            return nom != null ? Charset.forName(nom) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final Pattern TITRE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private static String titre(String html) {
        Matcher m = TITRE.matcher(html);
        return m.find() ? nettoyer(m.group(1)) : "";
    }

    private static final Pattern BRUIT = Pattern.compile(
            "(?is)<(script|style|noscript|svg|head|nav|header|footer|aside|form|figure|iframe|button|select)"
            + "\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENTAIRE = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern CORPS_ARTICLE = Pattern.compile("(?is)<(article|main)\\b[^>]*>(.*)</\\1\\s*>");
    private static final Pattern BLOC = Pattern.compile(
            "(?is)<(p|li|h2|h3|blockquote|dd)\\b[^>]*>(.*?)</\\1\\s*>");

    /** Texte des paragraphes de l'article (un par ligne) ; repli : tout le texte visible. */
    static String extraireArticle(String html) {
        String h = COMMENTAIRE.matcher(html).replaceAll(" ");
        h = BRUIT.matcher(h).replaceAll(" ");
        Matcher ma = CORPS_ARTICLE.matcher(h);
        String zone = ma.find() && ma.group(2).length() > 500 ? ma.group(2) : h;
        StringBuilder sb = new StringBuilder();
        Matcher m = BLOC.matcher(zone);
        while (m.find()) {
            String t = nettoyer(m.group(2));
            if (t.length() >= 25) sb.append(t).append('\n');
        }
        String texte = sb.toString().trim();
        if (texte.length() >= 200) return texte;
        return nettoyer(h.replaceAll("(?i)</(p|div|li|h[1-6]|tr|section|article)>|<br\\s*/?>", "\n"))
                .replaceAll(" *\n[ \n]*", "\n");
    }

    private static final Pattern BALISE = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern ENTITE_NUM = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** Balises retirées, entités décodées, espaces compactés (les \n restent). */
    static String nettoyer(String s) {
        String t = BALISE.matcher(s).replaceAll(" ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&rsquo;", "’").replace("&lsquo;", "‘")
                .replace("&laquo;", "«").replace("&raquo;", "»").replace("&hellip;", "…")
                .replace("&ndash;", "–").replace("&mdash;", "—").replace("&euro;", "€")
                .replace("&eacute;", "é").replace("&egrave;", "è").replace("&agrave;", "à")
                .replace("&ccedil;", "ç").replace("&ecirc;", "ê").replace("&ocirc;", "ô");
        Matcher m = ENTITE_NUM.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String rep;
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                rep = new String(Character.toChars(cp));
            } catch (Exception e) {
                rep = " ";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString().replaceAll("[ \\t\\x0B\\f\\r ]+", " ").trim();
    }
}
