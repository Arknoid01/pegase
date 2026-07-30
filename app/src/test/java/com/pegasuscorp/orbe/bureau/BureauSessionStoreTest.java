package com.pegasuscorp.orbe.bureau;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BureauSessionStoreTest {

    @Before
    public void setUp() {
        File dir = BureauSessionStore.dir(RuntimeEnvironment.getApplication());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".md") || f.getName().endsWith(".tmp")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    @Test
    public void loadToday_createsHeaderWhenEmpty() {
        String content = BureauSessionStore.loadToday(RuntimeEnvironment.getApplication());
        assertNotNull(content);
        assertTrue(content.startsWith("# Bureau"));
        assertTrue(content.contains("## Objectifs"));
        assertTrue(content.contains("## Tâches"));
    }

    @Test
    public void saveAndLoad_roundTrip() {
        var ctx = RuntimeEnvironment.getApplication();
        String body = "# Test\n\n- item\n";
        String name = BureauSessionStore.todayFilename();
        BureauSessionStore.saveSync(ctx, name, body);
        assertTrue("fichier créé", new File(BureauSessionStore.dir(ctx), name).isFile());
        String loaded = BureauSessionStore.loadFile(ctx, name);
        assertEquals(body, loaded);
    }
}
