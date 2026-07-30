package com.pegasuscorp.orbe.memory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MemoryRepositoryLinkedTest {

    private MemoryRepository repo;

    @Before
    public void setUp() {
        MemoryRepository.resetInstanceForTests();
        MemoryRepository.setAutoMigrateForTests(false);
        repo = MemoryRepository.getInstance(
                org.robolectric.RuntimeEnvironment.getApplication());
        while (!repo.getAllPermanentMemories().isEmpty()) {
            repo.removePermanentMemoryAt(0);
        }
    }

    @Test
    public void getLinkedMemories_findsSharedEntity() {
        MemoryEntry a = new MemoryEntry("projects", "Alpha Fableris", 0.9, "2026-01-01");
        a.entityIds.add("project_fableris");
        MemoryEntry b = new MemoryEntry("projects", "Beta Fableris budget", 0.8, "2026-01-01");
        b.entityIds.add("project_fableris");
        repo.addPermanentMemory(a);
        repo.addPermanentMemory(b);

        assertEquals(1, repo.getLinkedMemories(a).size());
        assertEquals(b.content, repo.getLinkedMemories(a).get(0).content);
    }

    @Test
    public void getLinkedMemories_findsRelatedKey() {
        MemoryEntry a = new MemoryEntry("a", "one", 0.5, "2026-01-01");
        MemoryEntry b = new MemoryEntry("b", "two", 0.5, "2026-01-01");
        MemoryGraph.linkRelated(a, b);
        repo.addPermanentMemory(a);
        repo.addPermanentMemory(b);
        assertEquals(1, repo.getLinkedMemories(a).size());
    }
}
