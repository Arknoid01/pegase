package com.pegasuscorp.orbe.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Valide la migration id/updatedAtMs sur une copie offline (pas la prod live).
 * Fixture optionnelle : {@code -Dpegase.migration.fixture=/chemin/pre-migration.json}
 */
@RunWith(RobolectricTestRunner.class)
public class MemoryMigrationTest {

    @Test
    public void migration_synthetic_noDuplicatesOrLoss() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(entryJson("project", "Pégase assistant vocal", 0.9, "2026-01-01"));
        arr.put(entryJson("prefs", "Aime le café le matin", 0.7, "2026-02-01"));
        arr.put(entryJson("user", "Sarah a un cheval nommé Sable", 0.85, "2026-03-01"));

        List<Migrated> first = migrateAll(arr);
        assertEquals(3, first.size());
        assertUniqueIds(first);
        assertStableRemigration(arr, first);

        for (Migrated m : first) {
            assertFalse(m.entry.id.isEmpty());
            assertTrue(m.entry.updatedAtMs > 0);
        }
    }

    @Test
    public void migration_realCopyFromFixture_ifPresent() throws Exception {
        String path = System.getProperty("pegase.migration.fixture", "").trim();
        if (path.isEmpty()) {
            path = System.getenv("PEGASE_MIGRATION_FIXTURE");
        }
        if (path == null || path.isEmpty()) return;

        File file = new File(path);
        if (!file.isFile()) {
            fail("Fixture introuvable : " + path);
        }

        JSONArray arr = readJsonArray(file);
        int before = arr.length();
        assertTrue("Fixture vide", before > 0);

        List<Migrated> first = migrateAll(arr);
        assertEquals("Perte d'entrées à la migration", before, first.size());
        assertUniqueIds(first);
        assertStableRemigration(arr, first);
        assertUniqueMemoryKeys(first);

        String referencePath = System.getProperty("pegase.migration.reference", "").trim();
        if (referencePath.isEmpty()) {
            referencePath = System.getenv("PEGASE_MIGRATION_REFERENCE");
        }
        if (referencePath != null && !referencePath.isEmpty()) {
            assertIdsMatchReference(first, new File(referencePath));
        }
    }

    private static void assertIdsMatchReference(List<Migrated> migrated, File referenceFile)
            throws Exception {
        JSONArray ref = readJsonArray(referenceFile);
        assertEquals("Taille référence différente", migrated.size(), ref.length());
        for (int i = 0; i < migrated.size(); i++) {
            JSONObject r = ref.getJSONObject(i);
            String refId = r.optString("id", "");
            if (refId.isEmpty()) continue;
            assertEquals("id différent idx=" + i + " content="
                            + migrated.get(i).content.substring(0, Math.min(40, migrated.get(i).content.length())),
                    refId, migrated.get(i).entry.id);
        }
    }

    private static void assertUniqueMemoryKeys(List<Migrated> migrated) {
        Set<String> keys = new HashSet<>();
        for (Migrated m : migrated) {
            String key = m.entry.memoryKey();
            assertFalse("memoryKey dupliquée : " + key, keys.contains(key));
            keys.add(key);
        }
    }

    private static void assertUniqueIds(List<Migrated> migrated) {
        Set<String> ids = new HashSet<>();
        for (Migrated m : migrated) {
            assertFalse("id dupliqué : " + m.entry.id, ids.contains(m.entry.id));
            ids.add(m.entry.id);
        }
    }

    private static void assertStableRemigration(JSONArray arr, List<Migrated> first)
            throws Exception {
        List<Migrated> second = migrateAll(arr);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            Migrated a = first.get(i);
            Migrated b = second.get(i);
            assertEquals("id instable idx=" + i, a.entry.id, b.entry.id);
            assertEquals("category altérée idx=" + i, a.category, b.category);
            assertEquals("content altéré idx=" + i, a.content, b.content);
        }
    }

    private static List<Migrated> migrateAll(JSONArray arr) throws Exception {
        List<Migrated> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String category = o.optString("category", "");
            String content = o.optString("content", "");
            MemoryEntry entry = MemoryEntry.fromJson(o);
            out.add(new Migrated(category, content, entry));
        }
        return out;
    }

    private static JSONObject entryJson(String category, String content,
            double importance, String createdAt) throws Exception {
        return new JSONObject()
                .put("category", category)
                .put("content", content)
                .put("importance", importance)
                .put("createdAt", createdAt)
                .put("source", MemoryEntry.SOURCE_USER)
                .put("lastUsedAt", 1_700_000_000_000L)
                .put("importanceAtLastUse", importance);
    }

    private static JSONArray readJsonArray(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return new JSONArray(sb.toString());
    }

    private static final class Migrated {
        final String category;
        final String content;
        final MemoryEntry entry;

        Migrated(String category, String content, MemoryEntry entry) {
            this.category = category;
            this.content = content;
            this.entry = entry;
        }
    }
}
