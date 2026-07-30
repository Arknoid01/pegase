package com.pegasuscorp.orbe.orion;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionProjectStoreTest {

    private Context ctx;
    private OrionProjectStore store;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        // Clean projects root
        File root = store.getProjectsRoot();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) deleteRecursive(f);
        }
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Test
    public void createProject_makesDirectoryAndActivates() {
        String name = store.createProject("Balle HTML");
        assertEquals("balle-html", name);
        assertTrue(store.hasActiveProject());
        assertEquals("balle-html", store.getActiveProject());
        assertTrue(store.getProjectDir("balle-html").isDirectory());
        assertTrue(store.listProjects().contains("balle-html"));
    }

    @Test
    public void saveFile_newThenConflictNeedsConfirm() {
        store.createProject("demo");
        OrionProjectStore.SaveResult first = store.saveFile("index.html", "<html/>", false, false);
        assertEquals(OrionProjectStore.SaveOutcome.CREATED, first.outcome);

        OrionProjectStore.SaveResult conflict =
                store.saveFile("index.html", "<html>2</html>", false, false);
        assertEquals(OrionProjectStore.SaveOutcome.NEEDS_CONFIRM, conflict.outcome);

        OrionProjectStore.SaveResult replaced =
                store.replaceFile("index.html", "<html>2</html>");
        assertEquals(OrionProjectStore.SaveOutcome.REPLACED, replaced.outcome);
        assertTrue(store.readFile("index.html").contains("2"));
    }

    @Test
    public void saveAsNew_keepsBoth() {
        store.createProject("demo2");
        store.saveFile("a.js", "1", false, false);
        OrionProjectStore.SaveResult asNew = store.saveAsNew("a.js", "2");
        assertEquals(OrionProjectStore.SaveOutcome.CREATED, asNew.outcome);
        assertEquals("a_2.js", asNew.path);
        assertEquals(2, store.getProjectFiles().size());
    }

    @Test
    public void switchProject_listsFilesSeparately() {
        store.createProject("alpha");
        store.saveFile("a.txt", "A", false, false);
        store.createProject("beta");
        store.saveFile("b.txt", "B", false, false);
        assertEquals("beta", store.getActiveProject());
        assertEquals(1, store.getProjectFiles().size());
        assertEquals("b.txt", store.getProjectFiles().get(0).name);

        store.setActive("alpha");
        assertEquals("a.txt", store.getProjectFiles().get(0).name);
    }

    @Test
    public void toOrionFiles_forPush() {
        store.createProject("push-me");
        store.saveFile("x.md", "# hi", false, false);
        List<OrionFileSession.OrionFile> files = store.toOrionFiles();
        assertEquals(1, files.size());
        assertEquals("x.md", files.get(0).path);
        assertEquals(OrionFileSession.FileStatus.VALIDATED, files.get(0).status);
    }

    @Test
    public void toOrionFiles_filterSelection() {
        store.createProject("sel");
        store.saveFile("a.js", "1", false, false);
        store.saveFile("b.js", "2", false, false);
        store.saveFile("c.js", "3", false, false);
        List<OrionFileSession.OrionFile> two = store.toOrionFiles(
                java.util.Arrays.asList("a.js", "c.js"));
        assertEquals(2, two.size());
        assertEquals("a.js", two.get(0).path);
        assertEquals("c.js", two.get(1).path);
    }

    @Test
    public void renameAndPushSnapshot_diff() {
        store.createProject("diff-me");
        store.saveFile("ball.js", "v1", false, false);
        store.recordPushSnapshot(null);
        assertFalse(store.hasChangedSincePush("ball.js"));
        store.replaceFile("ball.js", "v2");
        assertTrue(store.hasChangedSincePush("ball.js"));
        assertEquals("v1", store.getLastPushedContent("ball.js"));

        String renamed = store.renameFile("ball.js", "sphere.js");
        assertEquals("sphere.js", renamed);
        assertTrue(store.fileExists("sphere.js"));
        assertFalse(store.fileExists("ball.js"));
        assertEquals("v1", store.getLastPushedContent("sphere.js"));
    }

    @Test
    public void deleteProject_removesDirAndClearsActive() {
        store.createProject("to-go");
        store.saveFile("x.txt", "bye", false, false);
        assertEquals("to-go", store.getActiveProject());
        assertTrue(store.deleteProject("to-go"));
        assertFalse(store.listProjects().contains("to-go"));
        assertNotEquals("to-go", store.getActiveProject());
        assertFalse(new File(store.getProjectsRoot(), "to-go").exists());
    }
}