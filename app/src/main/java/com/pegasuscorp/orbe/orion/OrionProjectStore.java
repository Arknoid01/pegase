package com.pegasuscorp.orbe.orion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.orion.search.OrionCodeIndexService;
import com.pegasuscorp.orbe.orion.search.OrionFileSearcher;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Workspace local Orion : {@code files/orion/projects/&lt;projet&gt;/}.
 * Local = source de vérité. Push GitHub = décision explicite (jamais auto).
 */
public final class OrionProjectStore {

    public interface Observer {
        void onProjectChanged();
    }

    public enum SaveOutcome {
        CREATED,
        REPLACED,
        SKIPPED,
        /** Fichier existe — UI doit demander Remplacer / Nouveau / Ignorer. */
        NEEDS_CONFIRM
    }

    public static final class SaveResult {
        public final SaveOutcome outcome;
        public final String path;
        public final String message;

        public SaveResult(SaveOutcome outcome, String path, String message) {
            this.outcome = outcome;
            this.path = path == null ? "" : path;
            this.message = message == null ? "" : message;
        }
    }

    public static final class ProjectFile {
        public final String name;
        public final File file;
        public final long modified;
        public final long sizeBytes;

        public ProjectFile(String name, File file, long modified, long sizeBytes) {
            this.name = name;
            this.file = file;
            this.modified = modified;
            this.sizeBytes = sizeBytes;
        }

