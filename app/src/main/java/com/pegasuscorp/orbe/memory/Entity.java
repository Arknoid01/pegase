package com.pegasuscorp.orbe.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fiche d'entité de l'atlas (personne, projet, lieu, appareil…). */
public final class Entity {

    public static final String TYPE_PERSON = "person";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_PLACE = "place";
    public static final String TYPE_DEVICE = "device";
    public static final String TYPE_ROUTINE = "routine";
    public static final String TYPE_PREFERENCE = "preference";

    public final String id;
    public final String type;
    public final String name;
    public final List<String> aliases;
    public final JSONObject data;

    public Entity(String id, String type, String name, List<String> aliases, JSONObject data) {
        this.id = id != null ? id : "";
        this.type = type != null ? type : "";
        this.name = name != null ? name : "";
        this.aliases = aliases != null ? aliases : new ArrayList<>();
        this.data = data != null ? data : new JSONObject();
    }

    public List<String> allMatchTerms() {
        List<String> terms = new ArrayList<>();
        if (!name.isEmpty()) terms.add(name);
        terms.addAll(aliases);
        return terms;
    }

    /** Bloc compact pour le prompt (30–80 tokens visés). */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append(labelForType()).append(" : ").append(name);
        String relation = data.optString("relation", "").trim();
        if (!relation.isEmpty()) sb.append(", ").append(relation);
        String status = data.optString("status", "").trim();
        if (!status.isEmpty()) sb.append(" (").append(status).append(")");
        String city = data.optString("city", "").trim();
        if (!city.isEmpty()) sb.append(" — ").append(city);
        JSONArray facts = data.optJSONArray("facts");
        if (facts != null && facts.length() > 0) {
            sb.append(". ");
            int limit = Math.min(3, facts.length());
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(" ");
                sb.append(facts.optString(i, "").trim());
                if (i < limit - 1 && !facts.optString(i, "").endsWith(".")) sb.append(".");
            }
        }
        return sb.toString().trim();
    }

    private String labelForType() {
        return typeLabelFr(type);
    }

    public static String typeLabelFr(String type) {
        if (type == null) return "Entité";
        switch (type) {
            case TYPE_PERSON: return "Personne";
            case TYPE_PROJECT: return "Projet";
            case TYPE_PLACE: return "Lieu";
            case TYPE_DEVICE: return "Appareil";
            case TYPE_ROUTINE: return "Routine";
            case TYPE_PREFERENCE: return "Préférence";
            default: return "Entité";
        }
    }

    public List<String> getFacts() {
        List<String> out = new ArrayList<>();
        JSONArray facts = data.optJSONArray("facts");
        if (facts == null) return out;
        for (int i = 0; i < facts.length(); i++) {
            String f = facts.optString(i, "").trim();
            if (!f.isEmpty()) out.add(f);
        }
        return out;
    }

    public String extraFieldValue() {
        switch (type) {
            case TYPE_PERSON: return data.optString("relation", "");
            case TYPE_PROJECT: return data.optString("status", "");
            case TYPE_PLACE: return data.optString("city", "");
            default: return "";
        }
    }

    public static String extraFieldLabel(String type) {
        switch (type) {
            case TYPE_PERSON: return "Relation";
            case TYPE_PROJECT: return "Statut";
            case TYPE_PLACE: return "Ville";
            default: return "Info";
        }
    }

    public static Entity fromForm(String id, String type, String name, List<String> aliases,
            String extra, List<String> facts) throws Exception {
        switch (type) {
            case TYPE_PERSON:
                return person(id, name, aliases, extra, facts);
            case TYPE_PROJECT:
                return project(id, name, aliases, extra, facts);
            case TYPE_PLACE:
                return place(id, name, aliases, extra, facts);
            case TYPE_DEVICE:
                return device(id, name, aliases, facts);
            default:
                JSONObject data = new JSONObject().put("facts", toJsonArray(facts));
                return new Entity(id, type, name, aliases, data);
        }
    }

    public JSONObject toJson() throws Exception {
        JSONArray aliasArr = new JSONArray();
        for (String alias : aliases) aliasArr.put(alias);
        return new JSONObject()
                .put("id", id)
                .put("type", type)
                .put("name", name)
                .put("aliases", aliasArr)
                .put("data", data);
    }

    public static Entity fromJson(JSONObject o) {
        List<String> aliases = new ArrayList<>();
        JSONArray arr = o.optJSONArray("aliases");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String a = arr.optString(i, "").trim();
                if (!a.isEmpty()) aliases.add(a);
            }
        }
        return new Entity(
                o.optString("id", ""),
                o.optString("type", ""),
                o.optString("name", ""),
                aliases,
                o.optJSONObject("data"));
    }

    public static Entity project(String id, String name, List<String> aliases,
            String status, List<String> facts) throws Exception {
        JSONObject data = new JSONObject().put("status", status);
        data.put("facts", toJsonArray(facts));
        return new Entity(id, TYPE_PROJECT, name, aliases, data);
    }

    public static Entity device(String id, String name, List<String> aliases,
            List<String> facts) throws Exception {
        JSONObject data = new JSONObject().put("facts", toJsonArray(facts));
        return new Entity(id, TYPE_DEVICE, name, aliases, data);
    }

    public static Entity person(String id, String name, List<String> aliases,
            String relation, List<String> facts) throws Exception {
        JSONObject data = new JSONObject().put("relation", relation);
        data.put("facts", toJsonArray(facts));
        return new Entity(id, TYPE_PERSON, name, aliases, data);
    }

    public static Entity place(String id, String name, List<String> aliases,
            String city, List<String> facts) throws Exception {
        JSONObject data = new JSONObject().put("city", city);
        data.put("facts", toJsonArray(facts));
        return new Entity(id, TYPE_PLACE, name, aliases, data);
    }

    private static JSONArray toJsonArray(List<String> items) {
        JSONArray arr = new JSONArray();
        if (items == null) return arr;
        for (String item : items) {
            if (item != null && !item.trim().isEmpty()) arr.put(item.trim());
        }
        return arr;
    }

    public static String foldTerm(String term) {
        if (term == null) return "";
        return term.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('ù', 'u').replace('ô', 'o')
                .replace('\'', ' ').replace('’', ' ')
                .trim();
    }
}
