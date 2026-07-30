package com.pegasuscorp.orbe.f1companion;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class FavoriteTeamsStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        FavoriteTeamsStore.resetForTests(ctx);
        F1NewsStore.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        FavoriteTeamsStore.resetForTests(ctx);
        F1NewsStore.resetForTests(ctx);
    }

    @Test
    public void defaultsIncludeFerrariMcLarenMercedes() {
        assertTrue(FavoriteTeamsStore.isSelected(ctx, "ferrari"));
        assertTrue(FavoriteTeamsStore.isSelected(ctx, "mclaren"));
        assertTrue(FavoriteTeamsStore.isSelected(ctx, "mercedes"));
        assertFalse(FavoriteTeamsStore.isSelected(ctx, "haas"));
    }

    @Test
    public void toggleAndPersist() {
        FavoriteTeamsStore.toggle(ctx, "haas");
        assertTrue(FavoriteTeamsStore.isSelected(ctx, "haas"));
        FavoriteTeamsStore.toggle(ctx, "ferrari");
        assertFalse(FavoriteTeamsStore.isSelected(ctx, "ferrari"));
    }

    @Test
    public void newsStore_seedAndPending() {
        F1NewsStore.markSeenAll(ctx, Arrays.asList("a", "b"));
        assertTrue(F1NewsStore.isSeeded(ctx));
        assertTrue(F1NewsStore.hasSeen(ctx, "a"));

        F1NewsStore.setPending(ctx, "c", "Title", "Summary phrase", "https://x", "Ferrari");
        assertTrue(F1NewsStore.hasPending(ctx));
        assertEquals("Summary phrase", F1NewsStore.getPendingSummary(ctx));

        F1NewsStore.acknowledgePending(ctx);
        assertFalse(F1NewsStore.hasPending(ctx));
        assertTrue(F1NewsStore.hasSeen(ctx, "c"));
    }

    @Test
    public void setSelectedIds_filtersUnknown() {
        FavoriteTeamsStore.setSelectedIds(ctx, new LinkedHashSet<>(Arrays.asList("ferrari", "nope")));
        assertTrue(FavoriteTeamsStore.isSelected(ctx, "ferrari"));
        assertEquals(1, FavoriteTeamsStore.getSelectedIds(ctx).size());
    }
}
