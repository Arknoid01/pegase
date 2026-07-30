package com.pegasuscorp.orbe.orion;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeneratedFilesTest {

    @Test
    public void guessExtension_fromFence() {
        assertEquals(".java", GeneratedFiles.guessExtension("```java\nclass A {}\n```"));
        assertEquals(".py", GeneratedFiles.guessExtension("```python\nprint(1)\n```"));
        assertEquals(".kt", GeneratedFiles.guessExtension("```kotlin\nfun main(){}\n```"));
        assertEquals(".sh", GeneratedFiles.guessExtension("```bash\necho hi\n```"));
    }

    @Test
    public void guessExtension_heuristicsAndDefault() {
        assertEquals(".java", GeneratedFiles.guessExtension(
                "package com.demo;\npublic class Foo {}"));
        assertEquals(".md", GeneratedFiles.guessExtension("Bonjour Orion"));
        assertTrue(GeneratedFiles.defaultOrionName("x").startsWith("orion_"));
        assertTrue(GeneratedFiles.defaultOrionName("```js\n1\n```").endsWith(".js"));
    }

    @Test
    public void extractArtifacts_namedFenceAndTitle() {
        String text = "Voici le script :\n\n### hello.py\n```python\nprint('hi')\n```\n";
        List<GeneratedFiles.Artifact> arts = GeneratedFiles.extractArtifacts(text);
        assertEquals(1, arts.size());
        assertEquals("hello.py", arts.get(0).filename);
        assertEquals("print('hi')", arts.get(0).content);

        List<GeneratedFiles.Artifact> colon = GeneratedFiles.extractArtifacts(
                "```java:Main.java\nclass Main {}\n```");
        assertEquals("Main.java", colon.get(0).filename);
        assertTrue(colon.get(0).content.contains("class Main"));
    }

    @Test
    public void extractArtifacts_bodyFileHint() {
        List<GeneratedFiles.Artifact> arts = GeneratedFiles.extractArtifacts(
                "```java\n// file: Demo.java\npublic class Demo {}\n```");
        assertEquals("Demo.java", arts.get(0).filename);
    }

    @Test
    public void extractArtifacts_sameNameKeepsLastOnly() {
        String text = ""
                + "### timer.js\n```js\n// base\n```\n\n"
                + "### timer.js\n```js\n// mid\n```\n\n"
                + "### other.js\n```js\n1\n```\n\n"
                + "### timer.js\n```js\n// final\n```\n";
        List<GeneratedFiles.Artifact> arts = GeneratedFiles.extractArtifacts(text);
        assertEquals(2, arts.size());
        assertEquals("timer.js", arts.get(0).filename);
        assertEquals("// final", arts.get(0).content);
        assertEquals("other.js", arts.get(1).filename);
    }

    @Test
    public void filenameFromHeader_variants() {
        assertEquals("Foo.java", GeneratedFiles.filenameFromHeader("java:Foo.java"));
        assertEquals("app.py", GeneratedFiles.filenameFromHeader("python app.py"));
        assertEquals("util.kt", GeneratedFiles.filenameFromHeader("src/util.kt"));
    }
}
