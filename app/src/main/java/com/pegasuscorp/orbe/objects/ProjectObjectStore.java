package com.pegasuscorp.orbe.objects;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.orion.OrionConfig;
import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.orion.OrionStateStore;
import com.pegasuscorp.orbe.orion.OrionStatus;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Fiches projet locales (objets) — continuité hors chat.
 * Orion synchronisé depuis {@link OrionStateStore} + fiches custom CRUD.
 */
public final class ProjectObjectStore {

    public static final String ID_ORION = "orion";
    public static final String KIND_CUSTOM = "custom";

    private static final String TAG = "ProjectObjectStore";
    private static final String FILE = "objects.json";

    private static ProjectObjectStore instance;
    private final File file;
    private JSONObject root;

    private ProjectObjectStore(Context ctx) {
        File dir = PegaseFileSystem.get(ctx).dir("objects");
        file = new File(dir, FILE);
        root = load();
    }

    public static synchronized ProjectObjectStore getInstance(Context ctx) {
        if (instance == null) {
            instance = new ProjectObjectStore(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    /** Snapshot Orion → fiche persistée (appelé depuis OrionStateStore). */
    public synchronized void syncOrion(Context ctx) {
        if (ctx == null) return;
        try {
            OrionStateStore st = OrionStateStore.get();
            st.attach(ctx);
            JSONObject o = optObject(ID_ORION);
            o.put("kind", "orion");
            o.put("label", "Orion / RunPod");
            OrionStatus status = st.getStatus();
            o.put("status", status != null ? status.name() : OrionStatus.OFFLINE.name());
            String gpu = st.getGpuLabel();
            if (!TextUtils.isEmpty(gpu)) o.put("gpuLabel", gpu);
            String err = st.getLastError();
            if (!TextUtils.isEmpty(err)) {
                o.put("lastError", err);
                o.put("lastFailureAtMs", System.currentTimeMillis());
            } else if (status == OrionStatus.READY) {
                o.put("lastError", "");
                o.put("lastSuccessAtMs", System.currentTimeMillis());
            }
            try {
                OrionConfig cfg = OrionConfig.load(ctx);
                if (cfg != null) {
                    if (!TextUtils.isEmpty(cfg.networkVolumeId)) {
                        o.put("volumeId", cfg.networkVolumeId);
                    }
                    if (!TextUtils.isEmpty(cfg.dataCenterId)) {
                        o.put("dataCenterId", cfg.dataCenterId);
                    }
                }
            } catch (Exception ignored) {}
            try {
                String active = OrionProjectStore.get(ctx).getActiveProject();
                o.put("activeProject", active != null ? active : "");
            } catch (Exception e) {
                o.put("activeProject", "");
            }
            o.put("updatedAtMs", System.currentTimeMillis());
            putObject(ID_ORION, o);
            save();
        } catch (Exception e) {
            Log.w(TAG, "syncOrion", e);
        }
    }

    public synchronized JSONObject getOrion() {
        return optObject(ID_ORION);
    }

    public synchronized boolean orionHasRecentFailure(long maxAgeMs) {
        JSONObject o = getOrion();
        String err = o.optString("lastError", "");
        if (TextUtils.isEmpty(err)) return false;
        long at = o.optLong("lastFailureAtMs", 0L);
        if (at <= 0L) return true;
        return (System.currentTimeMillis() - at) <= maxAgeMs;
    }

    /**
     * Crée ou met à jour une fiche custom.
     * @return id stable, ou null si label vide
     */
    public synchronized String upsertCustom(String idOrNull, String label, String notes,
            String status) {
        if (TextUtils.isEmpty(label)) return null;
        try {
            String id = TextUtils.isEmpty(idOrNull)
                    ? slugId(label.trim())
                    : idOrNull.trim().toLowerCase(Locale.ROOT);
            if (ID_ORION.equals(id)) return null;
            JSONObject o = optObject(id);
            o.put("kind", KIND_CUSTOM);
            o.put("label", label.trim());
            if (notes != null) o.put("notes", notes.trim());
            if (status != null) o.put("status", status.trim());
            else if (!o.has("status")) o.put("status", "actif");
            o.put("updatedAtMs", System.currentTimeMillis());
            if (!o.has("createdAtMs")) o.put("createdAtMs", System.currentTimeMillis());
            putObject(id, o);
            save();
            return id;
        } catch (Exception e) {
            Log.w(TAG, "upsertCustom", e);
            return null;
        }
    }

    public synchronized boolean remove(String id) {
        if (TextUtils.isEmpty(id) || ID_ORION.equals(id)) return false;
        try {
            JSONObject objects = root.optJSONObject("objects");
            if (objects == null || !objects.has(id)) return false;
            objects.remove(id);
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized JSONObject get(String id) {
        if (TextUtils.isEmpty(id)) return new JSONObject();
        return optObject(id);
    }

    /** Fiches custom uniquement (hors Orion). */
    public synchronized List<JSONObject> listCustom() {
        List<JSONObject> out = new ArrayList<>();
        JSONObject objects = root.optJSONObject("objects");
        if (objects == null) return out;
        Iterator<String> keys = objects.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            if (ID_ORION.equals(id)) continue;
            JSONObject o = objects.optJSONObject(id);
            if (o == null) continue;
            if ("orion".equals(o.optString("kind", ""))) continue;
            try {
                JSONObject copy = new JSONObject(o.toString());
                copy.put("id", id);
                out.add(copy);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public synchronized String promptBlock() {
        StringBuilder sb = new StringBuilder();
        JSONObject orion = getOrion();
        List<JSONObject> custom = listCustom();
        if (orion.length() == 0 && custom.isEmpty()) return "";
        sb.append("Fiches projet (objets locaux) :\n");
        if (orion.length() > 0) {
            sb.append("- Orion / RunPod : statut ")
                    .append(orion.optString("status", "?"))
                    .append(", projet ")
                    .append(emptyDash(orion.optString("activeProject", "")))
                    .append(", GPU ")
                    .append(emptyDash(orion.optString("gpuLabel", "")))
                    .append(", volume ")
                    .append(emptyDash(orion.optString("volumeId", "")));
            String err = orion.optString("lastError", "");
            if (!TextUtils.isEmpty(err)) {
                sb.append(". Dernier échec : ").append(err.trim());
            }
            sb.append(".\n");
        }
        for (JSONObject o : custom) {
            sb.append("- ")
                    .append(o.optString("label", o.optString("id", "?")))
                    .append(" : statut ")
                    .append(emptyDash(o.optString("status", "")));
            String notes = o.optString("notes", "");
            if (!TextUtils.isEmpty(notes)) {
                sb.append(" — ").append(notes.trim());
            }
            sb.append(".\n");
        }
        sb.append("Si on parle d'un de ces projets, appuie-toi sur la fiche "
                + "(ne pas inventer un autre état).\n");
        return sb.toString();
    }

    /** Tests / UI. */
    public synchronized void putOrionFailureForTests(String error) {
        try {
            JSONObject o = optObject(ID_ORION);
            o.put("kind", "orion");
            o.put("label", "Orion / RunPod");
            o.put("status", OrionStatus.OFFLINE.name());
            o.put("lastError", error);
            o.put("lastFailureAtMs", System.currentTimeMillis());
            o.put("updatedAtMs", System.currentTimeMillis());
            putObject(ID_ORION, o);
            save();
        } catch (Exception ignored) {}
    }

    private static String slugId(String label) {
        String s = label.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        if (TextUtils.isEmpty(s)) s = "projet";
        if (s.length() > 40) s = s.substring(0, 40);
        return s + "_" + Long.toString(System.currentTimeMillis() % 100000, 36);
    }

    private JSONObject optObject(String id) {
        try {
            JSONObject objects = root.optJSONObject("objects");
            if (objects == null) {
                objects = new JSONObject();
                root.put("objects", objects);
            }
            JSONObject o = objects.optJSONObject(id);
            return o != null ? o : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void putObject(String id, JSONObject o) throws Exception {
        JSONObject objects = root.optJSONObject("objects");
        if (objects == null) {
            objects = new JSONObject();
            root.put("objects", objects);
        }
        objects.put(id, o);
    }

    private JSONObject load() {
        if (!file.isFile()) return new JSONObject();
        try {
            byte[] raw = readAll(file);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void save() {
        try {
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
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
            return buf;
        }
    }

    private static String emptyDash(String s) {
        return TextUtils.isEmpty(s) ? "—" : s;
    }
}
