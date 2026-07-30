package com.pegasuscorp.orbe.f1companion;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSS F1 gratuits — Autosport + Motorsport (formula1.com ne sert plus de flux).
 */
public final class F1RssFetcher {

    private static final String TAG = "F1RssFetcher";
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    public static final String[] FEED_URLS = {
            "https://www.autosport.com/rss/f1/news/",
            "https://www.motorsport.com/rss/f1/news/",
    };

    private F1RssFetcher() {}

    public static List<F1RssItem> fetchAll() {
        List<F1RssItem> all = new ArrayList<>();
        for (String url : FEED_URLS) {
            try {
                all.addAll(fetchFeed(url));
            } catch (Exception e) {
                Log.w(TAG, "feed " + url, e);
            }
        }
        return all;
    }

    static List<F1RssItem> fetchFeed(String urlStr) throws Exception {
        String xml = download(urlStr);
        return parseRss(xml, sourceLabel(urlStr));
    }

    static List<F1RssItem> parseRss(String xml, String source) throws Exception {
        List<F1RssItem> items = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) return items;
        // HTML error page
        String trim = xml.trim().toLowerCase(Locale.ROOT);
        if (trim.startsWith("<!doctype") || trim.startsWith("<html")) return items;

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        String title = null, link = null, guid = null, description = null;
        boolean inItem = false;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("item".equalsIgnoreCase(name)) {
                    inItem = true;
                    title = link = guid = description = null;
                } else if (inItem) {
                    if ("title".equalsIgnoreCase(name)) {
                        title = cleanText(readText(parser));
                    } else if ("link".equalsIgnoreCase(name)) {
                        link = cleanText(readText(parser));
                    } else if ("guid".equalsIgnoreCase(name)) {
                        guid = cleanText(readText(parser));
                    } else if ("description".equalsIgnoreCase(name)) {
                        description = stripHtml(readText(parser));
                    }
                }
            } else if (event == XmlPullParser.END_TAG && "item".equalsIgnoreCase(name) && inItem) {
                inItem = false;
                if (title != null && !title.isEmpty()) {
                    items.add(new F1RssItem(guid, title, link, description, source));
                }
            }
            event = parser.next();
        }
        return items;
    }

    private static String readText(XmlPullParser parser) throws Exception {
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        return result != null ? result : "";
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        Matcher m = CDATA.matcher(s);
        if (m.find()) s = m.group(1);
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .trim();
    }

    static String stripHtml(String raw) {
        if (raw == null) return "";
        String s = cleanText(raw);
        s = TAGS.matcher(s).replaceAll(" ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String sourceLabel(String url) {
        if (url == null) return "RSS";
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("autosport")) return "Autosport";
        if (u.contains("motorsport")) return "Motorsport";
        return "RSS";
    }

    private static String download(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0 (F1 Companion)");
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
        }
        conn.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + " for " + urlStr);
        }
        return sb.toString();
    }
}
