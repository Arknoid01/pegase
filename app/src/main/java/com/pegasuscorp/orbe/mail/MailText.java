package com.pegasuscorp.orbe.mail;

import javax.mail.Multipart;
import javax.mail.Part;

/** Texte lisible d'un mail : partie text/plain, sinon HTML débarrassé des balises. */
final class MailText {

    private MailText() {}

    /** @param max longueur au-delà de laquelle on arrête de lire (le début suffit pour un aperçu) */
    static String fromPart(Part p, int max) throws Exception {
        String plain = find(p, "text/plain", max);
        if (plain != null && !plain.trim().isEmpty()) return plain.trim();
        String html = find(p, "text/html", max);
        return html != null ? htmlToText(html) : null;
    }

    private static String find(Part p, String mime, int max) throws Exception {
        if (p.isMimeType(mime) && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            Object c = p.getContent();
            if (c instanceof String) {
                String s = (String) c;
                return s.length() > max ? s.substring(0, max) : s;
            }
            return null;
        }
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String s = find(mp.getBodyPart(i), mime, max);
                if (s != null && !s.trim().isEmpty()) return s;
            }
        }
        return null;
    }

    /** Version pure, testable. */
    static String htmlToText(String html) {
        String t = html
                .replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&eacute;", "é").replace("&egrave;", "è").replace("&agrave;", "à");
        return t.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*(\\n\\s*)+", "\n\n").trim();
    }
}
