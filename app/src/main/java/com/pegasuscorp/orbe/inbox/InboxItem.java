package com.pegasuscorp.orbe.inbox;

/** Une conversation de la Messagerie unifiée (SMS, WhatsApp ou Messenger). */
public final class InboxItem {

    public enum Source { SMS, WHATSAPP, MESSENGER }

    /** Messenger n'a pas de compteur : non lu sans nombre connu. */
    public static final int UNREAD_UNKNOWN_COUNT = -1;

    public final Source source;
    public final String name;
    public final String preview;
    /** Date du dernier message (ms epoch), 0 = inconnue (rangée en fin de liste). */
    public final long timeMs;
    /** Libellé d'heure tel qu'affiché par la source (« 20:42 », « Hier », « 2 sem. »). */
    public final String timeLabel;
    /** 0 = lu ; &gt;0 = nombre de non-lus ; {@link #UNREAD_UNKNOWN_COUNT} = non lu sans nombre. */
    public final int unread;
    public final boolean group;
    /** SMS : numéro de l'expéditeur, pour ouvrir la conversation. Vide sinon. */
    public final String address;

    public InboxItem(Source source, String name, String preview, long timeMs, String timeLabel,
            int unread, boolean group, String address) {
        this.source = source;
        this.name = name != null ? name : "";
        this.preview = preview != null ? preview : "";
        this.timeMs = Math.max(0L, timeMs);
        this.timeLabel = timeLabel != null ? timeLabel : "";
        this.unread = unread;
        this.group = group;
        this.address = address != null ? address : "";
    }

    public boolean isUnread() {
        return unread != 0;
    }
}
