package com.pegasuscorp.orbe.mail;

/** Un mail de la boîte de réception (en-tête + début du texte). */
public final class MailMessage {

    public final String account;
    public final long uid;
    public final String fromName;
    public final String fromAddress;
    public final String subject;
    public final long dateMs;
    public final boolean seen;
    /** Début du texte (mails non lus seulement, vide sinon). */
    public final String snippet;

    public MailMessage(String account, long uid, String fromName, String fromAddress,
            String subject, long dateMs, boolean seen, String snippet) {
        this.account = account;
        this.uid = uid;
        this.fromName = fromName != null ? fromName : "";
        this.fromAddress = fromAddress != null ? fromAddress : "";
        this.subject = subject != null ? subject : "";
        this.dateMs = dateMs;
        this.seen = seen;
        this.snippet = snippet != null ? snippet : "";
    }

    public String sender() {
        return !fromName.isEmpty() ? fromName : fromAddress;
    }
}
