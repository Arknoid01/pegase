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
            // Groupe : dans le dernier message, l'expéditeur est suivi d'un élément « : ».
            + "var group=false,st=r.querySelector('[data-testid=last-msg-status]');"
            + "if(st){[].slice.call(st.children).forEach(function(c){"
            + "if(c.previousElementSibling&&/^:\\s*$/.test(c.textContent))group=true;});}"
            + "out.push({name:t.getAttribute('title'),time:time?time.innerText.trim():'',"
            + "last:(last||'').replace(/\\s+/g,' ').trim().slice(0,200),unread:unread,group:group});});"
            + "return JSON.stringify(out);})()";

    /**
     * Messenger (relevé 2026-10-17) : lignes de {@code [role=grid][aria-label=Discussions]}.
     * Pas de compteur : une discussion non lue a son nom en gras → {@code unread=1}.
     * Groupes repérés par le libellé du lien « Discussion de groupe : … ».
     */
    static final String MESSENGER_JS = "(function(){"
            + "var g=document.querySelector('[role=grid][aria-label=Discussions]');"
            + "if(!g)return 'NO_LIST';"
            + "var out=[];"
            + "[].slice.call(g.querySelectorAll('[role=row]')).forEach(function(r){"
            + "var a=r.querySelector('a[role=link]');if(!a)return;"
            + "var n=a.querySelector('span[dir=auto]');if(!n)return;"
            + "var name=n.innerText.replace(/\\s+/g,' ').trim();if(!name)return;"
            + "var w=function(e){return parseInt(getComputedStyle(e).fontWeight,10)||400;};"
            + "var bold=w(n)>=600||[].slice.call(n.querySelectorAll('span')).some(function(e){return w(e)>=600;});"
            + "var lines=a.innerText.split('\\n').map(function(s){return s.trim();}).filter(Boolean);"
            + "var nl=n.innerText.split('\\n').filter(function(s){return s.trim();}).length;"
            + "var rest=lines.slice(nl);"
            + "var dot=rest.lastIndexOf('·');"
            + "var time=dot>=0?rest.slice(dot+1).join(' '):'';"
            + "var last=(dot>=0?rest.slice(0,dot):rest).join(' ');"
            + "out.push({name:name,time:time,last:last.slice(0,200),unread:bold?1:0,"
            + "group:/^Discussion de groupe/.test(a.getAttribute('aria-label')||'')});"
            + "});"
            + "return JSON.stringify(out);"
            + "})()";

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
