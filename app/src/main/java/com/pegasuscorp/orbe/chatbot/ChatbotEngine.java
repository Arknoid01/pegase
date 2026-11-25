package com.pegasuscorp.orbe.chatbot;

import com.pegasuscorp.orbe.search.TexteFr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chatbot hybride (portage de pegase-chatbot/chatbot/engine.py) : règles → banque par sens →
 * décision par zone. Les OUTILS ne sont pas ici : le routeur d'Orbe les traite avant.
 *
 * <ul>
 *   <li>forte : réponse directe (règle ou entrée « direct » de la banque très proche) ;</li>
 *   <li>moyenne : idées de réponse que l'E2B reformule ;</li>
 *   <li>faible : rien de connu, l'E2B répond seul avec les faits des fiches.</li>
 * </ul>
 */
public final class ChatbotEngine {

    /** Calcule des empreintes L2-normalisées (mpnet int8 sur le téléphone). */
    public interface Embed {
        float[][] embed(List<String> texts) throws Exception;
    }

    // Seuils calibrés au banc PC le 2026-11-25 (mpnet, 96 % sur 48 phrases).
    static final float T_FORTE = 0.80f;
    static final float T_MOYENNE = 0.66f;
    static final float T_SANS_ANCRE = 0.90f;
    static final float BONUS_FICHE = 0.08f;
    static final float ECART_IDEE = 0.03f;