        public int lineCount() {
            try {
                String c = readUtf8(file);
                if (c.isEmpty()) return 0;
                int n = 1;
                for (int i = 0; i < c.length(); i++) {
                    if (c.charAt(i) == '\n') n++;
                }
                return n;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static final String PREFS = "orion_projects";
    private static final String KEY_ACTIVE = "active_project";

    private static OrionProjectStore instance;

    private final Context appContext;
    private final File projectsRoot;
    private final SharedPreferences prefs;
    private String activeProject;
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private VectorStore vectorStore;

    private OrionProjectStore(Context context) {
        appContext = context.getApplicationContext();
        projectsRoot = PegaseFileSystem.get(appContext).orionProjectsDir();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        activeProject = prefs.getString(KEY_ACTIVE, "");
        if (!TextUtils.isEmpty(activeProject)) {
            File dir = new File(projectsRoot, sanitizeProjectName(activeProject));
            if (!dir.isDirectory()) {
                activeProject = "";
                prefs.edit().remove(KEY_ACTIVE).apply();
            }
        }
        if (TextUtils.isEmpty(activeProject)) {
            List<String> all = listProjects();
            if (!all.isEmpty()) {
                setActive(all.get(0), false);
            }
        }
    }

    public static synchronized OrionProjectStore get(Context ctx) {
        if (instance == null) {
            instance = new OrionProjectStore(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public void addObserver(Observer o) {
        if (o != null) observers.addIfAbsent(o);
    }

    public void removeObserver(Observer o) {
        if (o != null) observers.remove(o);
    }

    private void notifyChanged() {
        for (Observer o : observers) {
            try {
                o.onProjectChanged();
            } catch (Exception ignored) {
            }
        }
    }

    public File getProjectsRoot() {
        return projectsRoot;
    }

    public synchronized String getActiveProject() {
        return activeProject == null ? "" : activeProject;
    }

    public synchronized boolean hasActiveProject() {
        if (TextUtils.isEmpty(activeProject)) return false;
        String name = sanitizeProjectName(activeProject);
        if (name.isEmpty()) return false;
        return new File(projectsRoot, name).isDirectory();
    }

    /** Liste des noms de projets (dossiers). */
    public synchronized List<String> listProjects() {
        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (File d : dirs) {
            if (d != null && isSafeProjectName(d.getName())) out.add(d.getName());
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public synchronized File getProjectDir(String projectName) {
        String name = sanitizeProjectName(projectName);
        if (name.isEmpty()) return projectsRoot;
        File d = new File(projectsRoot, name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * Crée un projet et l'active.
     * @return nom final (slug) ou vide si invalide
     */
    public synchronized String createProject(String rawName) {
        String name = sanitizeProjectName(rawName);
        if (name.isEmpty()) return "";
        File dir = new File(projectsRoot, name);
        if (!dir.exists() && !dir.mkdirs()) return "";
        setActive(name, true);
        return name;
    }

    /**
     * Switch de projet + charge le contexte nommé si trouvé
     * (ex. balle-html → pas de md ; fableris-ui → fableris).
     */
    public synchronized boolean setActive(String projectName) {
        return setActive(projectName, true);
    }

    private synchronized boolean setActive(String projectName, boolean loadContext) {
        String name = sanitizeProjectName(projectName);
        if (name.isEmpty()) return false;
        File dir = new File(projectsRoot, name);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        activeProject = name;
        prefs.edit().putString(KEY_ACTIVE, name).apply();
        if (loadContext) {
            tryLoadProjectContext(name);
        }
        notifyChanged();
        OrionCodeIndexService.get().scheduleIndexActiveProject(appContext);
        return true;
    }

    /** Heuristique : charge un contexte ContextualFileStore lié au nom du projet. */
    private void tryLoadProjectContext(String projectName) {
        try {
            ContextualFileStore store = ContextualFileStore.getInstance(appContext);
            String lower = projectName.toLowerCase(Locale.ROOT);
            String[] candidates = {
                    lower,
                    lower.replace("-", ""),
                    lower.contains("fableris") ? "fableris" : null,
                    lower.contains("boucherie") || lower.contains("scanner") ? "boucherie" : null,
                    lower.contains("orion") ? "orion" : null,
                    lower.contains("olympos") ? "olympos" : null,
                    lower.contains("iris") ? "irisforge" : null,
                    lower.contains("pegase") || lower.contains("pégase") ? "pegase" : null,
            };
            for (String c : candidates) {
                if (c == null || c.isEmpty()) continue;
                String msg = store.load(c);
                if (msg != null && !msg.toLowerCase(Locale.ROOT).contains("introuvable")
                        && !msg.toLowerCase(Locale.ROOT).contains("aucun")) {
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized List<ProjectFile> getProjectFiles() {
        if (!hasActiveProject()) return Collections.emptyList();
        return listFilesIn(getProjectDir(activeProject));
    }

    public synchronized List<ProjectFile> getProjectFiles(String projectName) {
        return listFilesIn(getProjectDir(projectName));
    }

    private static List<ProjectFile> listFilesIn(File dir) {
        List<ProjectFile> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (f != null && f.isFile() && !f.getName().startsWith(".")) {
                out.add(new ProjectFile(f.getName(), f, f.lastModified(), f.length()));
            }
        }
        return out;
    }

    public synchronized boolean fileExists(String filename) {
        if (!hasActiveProject()) return false;
        File f = new File(getProjectDir(activeProject), sanitizeFilename(filename));
        return f.isFile();
    }

    /**
     * Sauvegarde dans le projet actif.
     * @param replace true = écraser ; false + existe → {@link SaveOutcome#NEEDS_CONFIRM}
     * @param asNew   true = écrire sous un nom libre (fichier_2.ext) si conflit
     */
    public synchronized SaveResult saveFile(String filename, String content,
            boolean replace, boolean asNew) {
        if (!hasActiveProject()) {
            return new SaveResult(SaveOutcome.SKIPPED, filename,
                    "Aucun projet actif — crée ou sélectionne un projet.");
        }
        String name = sanitizeFilename(filename);
        if (name.isEmpty()) {
            return new SaveResult(SaveOutcome.SKIPPED, "", "Nom de fichier invalide.");
        }
        File dir = getProjectDir(activeProject);
        File target = new File(dir, name);
        if (target.exists() && !replace && !asNew) {
            return new SaveResult(SaveOutcome.NEEDS_CONFIRM, name,
                    name + " existe déjà dans « " + activeProject + " ».");
        }
        if (target.exists() && asNew) {
            name = uniqueName(dir, name);
            target = new File(dir, name);
        }
        try {
            writeUtf8(target, content == null ? "" : content);
            indexProjectFile(activeProject, name, content);
            if (name.toLowerCase(Locale.ROOT).endsWith(".java")) {
                OrionCodeIndexService.get().scheduleReindexFile(
                        appContext, activeProject, name, content);
            }
            boolean wasReplace = replace && !asNew;
            notifyChanged();
            return new SaveResult(
                    wasReplace ? SaveOutcome.REPLACED : SaveOutcome.CREATED,
                    name,
                    wasReplace
                            ? name + " remplacé dans « " + activeProject + " »."
                            : name + " ajouté au projet « " + activeProject + " ».");
        } catch (Exception e) {
            return new SaveResult(SaveOutcome.SKIPPED, name,
                    "Écriture impossible : " + (e.getMessage() == null ? "erreur" : e.getMessage()));
        }
    }

    /** Force remplace (après confirmation UI). */
    public synchronized SaveResult replaceFile(String filename, String content) {
        return saveFile(filename, content, true, false);
    }

    /** Garde les deux (nouveau nom). */
    public synchronized SaveResult saveAsNew(String filename, String content) {
        return saveFile(filename, content, false, true);
    }

    public synchronized String readFile(String filename) {
        if (!hasActiveProject()) return "";
        File f = new File(getProjectDir(activeProject), sanitizeFilename(filename));
        if (!f.isFile()) return "";
        try {
            return readUtf8(f);
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized boolean deleteFile(String filename) {
        if (!hasActiveProject()) return false;
        String name = sanitizeFilename(filename);
        File f = new File(getProjectDir(activeProject), name);
        boolean ok = f.isFile() && f.delete();
        if (ok) {
            deleteProjectFileIndex(activeProject, name);
            OrionCodeIndexService.get().schedulePurgeFile(appContext, activeProject, name);
            File snap = new File(pushSnapshotDir(), name);
            //noinspection ResultOfMethodCallIgnored
            if (snap.isFile()) snap.delete();
            notifyChanged();
        }
        return ok;
    }

    /**
     * Supprime un projet (dossier + snapshots). Confirmation UI obligatoire.
     * @return true si le dossier a été effacé
     */
    public synchronized boolean deleteProject(String projectName) {
        String name = sanitizeProjectName(projectName);
        if (name.isEmpty()) return false;
        File dir = new File(projectsRoot, name);
        if (!dir.isDirectory()) return false;
        List<ProjectFile> existing = listFilesIn(dir);
        boolean ok = deleteRecursive(dir);
        if (ok) {
            for (ProjectFile pf : existing) {
                if (pf != null) deleteProjectFileIndex(name, pf.name);
            }
        }
        if (ok && name.equals(activeProject)) {
            activeProject = "";
            prefs.edit().remove(KEY_ACTIVE).apply();
            List<String> left = listProjects();
            if (!left.isEmpty()) {
                setActive(left.get(0), false);
            } else {
                notifyChanged();
            }
        } else if (ok) {
            notifyChanged();
        }
        return ok;
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        return f.delete();
    }

    /**
     * Renomme un fichier du projet actif.
     * @return nouveau nom, ou vide si échec
     */
    public synchronized String renameFile(String from, String to) {
        if (!hasActiveProject()) return "";
        String oldName = sanitizeFilename(from);
        String newName = sanitizeFilename(to);
        if (oldName.isEmpty() || newName.isEmpty()) return "";
        if (oldName.equals(newName)) return oldName;
        File dir = getProjectDir(activeProject);
        File src = new File(dir, oldName);
        File dst = new File(dir, newName);
        if (!src.isFile() || dst.exists()) return "";
        if (!src.renameTo(dst)) return "";
        deleteProjectFileIndex(activeProject, oldName);
        OrionCodeIndexService.get().schedulePurgeFile(appContext, activeProject, oldName);
        try {
            String content = readUtf8(dst);
            indexProjectFile(activeProject, newName, content);
            if (newName.toLowerCase(Locale.ROOT).endsWith(".java")) {
                OrionCodeIndexService.get().scheduleReindexFile(
                        appContext, activeProject, newName, content);
            }
        } catch (Exception ignored) {
        }
        File snapSrc = new File(pushSnapshotDir(), oldName);
        if (snapSrc.isFile()) {
            File snapDst = new File(pushSnapshotDir(), newName);
            //noinspection ResultOfMethodCallIgnored
            snapSrc.renameTo(snapDst);
        }
        notifyChanged();
        return newName;
    }

    /** Pour push : tous les fichiers du projet actif. */
    public synchronized List<OrionFileSession.OrionFile> toOrionFiles() {
        return toOrionFiles(null);
    }

    /**
     * Pour push sélectif.
     * @param onlyNames null / vide = tous les fichiers ; sinon filtre (noms exacts)
     */
    public synchronized List<OrionFileSession.OrionFile> toOrionFiles(
            Collection<String> onlyNames) {
        Set<String> filter = null;
        if (onlyNames != null && !onlyNames.isEmpty()) {
            filter = new HashSet<>();
            for (String n : onlyNames) {
                String s = sanitizeFilename(n);
                if (!s.isEmpty()) filter.add(s);
            }
        }
        List<OrionFileSession.OrionFile> out = new ArrayList<>();
        for (ProjectFile pf : getProjectFiles()) {
            if (filter != null && !filter.contains(pf.name)) continue;
            try {
                String c = readUtf8(pf.file);
                out.add(new OrionFileSession.OrionFile(
                        pf.name, c, OrionFileSession.FileStatus.VALIDATED));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Snapshot local après un push réussi — base pour le diff « depuis dernier push ». */
    public synchronized void recordPushSnapshot(Collection<String> filenames) {
        if (!hasActiveProject()) return;
        File snapDir = pushSnapshotDir();
        List<ProjectFile> files = getProjectFiles();
        Set<String> want = null;
        if (filenames != null && !filenames.isEmpty()) {
            want = new HashSet<>();
            for (String n : filenames) {
                String s = sanitizeFilename(n);
                if (!s.isEmpty()) want.add(s);
            }
        }
        for (ProjectFile pf : files) {
            if (want != null && !want.contains(pf.name)) continue;
            try {
                writeUtf8(new File(snapDir, pf.name), readUtf8(pf.file));
            } catch (Exception ignored) {
            }
        }
    }

    /** Contenu au dernier push local, ou {@code null} si jamais snapshoté. */
    public synchronized String getLastPushedContent(String filename) {
        if (!hasActiveProject()) return null;
        File f = new File(pushSnapshotDir(), sanitizeFilename(filename));
        if (!f.isFile()) return null;
        try {
            return readUtf8(f);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean hasChangedSincePush(String filename) {
        String pushed = getLastPushedContent(filename);
        if (pushed == null) return false; // pas de base → pas de badge « modifié »
        String local = readFile(filename);
        return !pushed.equals(local);
    }

    private File pushSnapshotDir() {
        File d = new File(getProjectDir(activeProject), ".push");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public synchronized String speakSummary() {
        if (!hasActiveProject()) {
            List<String> all = listProjects();
            if (all.isEmpty()) {
                return "Aucun projet Orion. Dis « nouveau projet mon-app » pour commencer.";
            }
            return "Pas de projet actif. Projets : " + String.join(", ", all) + ".";
        }
        List<ProjectFile> files = getProjectFiles();
        StringBuilder sb = new StringBuilder();
        sb.append("Projet « ").append(activeProject).append(" » : ")
                .append(files.size()).append(" fichier")
                .append(files.size() > 1 ? "s" : "").append('.');
        for (ProjectFile f : files) {
            sb.append(" · ").append(f.name);
        }
        return sb.toString();
    }

    public static String sanitizeProjectName(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9._\\-]+", "-");
        t = t.replaceAll("^-+|-+$", "");
        if (t.length() > 48) t = t.substring(0, 48);
        if (!isSafeProjectName(t)) return "";
        return t;
    }

    private static boolean isSafeProjectName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9._\\-]{0,47}");
    }

    static String sanitizeFilename(String filename) {
        if (filename == null) return "";
        String name = filename.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "";
        if (!name.contains(".")) name += ".txt";
        if (name.length() > 80) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : ".txt";
            name = name.substring(0, Math.min(60, name.length())) + ext;
        }
        return name;
    }

    private static String uniqueName(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 2;
        while (true) {
            String candidate = base + "_" + i + ext;
            if (!new File(dir, candidate).exists()) return candidate;
            i++;
            if (i > 999) return base + "_" + System.currentTimeMillis() + ext;
        }
    }

    public static String readUtf8(File file) throws Exception {
        byte[] buf = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeUtf8(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private VectorStore vectors() {
        if (vectorStore == null) vectorStore = new VectorStore(appContext);
        return vectorStore;
    }

    private void indexProjectFile(String projectName, String filename, String content) {
        if (TextUtils.isEmpty(projectName) || TextUtils.isEmpty(filename)) return;
        try {
            float[] vector = EmbeddingEngine.get(appContext)
                    .embed(filename + "\n" + (content == null ? "" : content));
            JSONObject payload = new JSONObject()
                    .put("filename", filename)
                    .put("project", projectName);
            vectors().upsert(OrionFileSearcher.vectorKey(projectName, filename), vector,
                    VectorStore.NS_ORION_FILES, payload.toString());
        } catch (Exception ignored) {
        }
    }

    private void deleteProjectFileIndex(String projectName, String filename) {
        if (TextUtils.isEmpty(projectName) || TextUtils.isEmpty(filename)) return;
        try {
            vectors().delete(OrionFileSearcher.vectorKey(projectName, filename));
        } catch (Exception ignored) {
        }
    }
}
