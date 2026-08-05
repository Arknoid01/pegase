package com.pegasuscorp.orbe.copilot;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hints a11y appris / seedés par package — données, pas de if hardcodé dans le matcher.
 */
public final class CopilotAppHints {

    public final String packageName;
    /** Notes injectées dans le prompt copilote. */
    public final List<String> notes;
    /** Alias cible utilisateur → libellé a11y (clés normalisées). */
    public final Map<String, String> aliases;
    /** Gesture avant ACTION_CLICK (Compose / WebView). */
    public final boolean preferGesture;
    /** Inverser : a11y d'abord (apps natives fiables). */
    public final boolean preferA11yFirst;
    /** Matching texte plus strict (anti faux positifs findByText). */
    public final boolean strictTextMatch;
    /** Ne pas faire confiance à un succès a11y seul (fantômes WebView). */
    public final boolean distrustA11yClickSuccess;

    public CopilotAppHints(String packageName,
            List<String> notes,
            Map<String, String> aliases,
            boolean preferGesture,
            boolean preferA11yFirst,
            boolean strictTextMatch,
            boolean distrustA11yClickSuccess) {
        this.packageName = packageName != null ? packageName : "";
        this.notes = notes != null
                ? Collections.unmodifiableList(new ArrayList<>(notes))
                : Collections.emptyList();
        this.aliases = aliases != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(aliases))
                : Collections.emptyMap();
        this.preferGesture = preferGesture;
        this.preferA11yFirst = preferA11yFirst;
        this.strictTextMatch = strictTextMatch;
        this.distrustA11yClickSuccess = distrustA11yClickSuccess;
    }

    public static CopilotAppHints empty(String packageName) {
        return new CopilotAppHints(packageName, null, null,
                true, false, false, false);
    }

    public boolean isEmpty() {
        return notes.isEmpty() && aliases.isEmpty()
                && preferGesture && !preferA11yFirst
                && !strictTextMatch && !distrustA11yClickSuccess;
    }

    /** Résout un alias (ex. « envoyer » → libellé réel). Sinon renvoie la cible. */
    public String resolveAlias(String target) {
        if (TextUtils.isEmpty(target) || aliases.isEmpty()) {
            return target != null ? target.trim() : "";
        }
        String key = A11yUiMatcher.normalizeForMatch(target);
        if (key.isEmpty()) return target.trim();
        String mapped = aliases.get(key);
        if (mapped != null && !mapped.isEmpty()) return mapped;
        return target.trim();
    }

    /** Fusionne un override utilisateur par-dessus un seed (override gagne). */
    public CopilotAppHints mergeOver(CopilotAppHints base) {
        if (base == null || base.isEmpty()) return this;
        List<String> mergedNotes = new ArrayList<>(base.notes);
        for (String n : notes) {
            if (n != null && !n.isEmpty() && !mergedNotes.contains(n)) {
                mergedNotes.add(n);
            }
        }
        Map<String, String> mergedAliases = new LinkedHashMap<>(base.aliases);
        mergedAliases.putAll(aliases);
        return new CopilotAppHints(
                !packageName.isEmpty() ? packageName : base.packageName,
                mergedNotes,
                mergedAliases,
                preferGesture,
                preferA11yFirst,
                strictTextMatch || base.strictTextMatch,
                distrustA11yClickSuccess || base.distrustA11yClickSuccess);
    }

    public String toPromptSection() {
        if (isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Hints a11y pour cette app");
        if (!packageName.isEmpty()) sb.append(" (").append(packageName).append(')');
        sb.append(" :\n");
        for (String n : notes) {
            if (n != null && !n.isEmpty()) sb.append("- ").append(n.trim()).append('\n');
        }
        if (!aliases.isEmpty()) {
            sb.append("- Alias cibles : ");
            boolean first = true;
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (!first) sb.append(" ; ");
                first = false;
                sb.append('«').append(e.getKey()).append("»→«")
                        .append(e.getValue()).append('»');
            }
            sb.append('\n');
        }
        if (strictTextMatch) {
            sb.append("- Matching strict : préfère le libellé exact visible.\n");
        }
        if (distrustA11yClickSuccess || preferGesture) {
            sb.append("- Clics : gesture prioritaire (Compose/WebView peu fiable en a11y).\n");
        }
        if (preferA11yFirst) {
            sb.append("- Clics : ACTION_CLICK a11y prioritaire sur cette app.\n");
        }
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("package", packageName);
            JSONArray na = new JSONArray();
            for (String n : notes) na.put(n);
            o.put("notes", na);
            JSONObject al = new JSONObject();
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                al.put(e.getKey(), e.getValue());
            }
            o.put("aliases", al);
            o.put("prefer_gesture", preferGesture);
            o.put("prefer_a11y_first", preferA11yFirst);
            o.put("strict_text_match", strictTextMatch);
            o.put("distrust_a11y_click", distrustA11yClickSuccess);
        } catch (Exception ignored) {}
        return o;
    }

    public static CopilotAppHints fromJson(String packageName, JSONObject o) {
        if (o == null) return empty(packageName);
        List<String> notes = new ArrayList<>();
        JSONArray na = o.optJSONArray("notes");
        if (na != null) {
            for (int i = 0; i < na.length(); i++) {
                String n = na.optString(i, "").trim();
                if (!n.isEmpty()) notes.add(n);
            }
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        JSONObject al = o.optJSONObject("aliases");
        if (al != null) {
            JSONArray keys = al.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.optString(i, "");
                    String v = al.optString(k, "");
                    if (!k.isEmpty() && !v.isEmpty()) {
                        aliases.put(A11yUiMatcher.normalizeForMatch(k), v.trim());
                    }
                }
            }
        }
        String pkg = o.optString("package", packageName);
        return new CopilotAppHints(
                pkg,
                notes,
                aliases,
                o.optBoolean("prefer_gesture", true),
                o.optBoolean("prefer_a11y_first", false),
                o.optBoolean("strict_text_match", false),
                o.optBoolean("distrust_a11y_click", false));
    }

    static String foldKey(String raw) {
        return A11yUiMatcher.normalizeForMatch(raw);
    }

    @Override
    public String toString() {
        return "CopilotAppHints{" + packageName
                + " notes=" + notes.size()
                + " aliases=" + aliases.size()
                + " strict=" + strictTextMatch
                + " distrust=" + distrustA11yClickSuccess + '}';
    }
}
