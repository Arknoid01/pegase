package com.pegasuscorp.orbe.mail;

import com.sun.mail.iap.Response;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.protocol.IMAPResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;

/**
 * Lecture seule de la boîte Gmail en IMAP (imap.gmail.com:993, SSL, mot de passe
 * d'application). Dossier ouvert en READ_ONLY : lire un mail dans Pégase ne le marque
 * pas comme lu dans Gmail.
 *
 * Bloquant (réseau) : à appeler hors du thread principal.
 */
public final class GmailImapClient {

    private static final String HOST = "imap.gmail.com";
    /** Onglet « Principale » de Gmail (sans Promotions / Réseaux sociaux / Notifications). */
    private static final String PRIMARY_QUERY = "in:inbox category:primary newer_than:60d";
    private static final int SNIPPET_MAX = 300;
    private static final int SNIPPETS_FOR = 15;

    private GmailImapClient() {}

    /** Vérifie l'adresse et le mot de passe d'application ; lève une exception sinon. */
    public static void check(String email, String appPassword) throws Exception {
        Store store = connect(email, appPassword);
        store.close();
    }

    /** Derniers mails de l'onglet Principale, du plus récent au plus ancien. */
    public static List<MailMessage> fetchLatest(String email, String appPassword, int max)
            throws Exception {
        Store store = connect(email, appPassword);
        try {
            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                Message[] msgs = primaryMessages(inbox, max);
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(UIDFolder.FetchProfileItem.UID);
                inbox.fetch(msgs, fp);
                List<MailMessage> out = new ArrayList<>();
                int snippets = 0;
                for (int i = msgs.length - 1; i >= 0; i--) {
                    Message m = msgs[i];
                    boolean seen = m.isSet(Flags.Flag.SEEN);
                    String snippet = "";
                    if (!seen && snippets < SNIPPETS_FOR) {
                        snippets++;
                        snippet = snippet(m);
                    }
                    out.add(toMail(email, inbox.getUID(m), m, seen, snippet));
                }
                return out;
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    /** Texte complet d'un mail (pour l'afficher dans Pégase). */
    public static String readBody(String email, String appPassword, long uid) throws Exception {
        Store store = connect(email, appPassword);
        try {
            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                Message m = inbox.getMessageByUID(uid);
                if (m == null) return "";
                String text = MailText.fromPart(m, Integer.MAX_VALUE);
                return text != null ? text : "";
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    private static Store connect(String email, String appPassword) throws Exception {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", HOST);
        p.put("mail.imaps.port", "993");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", "15000");
        p.put("mail.imaps.timeout", "20000");
        Session session = Session.getInstance(p);
        Store store = session.getStore("imaps");
        store.connect(HOST, email, appPassword);
        return store;
    }

    /**
     * Recherche Gmail (X-GM-RAW) pour l'onglet Principale ; si le serveur la refuse, les
     * derniers mails de la boîte.
     */
    private static Message[] primaryMessages(IMAPFolder inbox, int max) throws Exception {
        long[] uids = null;
        try {
            uids = (long[]) inbox.doCommand(protocol -> {
                Response[] r = protocol.command(
                        "UID SEARCH X-GM-RAW \"" + PRIMARY_QUERY + "\"", null);
                List<Long> found = new ArrayList<>();
                for (Response resp : r) {
                    if (!(resp instanceof IMAPResponse)) continue;
                    IMAPResponse ir = (IMAPResponse) resp;
                    if (!ir.keyEquals("SEARCH")) continue;
                    String num;
                    while ((num = ir.readAtomString()) != null && !num.isEmpty()) {
                        try {
                            found.add(Long.parseLong(num));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                protocol.notifyResponseHandlers(r);
                protocol.handleResult(r[r.length - 1]);
                long[] arr = new long[found.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = found.get(i);
                return arr;
            });
        } catch (Exception ignored) {
            // Pas Gmail ou recherche refusée : repli sur les derniers mails.
        }
        if (uids != null) {
            Arrays.sort(uids);
            long[] last = Arrays.copyOfRange(uids, Math.max(0, uids.length - max), uids.length);
            Message[] msgs = inbox.getMessagesByUID(last);
            List<Message> kept = new ArrayList<>();
            for (Message m : msgs) if (m != null) kept.add(m);
            return kept.toArray(new Message[0]);
        }
        int count = inbox.getMessageCount();
        if (count == 0) return new Message[0];
        return inbox.getMessages(Math.max(1, count - max + 1), count);
    }

    private static MailMessage toMail(String account, long uid, Message m, boolean seen,
            String snippet) throws Exception {
        String name = "";
        String address = "";
        Address[] from = m.getFrom();
        if (from != null && from.length > 0) {
            if (from[0] instanceof InternetAddress) {
                InternetAddress ia = (InternetAddress) from[0];
                name = ia.getPersonal() != null ? ia.getPersonal() : "";
                address = ia.getAddress() != null ? ia.getAddress() : "";
            } else {
                address = from[0].toString();
            }
        }
        java.util.Date d = m.getSentDate() != null ? m.getSentDate() : m.getReceivedDate();
        return new MailMessage(account, uid, name, address, m.getSubject(),
                d != null ? d.getTime() : 0L, seen, snippet);
    }

    private static String snippet(Message m) {
        try {
            String t = MailText.fromPart(m, SNIPPET_MAX * 4);
            if (t == null) return "";
            t = t.replaceAll("\\s+", " ").trim();
            return t.length() > SNIPPET_MAX ? t.substring(0, SNIPPET_MAX) + "…" : t;
        } catch (Exception e) {
            return "";
        }
    }
}
