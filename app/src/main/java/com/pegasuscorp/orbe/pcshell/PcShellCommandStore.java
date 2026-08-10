package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * CRUD commandes PC — {@code files/pc-shell/commands.json}.
 */
public final class PcShellCommandStore {

    private static PcShellCommandStore instance;
    private final File file;
    private final List<SavedCommand> commands = new ArrayList<>();

    private PcShellCommandStore(Context context) {
        file = PegaseFileSystem.get(context).pcShellCommandsJson();
        loadFromDisk();
    }

    public static synchronized PcShellCommandStore getInstance(Context context) {
        if (instance == null) {
            instance = new PcShellCommandStore(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    /** Réinitialise le store et supprime {@code commands.json} (tests unitaires). */
    public static synchronized void resetForTests(Context context) {
        instance = null;
        PegaseFileSystem.resetInstanceForTests();
        File f = PegaseFileSystem.get(context.getApplicationContext()).pcShellCommandsJson();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public synchronized List<SavedCommand> listAll() {
        return Collections.unmodifiableList(new ArrayList<>(commands));
    }

    public synchronized SavedCommand getById(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (SavedCommand c : commands) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    public synchronized SavedCommand getBySlug(String slug) {
        if (TextUtils.isEmpty(slug)) return null;
        String needle = slug.trim();
        for (SavedCommand c : commands) {
            if (needle.equals(c.slug)) return c;
        }
        return null;
    }

    public synchronized SavedCommand add(String label, String command) {
        return add(label, command, "");
    }

    public synchronized SavedCommand add(String label, String command, String slug) {
        return add(label, command, slug, null);
    }

    public synchronized SavedCommand add(String label, String command, String slug,
            Integer timeoutSeconds) {
        String lab = label != null ? label.trim() : "";
        String cmd = command != null ? command.trim() : "";
        String sl = slug != null ? slug.trim() : "";
        if (lab.isEmpty() || cmd.isEmpty()) return null;
        if (!sl.isEmpty() && getBySlug(sl) != null) return null;
        SavedCommand created = new SavedCommand(
                UUID.randomUUID().toString(),
                sl,
                lab,
                cmd,
                timeoutSeconds,
                System.currentTimeMillis(),
                null,
                null);
        commands.add(created);
        persist();
        return created;
    }

    /** Commande intégrée (slug stable) — créée si absente ; met à jour le timeout si absent. */
    public synchronized SavedCommand ensureBuiltin(String slug, String label, String command) {
        return ensureBuiltin(slug, label, command, null);
    }

    public synchronized SavedCommand ensureBuiltin(String slug, String label, String command,
            Integer timeoutSeconds) {
        SavedCommand existing = getBySlug(slug);
        if (existing != null) {
            if (timeoutSeconds != null && timeoutSeconds > 0 && existing.timeoutSeconds == null) {
                SavedCommand upgraded = existing.withTimeout(timeoutSeconds);
                replace(existing.id, upgraded);
                return upgraded;
            }
            return existing;
        }
        return add(label, command, slug, timeoutSeconds);
    }

    public synchronized SavedCommand update(String id, String label, String command) {
        SavedCommand existing = getById(id);
        if (existing == null) return null;
        String lab = label != null ? label.trim() : "";
        String cmd = command != null ? command.trim() : "";
        if (lab.isEmpty() || cmd.isEmpty()) return null;
        SavedCommand updated = new SavedCommand(
                existing.id, existing.slug, lab, cmd, existing.timeoutSeconds,
                existing.createdAtMs, existing.lastRunAtMs, existing.lastExitCode);
        replace(existing.id, updated);
        return updated;
    }

    private void replace(String id, SavedCommand updated) {
        for (int i = 0; i < commands.size(); i++) {
            if (id.equals(commands.get(i).id)) {
                commands.set(i, updated);
                persist();
                return;
            }
        }
    }

    public synchronized boolean delete(String id) {
        if (TextUtils.isEmpty(id)) return false;
        for (int i = 0; i < commands.size(); i++) {
            if (id.equals(commands.get(i).id)) {
                commands.remove(i);
                persist();
                return true;
            }
        }
        return false;
    }

    public synchronized void recordRun(String id, int exitCode) {
        SavedCommand existing = getById(id);
        if (existing == null) return;
        SavedCommand updated = existing.withRunResult(System.currentTimeMillis(), exitCode);
        replace(existing.id, updated);
    }

    private void loadFromDisk() {
        commands.clear();
        if (!file.isFile()) return;
        try {
            byte[] raw = readAll(file);
            if (raw.length == 0) return;
            JSONArray arr = new JSONArray(new String(raw, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                SavedCommand c = SavedCommand.fromJson(arr.optJSONObject(i));
                if (c != null) commands.add(c);
            }
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (SavedCommand c : commands) {
                arr.put(c.toJson());
            }
            writeAll(file, arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 2_000_000)];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
