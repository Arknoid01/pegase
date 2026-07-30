package com.pegasuscorp.orbe.f1companion;

/** Article RSS F1 (Autosport / Motorsport). */
public final class F1RssItem {
    public final String guid;
    public final String title;
    public final String link;
    public final String description;
    public final String source;

    public F1RssItem(String guid, String title, String link, String description, String source) {
        this.guid = guid != null ? guid : "";
        this.title = title != null ? title : "";
        this.link = link != null ? link : "";
        this.description = description != null ? description : "";
        this.source = source != null ? source : "";
    }

    public String id() {
        if (!guid.isEmpty()) return guid;
        if (!link.isEmpty()) return link;
        return title;
    }

    public String haystack() {
        return (title + " " + description).toLowerCase(java.util.Locale.ROOT);
    }
}
