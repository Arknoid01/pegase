package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Atlas local d'entités — coexiste avec {@link MemoryRepository}, sans conversion forcée.
 */
public final class EntityStore {

    private static EntityStore instance;

    private final File atlasFile;
    private final List<Entity> entities = new ArrayList<>();

    private EntityStore(Context context) {
        File memoryDir = new File(context.getApplicationContext().getFilesDir(), "memory");
        if (!memoryDir.exists()) memoryDir.mkdirs();
        atlasFile = new File(memoryDir, "entities.json");
        load();
        seedDefaultsIfEmpty();
    }

    public static synchronized EntityStore getInstance(Context context) {
        if (instance == null) instance = new EntityStore(context);
        return instance;
    }

    public List<Entity> getAll() {
        return Collections.unmodifiableList(entities);
    }

    public Entity findById(String id) {
        if (id == null) return null;
        for (Entity e : entities) {
            if (id.equals(e.id)) return e;
        }
        return null;
    }

    public void upsert(Entity entity) {
        if (entity == null || entity.id.isEmpty()) return;
        for (int i = 0; i < entities.size(); i++) {
            if (entity.id.equals(entities.get(i).id)) {
                entities.set(i, entity);
                save();
                return;
            }
        }
        entities.add(entity);
        save();
    }

    public boolean remove(String id) {
        if (id == null || id.isEmpty()) return false;
        boolean removed = entities.removeIf(e -> id.equals(e.id));
        if (removed) save();
        return removed;
    }

    public List<Entity> listByType(String type) {
        if (type == null || type.isEmpty()) return new ArrayList<>(entities);
        List<Entity> out = new ArrayList<>();
        for (Entity e : entities) {
            if (type.equals(e.type)) out.add(e);
        }
        return out;
    }

    public static String suggestId(String type, String name) {
        if (name == null) name = "";
        String slug = name.toLowerCase(java.util.Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('ù', 'u').replace('ô', 'o')
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        if (slug.isEmpty()) slug = "nouveau";
        String prefix = type == null || type.isEmpty() ? "entity" : type;
        return prefix + "_" + slug;
    }

    private void seedDefaultsIfEmpty() {
        if (!entities.isEmpty()) return;
        try {
            entities.add(Entity.project("project_pegase", "Pégase",
                    Arrays.asList("Orbe", "mon assistant", "le launcher", "pegase"),
                    "en développement",
                    Arrays.asList(
                            "Application Android",
                            "Assistant vocal intégré au launcher",
                            "Possède une mémoire et une boîte à outils")));
            entities.add(Entity.project("project_fableris", "Fableris",
                    Arrays.asList("le city builder", "mon jeu", "city builder"),
                    "en développement",
                    Arrays.asList("City builder nommé Fableris")));
            entities.add(Entity.device("device_nothing_phone", "Nothing Phone 1",
                    Arrays.asList("mon téléphone", "nothing phone", "mon tel"),
                    Arrays.asList("Android 15", "Pégase y est installé")));
            save();
        } catch (Exception ignored) {}
    }

    private void load() {
        entities.clear();
        if (!atlasFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(atlasFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) entities.add(Entity.fromJson(o));
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (Entity e : entities) {
            try {
                arr.put(e.toJson());
            } catch (Exception ignored) {}
        }
        try (FileOutputStream out = new FileOutputStream(atlasFile)) {
            out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }
}
