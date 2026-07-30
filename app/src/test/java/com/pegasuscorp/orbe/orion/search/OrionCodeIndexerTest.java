package com.pegasuscorp.orbe.orion.search;

import com.pegasuscorp.orbe.rag.VectorMath;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionCodeIndexerTest {

    private VectorStore vectors;

    @Before
    public void setUp() {
        vectors = new VectorStore(8, true);
        vectors.clear();
    }

    @After
    public void tearDown() {
        vectors.clear();
        vectors.close();
    }

    @Test
    public void indexFile_extractsBuildQuestionPrompt() throws Exception {
        String content = sampleBrainJava();
        OrionCodeIndexer indexer = new OrionCodeIndexer("demo", text -> unit(1, 0, 0, 0, 0, 0, 0, 0),
                vectors);
        indexer.indexProject(Collections.singletonList(
                new OrionCodeIndexer.JavaFile("BureauMarkdownBrain.java", content)));

        assertTrue(vectors.size(VectorStore.NS_ORION_CODE) > 0);
        Optional<CodeLocation> found = indexer.findCode("buildQuestionPrompt");
        assertTrue(found.isPresent());
        assertEquals("BureauMarkdownBrain.java", found.get().filename);
        assertEquals("buildQuestionPrompt", found.get().methodName);
        assertTrue(found.get().startLine > 0);
        assertTrue(found.get().snippet.contains("buildQuestionPrompt"));
    }

    @Test
    public void findCode_lowScoreReturnsEmpty() throws Exception {
        OrionCodeIndexer indexer = new OrionCodeIndexer("demo", text -> unit(1, 0, 0, 0, 0, 0, 0, 0),
                vectors);
        String content = "public class A { void foo(){} }";
        indexer.indexProject(Collections.singletonList(
                new OrionCodeIndexer.JavaFile("A.java", content)));

        OrionCodeIndexer other = new OrionCodeIndexer("demo", text -> unit(0, 1, 0, 0, 0, 0, 0, 0),
                vectors);
        assertFalse(other.findCode("particleCount").isPresent());
    }

    @Test
    public void reindexFile_replacesOldEntries() throws Exception {
        OrionCodeIndexer indexer = new OrionCodeIndexer("demo", text -> unit(1, 0, 0, 0, 0, 0, 0, 0),
                vectors);
        OrionCodeIndexer.JavaFile v1 = new OrionCodeIndexer.JavaFile("A.java",
                "public class A { void oldName(){} }");
        indexer.indexProject(Collections.singletonList(v1));
        int afterFirst = vectors.size(VectorStore.NS_ORION_CODE);

        OrionCodeIndexer.JavaFile v2 = new OrionCodeIndexer.JavaFile("A.java",
                "public class A { void newName(){} }");
        indexer.reindexFile(v2);
        int afterSecond = vectors.size(VectorStore.NS_ORION_CODE);

        assertTrue(afterFirst > 0);
        assertEquals(afterFirst, afterSecond);
        Optional<CodeLocation> found = indexer.findCode("newName");
        assertTrue(found.isPresent());
        assertEquals("newName", found.get().methodName);
    }

    @Test
    public void parseError_skipsWithoutCrash() {
        OrionCodeIndexer indexer = new OrionCodeIndexer("demo", text -> unit(1, 0, 0, 0, 0, 0, 0, 0),
                vectors);
        indexer.indexProject(Collections.singletonList(
                new OrionCodeIndexer.JavaFile("Broken.java", "public class { nope")));
        assertEquals(0, vectors.size(VectorStore.NS_ORION_CODE));
    }

    @Test
    public void namespace_isolatedFromOrionFiles() throws Exception {
        float[] v = unit(1, 0, 0, 0, 0, 0, 0, 0);
        vectors.upsert("ball.js", v, VectorStore.NS_ORION_FILES, "{}");
        JSONObject payload = new JSONObject()
                .put("project", "demo")
                .put("filename", "A.java")
                .put("method", "foo")
                .put("startLine", "1")
                .put("endLine", "2")
                .put("kind", "method")
                .put("snippet", "void foo(){}");
        vectors.upsert("orion-code:demo:A.java#foo", v, VectorStore.NS_ORION_CODE, payload.toString());

        assertEquals(1, vectors.search(v, 5, 0f, VectorStore.NS_ORION_FILES).size());
        assertEquals(1, vectors.search(v, 5, 0f, VectorStore.NS_ORION_CODE).size());
        assertEquals(0, vectors.search(v, 5, 0f, VectorStore.NS_MEMORY).size());
    }

    private static String sampleBrainJava() {
        StringBuilder sb = new StringBuilder();
        sb.append("public final class BureauMarkdownBrain {\n");
        for (int i = 0; i < 110; i++) {
            sb.append("  // line ").append(i + 1).append('\n');
        }
        sb.append("  public static String buildQuestionPrompt(String doc) {\n");
        sb.append("    return doc;\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static float[] unit(float... values) {
        return VectorMath.l2Normalize(values);
    }
}