    static final Set<String> MOTS_VIDES = new HashSet<>(Arrays.asList((
            "je tu il elle on nous vous ils elles me te se moi toi lui leur mon ma mes ton ta tes son sa ses "
                    + "notre nos votre vos le la les un une des du de d l au aux a et ou mais donc or ni car que qu qui quoi "
                    + "dont ce cet cette ces ca cela c est suis es sont etre ai as avons avez ont avoir fait faire pas ne n plus "
                    + "tres trop bien tout tous toute toutes pour par avec sans dans sur sous en y comme si oui non bon ben "
                    + "alors aussi encore deja juste vraiment peu un peut peux veux veut vais va vas dis dit quand comment pourquoi "
                    + "quel quelle quels quelles aujourd hui demain soir matin jour hier").split(" ")));
    private static final Pattern MOT = Pattern.compile("[a-z0-9]+");
    private static final Pattern SLOT = Pattern.compile("\\{(\\w+)\\}");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F300}-\\x{1FAFF}]");
    private static final Map<String, Integer> NOMBRES = new HashMap<>();

    static {
        String[] n = {"un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix"};
        for (int i = 0; i < n.length; i++) NOMBRES.put(n[i], i + 1);
    }

    public static final class Decision {
        public final String zone;     // forte | moyenne | faible
        public final String source;   // regle:<id> | banque:<id> | aucune
        public String reply = "";
        public List<String> ideas = new ArrayList<>();
        public List<String> facts = new ArrayList<>();
        public List<String> steps = new ArrayList<>();
        public float score;
        public long ms;

        Decision(String zone, String source) {
            this.zone = zone;
            this.source = source;
        }

        @Override public String toString() {
            return zone + " " + source + String.format(java.util.Locale.ROOT, " %.2f %d ms", score, ms);
        }
    }

    private static final class Intent {
        final String id;
        final boolean direct;
        final List<Pattern> patterns = new ArrayList<>();
        final List<String> responses;
        final List<String> steps;

        Intent(JSONObject o) {
            id = o.optString("id");
            direct = o.optBoolean("direct", false);
            for (String p : Fiches.strings(o.optJSONArray("patterns"))) {
                // Groupes nommés Python (?P<x>…) → Java (?<x>…).
                patterns.add(Pattern.compile(p.replace("(?P<", "(?<"), TexteFr.U));
            }
            responses = Fiches.strings(o.optJSONArray("responses"));
            steps = Fiches.strings(o.optJSONArray("steps"));
        }
    }

    private static final class Entry {
        final String id, theme, geste;
        final boolean direct;
        final List<String> responses;
        final Set<String> entites;
        final Set<String> vocab = new HashSet<>();

        Entry(JSONObject o) {
            id = o.optString("id");
            theme = o.optString("theme");
            geste = o.optString("geste");
            direct = o.optBoolean("direct", false);
            responses = Fiches.strings(o.optJSONArray("responses"));
            entites = new HashSet<>(Fiches.strings(o.optJSONArray("entites")));
        }
    }

    private final List<Intent> intents = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Integer> rowEntry = new ArrayList<>();
    private final List<String> rowText = new ArrayList<>();
    private float[][] vectors;
    private final Fiches fiches;
    private final Embed embedder;
    private final Random rng;
    private final boolean phone;

    // État de la conversation.
    private String lastReply = "";
    private final List<String> served = new ArrayList<>();

    /**
     * @param phone code de rire du téléphone (😂) plutôt que celui du PC (x) / ^^)
     */
    public ChatbotEngine(JSONObject rules, JSONObject bank, Fiches fiches, Embed embedder,
            boolean phone, Random rng) {
        this.fiches = fiches;
        this.embedder = embedder;
        this.phone = phone;
        this.rng = rng != null ? rng : new Random();
        JSONArray ints = rules.optJSONArray("intents");
        if (ints != null) for (int i = 0; i < ints.length(); i++) intents.add(new Intent(ints.optJSONObject(i)));
        JSONArray ents = bank.optJSONArray("entries");
        if (ents != null) {
            for (int i = 0; i < ents.length(); i++) {
                JSONObject o = ents.optJSONObject(i);
                Entry e = new Entry(o);
                entries.add(e);
                List<String> qs = Fiches.strings(o.optJSONArray("questions"));
                qs.addAll(Fiches.strings(o.optJSONArray("questions_gemma")));
                for (String q : qs) {
                    addRow(entries.size() - 1, q);
                    // Version sans accents : Yannick tape souvent « arrete », « ca »… et le modèle
                    // découpe alors autrement. On compare dans les deux versions.
                    String bare = Normalizer.stripAccents(q);
                    if (!bare.equals(q)) addRow(entries.size() - 1, bare);
                    e.vocab.addAll(contentWords(Normalizer.normalize(q).key));
                }
            }
        }
    }

    private void addRow(int entry, String q) {
        rowEntry.add(entry);
        rowText.add(Normalizer.normalize(q).text);
    }

    /** Textes de la banque à encoder (dans l'ordre des lignes) — pour le cache des empreintes. */
    public List<String> bankTexts() {
        return Collections.unmodifiableList(rowText);
    }

    /** Empreintes de la banque : lues du cache, ou calculées si {@code null}. */
    public void loadVectors(float[][] cached) throws Exception {
        vectors = cached != null && cached.length == rowText.size() ? cached : embedder.embed(rowText);
    }

    public float[][] vectors() {
        return vectors;
    }

    public Fiches fiches() {
        return fiches;
    }

    static Set<String> contentWords(String key) {
        Set<String> out = new HashSet<>();
        Matcher m = MOT.matcher(key);
        while (m.find()) {
            String w = m.group();
            if (w.length() >= 4 && !MOTS_VIDES.contains(w)) out.add(w.length() > 5 ? w.substring(0, 5) : w);
        }
        return out;
    }

    // ---------------------------------------------------------------- réponses

    private String pick(List<String> variants) {
        List<String> fresh = new ArrayList<>();
        for (String v : variants) if (!served.contains(v)) fresh.add(v);
        if (fresh.isEmpty()) fresh = variants;
        return fresh.get(rng.nextInt(fresh.size()));
    }

    private Map<String, String> slots(Fiches.Entite e, String n) {
        JSONObject m = fiches.moi;
        Map<String, String> s = new HashMap<>();
        for (String k : new String[]{"prenom", "anniversaire", "couleur", "film", "metier", "moment", "plat"}) {
            if (m.has(k)) s.put(k, m.optString(k));
        }
        JSONArray mus = m.optJSONArray("musique");
        if (mus != null) s.put("musique", String.join(" et ", Fiches.strings(mus)));
        else if (m.has("musique")) s.put("musique", m.optString("musique"));
        List<String> projets = new ArrayList<>();
        for (Fiches.Entite x : fiches.entites) if ("projet".equals(x.type)) projets.add(x.nom);
        s.put("liste_projets", String.join(", ", projets));
        if (e != null) {
            s.put("nom", e.nom);
            s.put("role", e.role);
            s.put("etat", !e.etat.isEmpty() ? e.etat : e.role);
            s.put("suite", e.suite);
            s.put("suite_phrase", e.suite.isEmpty() ? "" : " Prochaine étape : " + e.suite + ".");
            s.put("fait", e.faits.isEmpty() ? "" : e.faits.get(0));
        }
        if (n != null) s.put("n", n);
        return s;
    }

    /** Remplit {slot} ; {@code null} si un slot manque (la variante est alors écartée). */
    static String fill(String template, Map<String, String> slots) {
        if (template == null) return null;
        Matcher m = SLOT.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = slots.get(m.group(1));
            if (v == null) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String laughCode(String reply) {
        return phone ? reply.replace("^^", "😂").replace("x)", "😂") : reply;
    }

    // ---------------------------------------------------------------- décision

    /** Décide quoi faire de {@code message}. Ne lève pas : en cas d'échec d'encodage → faible. */
    public synchronized Decision decide(String message) {
        long t0 = System.nanoTime();
        Normalizer.Result nm = Normalizer.normalize(message);
        Decision d;
        try {
            d = decideInner(nm);
        } catch (Exception e) {
            d = new Decision("faible", "aucune");
        }
        d.ms = (System.nanoTime() - t0) / 1_000_000;
        if (!d.reply.isEmpty()) {
            served.add(d.reply);
            while (served.size() > 12) served.remove(0);
            lastReply = d.reply;
        } else if ("faible".equals(d.zone)) {
            lastReply = "";
        }
        return d;
    }

    /** La réponse finale réellement dite (E2B ou idée), pour les suivis « la suite ? ». */
    public synchronized void rememberSpoken(String text) {
        if (text != null && !text.isEmpty()) lastReply = text;
    }

    private Decision decideInner(Normalizer.Result nm) throws Exception {
        String key = nm.key;
        List<Fiches.Entite> entities = fiches.find(key);
        List<String> facts = new ArrayList<>();
        for (Fiches.Entite e : entities) facts.addAll(e.facts());
        Fiches.Entite first = entities.isEmpty() ? null : entities.get(0);

        // 1. Règles d'intention.
        for (Intent it : intents) {
            for (Pattern p : it.patterns) {
                Matcher m = p.matcher(key);
                if (!m.find()) continue;
                Decision d = fromIntent(it, m, entities, facts, nm);
                if (d != null) return d;
            }
        }

        // 2. Banque (recherche par sens).
        if (vectors == null || entries.isEmpty()) {
            Decision d = new Decision("faible", "aucune");
            d.facts = facts;
            return d;
        }
        String bare = Normalizer.stripAccents(nm.text);
        List<String> q = new ArrayList<>();
        q.add(nm.text);
        if (!bare.equals(nm.text)) q.add(bare);
        float[][] qv = embedder.embed(q);
        Set<String> entIds = new HashSet<>();
        for (Fiches.Entite e : entities) entIds.add(e.id);
        // Meilleur score par entrée (sur toutes ses formulations).
        float[] best = new float[entries.size()];
        Arrays.fill(best, -1f);
        for (int r = 0; r < vectors.length; r++) {
            float s = -1f;
            for (float[] v : qv) s = Math.max(s, dot(vectors[r], v));
            int i = rowEntry.get(r);
            if (!entIds.isEmpty() && !Collections.disjoint(entIds, entries.get(i).entites)) s += BONUS_FICHE;
            if (s > best[i]) best[i] = s;
        }
        Integer[] order = new Integer[entries.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(best[b], best[a]));
        // Ancrage : sans mot important commun, il faut une ressemblance très forte.
        Set<String> words = contentWords(key);
        LinkedHashMap<Integer, Float> hits = new LinkedHashMap<>();
        for (int k = 0; k < Math.min(3, order.length); k++) {
            int i = order[k];
            Entry e = entries.get(i);
            boolean anchored = best[i] >= T_SANS_ANCRE || !Collections.disjoint(words, e.vocab)
                    || !Collections.disjoint(entIds, e.entites);
            if (anchored) hits.put(i, best[i]);
        }
        if (hits.isEmpty()) hits.put(order[0], 0f);
        Map.Entry<Integer, Float> top = hits.entrySet().iterator().next();
        int bi = top.getKey();
        float bs = top.getValue();
        Entry be = entries.get(bi);

        List<String> steps = new ArrayList<>();
        if (bs >= T_MOYENNE) steps.add("Je reconnais : " + be.theme + " (" + be.geste + ")");
        if (first != null) steps.add("Je retrouve ma fiche « " + first.nom + " »");
        Map<String, String> sl = slots(first, null);
        if (bs >= T_FORTE && be.direct) {
            String reply = fill(pick(be.responses), sl);
            if (reply != null) {
                Decision d = new Decision("forte", "banque:" + be.id);
                d.reply = laughCode(reply);
                d.facts = facts;
                d.steps = steps;
                d.score = bs;
                return d;
            }
        }
        if (bs >= T_MOYENNE) {
            Decision d = new Decision("moyenne", "banque:" + be.id);
            for (Map.Entry<Integer, Float> h : hits.entrySet()) {
                if (h.getValue() >= T_MOYENNE && h.getValue() >= bs - ECART_IDEE && d.ideas.size() < 2) {
                    String txt = fill(pick(entries.get(h.getKey()).responses), sl);
                    if (txt != null) d.ideas.add(laughCode(txt));
                }
            }
            d.facts = facts;
            steps.add("Je prépare ma réponse…");
            d.steps = steps;
            d.score = bs;
            return d;
        }
        Decision d = new Decision("faible", "aucune");
        d.facts = facts;
        steps.add("Je réfléchis…");
        d.steps = steps;
        d.score = bs;
        return d;
    }

    private Decision fromIntent(Intent it, Matcher m, List<Fiches.Entite> entities, List<String> facts,
            Normalizer.Result nm) {
        String id = it.id;
        List<String> steps = new ArrayList<>(it.steps);
        if (id.equals("travaille_sur")) {
            String x = Normalizer.stripAccents(m.group("x"));
            List<Fiches.Entite> cand = fiches.find(x);
            if (cand.isEmpty()) cand = entities;
            Fiches.Entite projet = null;
            for (Fiches.Entite e : cand) {
                if ("projet".equals(e.type) || "jeu".equals(e.type)) { projet = e; break; }
            }
            if (projet == null) return null;   // projet inconnu : on laisse la banque / l'E2B
            String reply = fill(pick(it.responses), slots(projet, null));
            if (reply == null) return null;
            Decision d = new Decision("forte", "regle:" + id);
            d.reply = laughCode(reply);
            d.facts = projet.facts();
            d.steps = steps;
            d.score = 1f;
            return d;
        }
        if (id.equals("enigme_tous_sauf")) {
            String g = m.group("n");
            String n = NOMBRES.containsKey(g) ? String.valueOf(NOMBRES.get(g)) : g;
            String reply = fill(pick(it.responses), slots(null, n));
            if (reply == null) return null;
            Decision d = new Decision("forte", "regle:" + id);
            d.reply = laughCode(reply);
            d.steps = steps;
            d.score = 1f;
            return d;
        }
        if (id.equals("suite") || id.equals("sur")) {
            // Suivi : l'E2B a besoin du tour précédent.
            Decision d = new Decision("moyenne", "regle:" + id);
            if (!lastReply.isEmpty()) d.ideas.add(lastReply);
            d.facts = facts;
            d.steps = steps;
            d.score = 1f;
            return d;
        }
        if (it.responses.isEmpty()) return null;
        Fiches.Entite first = entities.isEmpty() ? null : entities.get(0);
        String reply = fill(pick(it.responses), slots(first, null));
        if (reply == null) return null;
        boolean emojiPhone = phone || EMOJI.matcher(nm.raw).find();
        if (emojiPhone) reply = reply.replace("^^", "😂").replace("x)", "😂");
        if (nm.laughs && (id.equals("salutation") || id.equals("ca_va"))
                && !(reply.contains("^^") || reply.contains("x)") || reply.contains("😂") || reply.contains("😅"))) {
            reply += emojiPhone ? " 😂" : " x)";
        }
        Decision d = new Decision(it.direct ? "forte" : "moyenne", "regle:" + id);
        if (it.direct) {
            d.reply = reply;
        } else {
            d.ideas.add(reply);
            steps.add("Je prépare ma réponse…");
        }
        d.facts = facts;
        d.steps = steps;
        d.score = 1f;
        return d;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }
}
