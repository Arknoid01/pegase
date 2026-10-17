package com.pegasuscorp.orbe.webmsg;

import org.json.JSONArray;

/**
 * Scripts de relevé injectés par {@link WebMessagingActivity} via {@code evaluateJavascript}.
 * Chacun renvoie un JSON {@code [{"name","time","last","unread"}…]} (chaîne), ou un mot-clé
 * ({@code NO_LIST}) quand la liste n'est pas affichée (connexion, chargement).
 *
 * Dépend du DOM des sites : à revoir si un relevé revient vide après une mise à jour.
 */
final class WebMsgScrapers {

    /** WhatsApp Web (relevé 2026-10-17) : lignes {@code #pane-side [role=row]}, virtualisées. */
    static final String WHATSAPP_JS = "(function(){"
            + "var rows=[].slice.call(document.querySelectorAll('#pane-side [role=row]'));"
            + "if(!rows.length)return 'NO_LIST';"
            + "var idx=function(r){var m=/(\\d+)$/.exec(r.getAttribute('data-testid')||'');"
            + "return m?parseInt(m[1],10):1e9;};"
            + "rows.sort(function(a,b){return idx(a)-idx(b);});"
            + "var out=[];"
            + "rows.forEach(function(r){"
            + "var t=r.querySelector('[data-testid=cell-frame-title] span[title]');if(!t)return;"
            + "var time=r.querySelector('[data-testid=chat-list-timestamp]');"
            + "var sec=r.querySelector('[data-testid=cell-frame-secondary]');"
            + "var last=sec&&sec.firstElementChild?sec.firstElementChild.innerText:'';"
            + "var b=r.querySelector('[data-testid=icon-unread-count]');"
            + "var unread=b?(parseInt((b.getAttribute('aria-label')||b.innerText)"
            + ".replace(/\\D+/g,''),10)||1):0;"
            + "out.push({name:t.getAttribute('title'),time:time?time.innerText.trim():'',"
            + "last:(last||'').replace(/\\s+/g,' ').trim().slice(0,200),unread:unread});});"
            + "return JSON.stringify(out);})()";

    /** Messenger : pas encore relevé (structure à examiner une fois connecté). */
    static final String MESSENGER_JS = null;

    private WebMsgScrapers() {}

    /** {@code evaluateJavascript} renvoie une valeur JSON : une chaîne arrive entre guillemets. */
    static String unquote(String raw) {
        if (raw == null || raw.equals("null")) return null;
        try {
            return new JSONArray("[" + raw + "]").optString(0, null);
        } catch (Exception e) {
            return null;
        }
    }
}
