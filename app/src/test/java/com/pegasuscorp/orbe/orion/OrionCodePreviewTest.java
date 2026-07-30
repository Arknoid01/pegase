package com.pegasuscorp.orbe.orion;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionCodePreviewTest {

    @Test
    public void languageFromPath_commonExts() {
        assertEquals("java", OrionCodePreview.languageFromPath("src/Main.java"));
        assertEquals("kotlin", OrionCodePreview.languageFromPath("App.kt"));
        assertEquals("python", OrionCodePreview.languageFromPath("script.py"));
        assertEquals("json", OrionCodePreview.languageFromPath("a/b/config.json"));
        assertEquals("clike", OrionCodePreview.languageFromPath("README"));
    }

    @Test
    public void wrapAsFence_avoidsNestedFenceCollision() {
        String code = "x\n```\ny";
        String md = OrionCodePreview.wrapAsFence("java", code);
        assertTrue(md.startsWith("````java"));
        assertTrue(md.endsWith("````"));
    }

    @Test
    public void render_returnsNonEmptySpanned() {
        CharSequence out = OrionCodePreview.render(
                RuntimeEnvironment.getApplication(),
                "Hello.java",
                "class Hello {\n  void x() {}\n}");
        assertNotNull(out);
        assertTrue(out.length() > 0);
        assertTrue(out.toString().contains("Hello") || out.toString().contains("class"));
    }
}
