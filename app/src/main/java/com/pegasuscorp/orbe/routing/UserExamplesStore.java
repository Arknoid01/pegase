package com.pegasuscorp.orbe.routing;

import android.content.Context;
import android.util.Log;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apprentissage du routing : phrase normalisée → id d'outil.
 * JSON local + index vectoriel namespace {@link VectorStore#NS_ROUTING}.
 */
public final class UserExamplesStore {

    private static final String TAG = "UserExamples";
    /**
     * Seuil sémantique calibré sur all-MiniLM-L6-v2 (FR).
     * Ex. « t'as eu des soucis » ↔ « tu as eut des problemes » ≈ 0.64.
     * Remonté légèrement : les faux positifs diag (follow-ups) coûtaient plus cher.
     */
    public static final float DEFAULT_MIN_SCORE = 0.62f;

    /**
     * Spinner import : {@code none} + tous les ids du {@link ToolRegistry}
     * (hors composite — trop ambigu pour un label manuel).
     */
    public static final String[] TOOL_OPTIONS = buildToolOptions();

    private static String[] buildToolOptions() {
        TreeSet<String> sorted = new TreeSet<>();
        for (Tool t : new ToolRegistry().listTools(EnumSet.allOf(ToolTag.class))) {
            String id = t.id();
            if (id == null || id.isEmpty()) continue;
            if ("composite".equals(id)) continue;
            sorted.add(id);
        }
        List<String> out = new ArrayList<>(sorted.size() + 1);
        out.add("none");
        out.addAll(sorted);
        return out.toArray(new String[0]);
    }

    public static final class UserExample {
        public final String phrase;
        public final String tool;
        public final int hits;

        public UserExample(String phrase, String tool, int hits) {
            this.phrase = phrase;
            this.tool = tool;
            this.hits = hits;
        }
    }

    public static final class Match {
        public final String tool;
        public final float score;
        public final String matchedPhrase;
        public final boolean exact;

        public Match(String tool, float score, String matchedPhrase, boolean exact) {
            this.tool = tool;
            this.score = score;
            this.matchedPhrase = matchedPhrase;
            this.exact = exact;
        }
    }

    private static final Pattern USER_LINE = Pattern.compile(
            "(?i)^\\s*(?:yannick|user|moi|humain)\\s*:\\s*(.+)$");
    private static final Pattern GENERIC_LINE = Pattern.compile(
            "(?i)^\\s*([A-Za-zÀ-ÿ][\\wÀ-ÿ.\\- ]{0,40})\\s*:\\s*(.+)$");

    private static volatile UserExamplesStore instance;

    private final Context app;
    private final List<UserExample> examples = new ArrayList<>();
    private long lastImportAtMs;
    private VectorStore vectorStore;

    private UserExamplesStore(Context context) {
        this.app = context.getApplicationContext();
        loadFromDisk();
    }

    public static UserExamplesStore getInstance(Context context) {
        if (instance == null) {
            synchronized (UserExamplesStore.class) {
                if (instance == null) {
                    instance = new UserExamplesStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** Visible tests. */
    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public synchronized List<UserExample> listExamples() {
        return new ArrayList<>(examples);
    }

    public synchronized int size() {
        return examples.size();
    }

    public synchronized long getLastImportAtMs() {
        return lastImportAtMs;
    }

    public synchronized Map<String, Integer> countsByTool() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String t : TOOL_OPTIONS) out.put(t, 0);
        for (UserExample e : examples) {
            Integer n = out.get(e.tool);
            if (n == null) out.put(e.tool, 1);
            else out.put(e.tool, n + 1);
        }
        return out;
    }

    public synchronized void addExample(String phrase, String tool) {
        String normalized = foldPhrase(phrase);
        if (normalized.isEmpty()) return;
        String toolId = normalizeToolId(tool);
        if (findExactLocked(normalized) != null) return;

        examples.add(new UserExample(normalized, toolId, 0));
        persistLocked();
        indexVector(normalized, toolId);
    }

    public synchronized void addAll(List<PhraseCandidate> accepted) {
        if (accepted == null) return;
        for (PhraseCandidate c : accepted) {
            if (c == null || !c.accepted) continue;
            String normalized = foldPhrase(c.phrase);
            if (normalized.isEmpty()) continue;
            String toolId = normalizeToolId(c.toolHint);
            if (findExactLocked(normalized) != null) continue;
            examples.add(new UserExample(normalized, toolId, 0));
            indexVector(normalized, toolId);
        }
        lastImportAtMs = System.currentTimeMillis();
        persistLocked();
    }

    public synchronized boolean removeContaining(String phraseFoldOrRaw) {
        String f = foldPhrase(phraseFoldOrRaw);
        if (f.isEmpty()) return false;
        boolean removed = false;
        for (int i = examples.size() - 1; i >= 0; i--) {
            if (examples.get(i).phrase.equals(f) || examples.get(i).phrase.contains(f)) {
                String key = VectorStore.keyFor("routing", examples.get(i).phrase);
                examples.remove(i);
                try {
                    vectors().delete(key);
                } catch (Exception ignored) {
                }
                removed = true;
            }
        }
        if (removed) persistLocked();
        return removed;
    }

    public synchronized void clearAll() {
        examples.clear();
        lastImportAtMs = 0L;
        persistLocked();
        try {
            vectors().clearNamespace(VectorStore.NS_ROUTING);
        } catch (Exception e) {
            Log.w(TAG, "clearNamespace routing", e);
        }
    }

    public synchronized void clearTool(String tool) {
        String toolId = normalizeToolId(tool);
        for (int i = examples.size() - 1; i >= 0; i--) {
            if (toolId.equals(examples.get(i).tool)) {
                String key = VectorStore.keyFor("routing", examples.get(i).phrase);
                examples.remove(i);
                try {
                    vectors().delete(key);
                } catch (Exception ignored) {
                }
            }
        }
        persistLocked();
    }

    /** Exact d'abord, puis sémantique (score ≥ minScore). */
    public Match findMatch(String input, float minScore) {
        String fold = foldPhrase(input);
        if (fold.isEmpty()) return null;

        synchronized (this) {
            UserExample exact = findExactLocked(fold);
            if (exact != null && acceptMatch(exact.tool, fold)) {
                return new Match(exact.tool, 1f, exact.phrase, true);
            }
        }

        try {
            float[] qv = EmbeddingEngine.get(app).embed(fold);
            // Cherche un peu plus bas pour appliquer un filet lexical si besoin
            float floor = Math.min(minScore, 0.58f);
            List<VectorStore.Hit> hits = vectors().search(qv, 5, floor, VectorStore.NS_ROUTING);
            if (hits.isEmpty()) return null;
            for (VectorStore.Hit hit : hits) {
                String tool = toolFromPayload(hit.payload);
                String phrase = phraseFromPayload(hit.payload);
                if (tool == null || tool.isEmpty()) continue;
                if (!acceptMatch(tool, fold)) continue;
                if (hit.score >= minScore) {
                    return new Match(tool, hit.score, phrase != null ? phrase : fold, false);
                }
                // Zone grise : paraphrases FR MiniLM — overlap plus exigeant qu'avant
                if (phrase != null && lexicalOverlap(fold, phrase) >= 0.35f
                        && hit.score >= Math.max(0.58f, minScore - 0.08f)) {
                    return new Match(tool, hit.score, phrase, false);
                }
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "semantic findMatch failed", e);
            return null;
        }
    }

    /**
     * Refuse les follow-ups génériques et les faux positifs diag
     * (ex. « en dire plus », « oui tu peux regarder »).
     */
    static boolean acceptMatch(String tool, String fold) {
        if (tool == null || fold == null || fold.isEmpty()) return false;
        if ("none".equals(tool)) return true;
        if (isGenericFollowUp(fold)) return false;
        if ("diag".equals(tool)) return isPlausibleDiagUtterance(fold);
        return true;
    }

    /** Phrases de conversation qui ne doivent jamais forcer un outil via exemples. */
    static boolean isGenericFollowUp(String fold) {
        if (fold.length() < 4) return true;
        if (fold.equals("alors") || fold.equals("ok") || fold.equals("oui")
                || fold.equals("non") || fold.equals("daccord") || fold.equals("d accord")
                || fold.equals("vas y") || fold.equals("continue")) {
            return true;
        }
        if (fold.contains("en dire plus") || fold.contains("plus de detail")
                || fold.contains("plus precis") || fold.contains("etre un plus precis")
                || fold.contains("un peu plus precis")) {
            return true;
        }
        if (fold.contains("mettre fin") || fold.contains("finir la conversation")
                || fold.contains("arrete la conversation") || fold.contains("stop conversation")) {
            return true;
        }
        // Accords vagues (« oui tu peux regarder ») sans sujet outil
        if (fold.startsWith("oui tu peux") || fold.startsWith("oui vous pouvez")
                || fold.equals("tu peux regarder") || fold.equals("vas y regarde")) {
            return true;
        }
        return false;
    }

    /** Signaux forts de santé session / diagnostic — pas un simple « comment ça va ». */
    static boolean isPlausibleDiagUtterance(String fold) {
        if (fold.contains("diag") || fold.contains("anomalie") || fold.contains("hallucination")
                || fold.contains("bilan") || fold.contains("trace")
                || fold.contains("probleme") || fold.contains("bug")) {
            return true;
        }
        if (fold.contains("souci") || fold.contains("soucis")) return true;
        if (fold.contains("eut des") || fold.contains("eu des souci")
                || fold.contains("eu un probleme") || fold.contains("as eu des")
                || fold.contains("t as eu des") || fold.contains("tas eu des")) {
            return true;
        }
        // Salutations seules → pas diag
        if (fold.contains("comment va") || fold.contains("comment tu")
                || fold.contains("ca va")) {
            return fold.contains("session") || fold.contains("systeme")
                    || fold.contains("toi meme") || fold.contains("de ton cote")
                    || fold.contains("cote tech") || fold.contains("cote technique");
        }
        return false;
    }

    /** Jaccard approximatif sur tokens (≥3 chars) — filet anti faux négatifs FR. */
    static float lexicalOverlap(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        java.util.HashSet<String> ta = tokens(a);
        java.util.HashSet<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0f;
        int inter = 0;
        for (String t : ta) {
            if (tb.contains(t)) inter++;
        }
        int union = ta.size() + tb.size() - inter;
        return union <= 0 ? 0f : (float) inter / (float) union;
    }

    private static java.util.HashSet<String> tokens(String fold) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String t : fold.split("\\s+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    public String findTool(String input, float minScore) {
        Match m = findMatch(input, minScore);
        return m != null ? m.tool : null;
    }

    /**
     * Extrait les phrases utilisateur d'un export conversation .txt.
     * Ignore les lignes Pégase / Orbe / assistant.
     */
    public List<PhraseCandidate> importFromConversation(String txtContent) {
        List<PhraseCandidate> candidates = new ArrayList<>();
        if (txtContent == null || txtContent.isEmpty()) return candidates;
        for (String rawLine : txtContent.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String phrase = extractUserPhrase(line);
            if (phrase == null) continue;
            if (phrase.length() >= 120) continue;
            if (phrase.length() < 2) continue;
            candidates.add(new PhraseCandidate(phrase, guessToolHint(phrase)));
        }
        return candidates;
    }

    static String extractUserPhrase(String line) {
        Matcher m = USER_LINE.matcher(line);
        if (m.matches()) return m.group(1).trim();
        m = GENERIC_LINE.matcher(line);
        if (!m.matches()) return null;
        String speaker = m.group(1).trim().toLowerCase(Locale.ROOT);
        if (speaker.contains("pegase") || speaker.contains("pégase")
                || speaker.contains("orbe") || speaker.contains("assistant")
                || speaker.contains("bot")) {
            return null;
        }
        return m.group(2).trim();
    }

    /** Pré-sélection intelligente de l'outil probable. */
    public static String guessToolHint(String phrase) {
        String f = foldPhrase(phrase);
        if (f.isEmpty()) return "none";

        // Diag / santé session (pas les salutations seules)
        if (f.contains("probleme") || f.contains("bilan") || f.contains("diag")
                || f.contains("eut des") || f.contains("eu des souci")
                || f.contains("eu un probleme") || f.contains("anomalie")
                || f.contains("hallucination") || f.contains("souci")
                || ((f.contains("comment va") || f.contains("comment tu"))
                && (f.contains("session") || f.contains("systeme")
                || f.contains("technique")))) {
            return "diag";
        }

        // Brief matinal
        if (f.contains("brief") || f.contains("resume du jour") || f.contains("resume ma journee")
                || f.contains("quoi de neuf aujourd") || f.contains("point du matin")) {
            return "brief";
        }

        // Orion
        if (f.contains("orion_code") || (f.contains("orion") && (f.contains("code")
                || f.contains("genere") || f.contains("ecris le")))) {
            return "orion_code";
        }
        if (f.contains("orion") && (f.contains("fichier") || f.contains("files"))) {
            return "orion_files";
        }
        if (f.contains("orion") && f.contains("projet")) return "orion_project";
        if (f.contains("orion") || (f.contains("lance") && f.contains("orion"))) {
            return "orion_manager";
        }
        if (f.contains("git commit") || f.contains("commit github") || f.contains("push github")) {
            return "git_commit";
        }

        // Musique / vidéo
        if (f.contains("youtube") || f.contains("video") || f.contains("mets la video")) {
            return "youtube";
        }
        if (f.contains("spotify") || f.contains("musique") || f.contains("playlist")
                || f.contains("mets du ") || f.contains("joue ") || f.contains("ecoute")) {
            return "spotify";
        }

        // Productivité temps
        if (f.contains("minuteur") || f.contains("timer") || f.contains("chronometre")) {
            return "timer";
        }
        if (f.contains("alarme") || f.contains("reveil")) return "alarm";
        if (f.contains("agenda") || f.contains("emploi du temps") || f.contains("rendez vous")
                || f.contains("rdv")
                || ((f.contains("demain") || f.contains("aujourd"))
                        && (f.contains("j ai") || f.contains("jai")
                                || f.contains("quoi") || f.contains("qu est")))
                || f.contains("qu ai je") || f.contains("quoi demain")) {
            return "agenda";
        }
        if (f.contains("calendrier") || f.contains("ajoute un evenement")
                || f.contains("cree un evenement") || f.contains("planifie")) {
            return "calendar";
        }

        // Notes / mémoire
        if ((f.contains("souviens") || f.contains("retenir") || f.contains("memorise")
                || f.contains("dans ta memoire"))
                && !f.contains("alarme")) {
            return "memory";
        }
        if (f.contains("contexte nomme") || f.contains("named context")
                || f.contains("charge le contexte")) {
            return "named_context";
        }
        if ((f.contains("note") || f.contains("ajoute") || f.contains("ecris")
                || f.contains("rappelle") || f.contains("mets dans la liste")
                || f.contains("to do") || f.contains("todo"))
                && !f.contains("alarme") && !f.contains("minuteur")
                && !f.contains("sms") && !f.contains("mail")) {
            return "notepad";
        }

        // Comms
        if (f.contains("sms") || f.contains("texto") || f.contains("envoie un message")) {
            return "sms";
        }
        if (f.contains("appel") || f.contains("telephon") || f.contains("passe un coup de fil")
                || f.contains("compose")) {
            return "call";
        }
        if (f.contains("email") || f.contains("e mail") || f.contains("mail ")
                || f.contains("envoie un mail") || f.contains("courriel")) {
            return "email";
        }
        if (f.contains("contact") || f.contains("numero de")) return "contacts";
        if (f.contains("partage") || f.contains("share")) return "share";

        // Device
        if (f.contains("torch") || f.contains("lampe") || f.contains("flashlight")) {
            return "flashlight";
        }
        if (f.contains("volume") || f.contains("son plus") || f.contains("son moins")
                || f.contains("coupe le son") || f.contains("mute")) {
            return "volume";
        }
        if (f.contains("wifi") || f.contains("bluetooth") || f.contains("avion")
                || f.contains("donnees mobiles") || f.contains("hotspot")) {
            return "connectivity";
        }
        if (f.contains("batterie") || f.contains("niveau de charge") || f.contains("stockage")
                || f.contains("espace disque") || f.contains("infos telephone")) {
            return "device";
        }
        if (f.contains("notif") || f.contains("notification")) return "notifications";
        if (f.contains("presse papier") || f.contains("clipboard") || f.contains("copie dans")
                || f.contains("colle ")) {
            return "clipboard";
        }
        if (f.contains("reglage") || f.contains("parametre") || f.contains("settings")
                || f.contains("ouvre les parametres")) {
            return "settings";
        }
        if (f.contains("itineraire") || f.contains("navigation") || f.contains("gps")
                || f.contains("comment aller") || f.contains("ouvre maps")
                || f.contains("google maps")) {
            return "navigation";
        }
        if (f.contains("ouvre ") || f.contains("lance ") || f.contains("demarre ")
                || f.contains("open app")) {
            if (f.contains("interface") || f.contains("pegase")) return "open_interface";
            return "open_app";
        }
        if (f.contains("interface pegase") || f.contains("open_interface")) {
            return "open_interface";
        }

        // Données / recherche
        if (f.contains("meteo") || f.contains("quel temps") || f.contains("pluie")
                || f.contains("temperature")) {
            return "weather";
        }
        if (f.contains("actu") || f.contains("actualite") || f.contains("news")
                || f.contains("journaux")) {
            return "news";
        }
        if (f.contains("nasa") || f.contains("photo du jour") || f.contains("apod")) {
            return "nasa";
        }
        if (f.contains("wikipedia") || f.contains("wiki ") || f.contains("c est qui")
                || f.contains("cest qui") || f.contains("qui est ")) {
            return "wikipedia";
        }
        if (f.contains("wikidata")) return "wikidata";
        if (f.contains("dans le navigateur") || f.contains("ouvre google")
                || f.contains("web_search") || f.contains("cherche sur le web")) {
            return "web_search";
        }
        if (f.contains("cherche") || f.contains("search") || f.contains("tavily")
                || f.contains("c est quoi") || f.contains("cest quoi")
                || f.contains("dis moi ce que")) {
            return "search";
        }
        if (f.contains("calcule") || f.contains("combien font") || f.contains("pourcentage")
                || f.contains("marge ") || f.contains("multiplie") || f.contains("divise")) {
            return "calculator";
        }

        // Fichiers
        if (f.contains("cree un fichier") || f.contains("create_file")
                || f.contains("nouveau fichier")) {
            return "create_file";
        }
        if (f.contains("fichier") || f.contains("telecharg") || f.contains("dossier")) {
            return "files";
        }

        return "none";
    }

    public static String foldPhrase(String phrase) {
        if (phrase == null) return "";
        return SpeechInputNormalizer.fold(phrase).replace('\'', ' ')
                .replace('\u2019', ' ')
                .replaceAll("[?!.…,;:]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String normalizeToolId(String tool) {
        if (tool == null || tool.trim().isEmpty()) return "none";
        String t = tool.trim().toLowerCase(Locale.ROOT);
        for (String opt : TOOL_OPTIONS) {
            if (opt.equals(t)) return opt;
        }
        return t;
    }

    public synchronized String exportJson() {
        try {
            return rootJsonLocked().toString(2);
        } catch (Exception e) {
            return "{\"examples\":[]}";
        }
    }

    // ── persistence ──────────────────────────────────────────────────────

    private void loadFromDisk() {
        examples.clear();
        File f = PegaseFileSystem.get(app).routingExamplesJson();
        if (!f.exists()) return;
        try {
            byte[] bytes = readAll(f);
            if (bytes.length == 0) return;
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            lastImportAtMs = root.optLong("last_import_at", 0L);
            JSONArray arr = root.optJSONArray("examples");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String phrase = o.optString("phrase", "").trim();
                String tool = normalizeToolId(o.optString("tool", "none"));
                int hits = o.optInt("score", o.optInt("hits", 0));
                if (!phrase.isEmpty()) {
                    examples.add(new UserExample(phrase, tool, hits));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load examples", e);
        }
    }

    private void persistLocked() {
        try {
            File f = PegaseFileSystem.get(app).routingExamplesJson();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(rootJsonLocked().toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "persist examples", e);
        }
    }

    private JSONObject rootJsonLocked() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("last_import_at", lastImportAtMs);
        JSONArray arr = new JSONArray();
        for (UserExample e : examples) {
            arr.put(new JSONObject()
                    .put("phrase", e.phrase)
                    .put("tool", e.tool)
                    .put("score", e.hits));
        }
        root.put("examples", arr);
        return root;
    }

    private UserExample findExactLocked(String normalized) {
        for (UserExample e : examples) {
            if (e.phrase.equals(normalized)) return e;
        }
        return null;
    }

    private VectorStore vectors() {
        if (vectorStore == null) {
            vectorStore = new VectorStore(app);
        }
        return vectorStore;
    }

    private void indexVector(String normalized, String toolId) {
        try {
            float[] vec = EmbeddingEngine.get(app).embed(normalized);
            String payload = new JSONObject()
                    .put("tool", toolId)
                    .put("phrase", normalized)
                    .toString();
            String key = VectorStore.keyFor("routing", normalized);
            vectors().upsert(key, vec, VectorStore.NS_ROUTING, payload);
        } catch (Exception e) {
            Log.w(TAG, "indexVector skipped", e);
        }
    }

    private static String toolFromPayload(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return new JSONObject(payload).optString("tool", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String phraseFromPayload(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return new JSONObject(payload).optString("phrase", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.max(0, f.length())];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off == buf.length) return buf;
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        }
    }
}
