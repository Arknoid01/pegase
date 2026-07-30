package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BureauResearchStoreTest {

    @Test
    public void saveLoadDelete() {
        Context ctx = RuntimeEnvironment.getApplication();
        assertTrue(BureauResearchStore.save(ctx, "sport-timer-compose.md", "# Timer\n\nNotes."));
        assertEquals("# Timer\n\nNotes.", BureauResearchStore.load(ctx, "sport-timer-compose.md"));
        assertTrue(BureauResearchStore.list(ctx).contains("sport-timer-compose.md"));
        assertEquals("research/sport-timer-compose.md",
                BureauResearchStore.relativePath("sport-timer-compose.md"));
        assertTrue(BureauResearchStore.delete(ctx, "sport-timer-compose.md"));
        assertNull(BureauResearchStore.load(ctx, "sport-timer-compose.md"));
    }
}
