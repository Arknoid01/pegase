package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MemoryLinkerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    @Test
    public void autoLink_fablerisContent_linksProjectEntity() {
        MemoryEntry entry = new MemoryEntry(
                "projects",
                "On avance sur Fableris, le city builder",
                0.85,
                "2026-07-30");
        MemoryLinker.autoLink(ctx, entry);
        assertTrue(entry.entityIds.contains("project_fableris"));
    }

    @Test
    public void seedEntityIds_fromResolution() {
        Entity fableris = EntityStore.getInstance(ctx).findById("project_fableris");
        assertNotNull(fableris);
        EntityResolver.Resolution resolution = EntityResolver.resolve(ctx, "Et Fableris ?");
        List<String> ids = MemoryLinker.seedEntityIds(resolution, 2);
        assertFalse(ids.isEmpty());
        assertEquals("project_fableris", ids.get(0));
    }
}
