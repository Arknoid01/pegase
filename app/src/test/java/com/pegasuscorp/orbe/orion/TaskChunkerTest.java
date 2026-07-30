package com.pegasuscorp.orbe.orion;

import android.content.Context;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.search.OrionFileSearcher;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class TaskChunkerTest {

    private Context ctx;
    private OrionProjectStore store;
    private VectorStore vectors;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        EmbeddingEngine.resetForTests();
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        File root = store.getProjectsRoot();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) deleteRecursive(f);
        }
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        vectors = new VectorStore(EmbeddingEngine.DIMENSIONS, true);
    }

    @After
    public void tearDown() {
        vectors.clear();
        vectors.close();
    }

    @Test
    public void parseChunkSpecs_validJson_returnsFourChunks() {
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais toute l'UI boucherie")
                .build();
        String json = "["
                + "{\"summary\":\"Header + navigation\",\"keyword\":\"header\",\"file\":\"header.xml\"},"
                + "{\"summary\":\"Liste des articles\",\"keyword\":\"articles\",\"file\":null},"
                + "{\"summary\":\"Formulaire de commande\",\"keyword\":\"orderForm\",\"file\":null},"
                + "{\"summary\":\"Styles globaux\",\"keyword\":null,\"file\":\"styles.css\"}"
                + "]";
        List<TaskChunker.ChunkSpec> specs = TaskChunker.parseChunkSpecs(json, parent);
        assertEquals(4, specs.size());
        assertEquals("Header + navigation", specs.get(0).summary);
        assertEquals("header", specs.get(0).keyword);
    }

    @Test
    public void parseChunkSpecs_invalidJson_fallbackSingleChunk() {
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais toute l'UI")
                .build();
        List<TaskChunker.ChunkSpec> specs = TaskChunker.parseChunkSpecs("pas du json", parent);
        assertEquals(1, specs.size());
        assertEquals("refais toute l'UI", specs.get(0).summary);
        assertNull(specs.get(0).keyword);
    }

    @Test
    public void parseChunkSpecs_extractsJsonFromMarkdownFence() {
        ResolvedTask parent = ResolvedTask.builder().rawInput("mission").build();
        String json = "Voici le plan :\n```json\n"
                + "[{\"summary\":\"Étape A\",\"keyword\":\"foo\",\"file\":null}]"
                + "\n```";
        List<TaskChunker.ChunkSpec> specs = TaskChunker.parseChunkSpecs(json, parent);
        assertEquals(1, specs.size());
        assertEquals("Étape A", specs.get(0).summary);
        assertEquals("foo", specs.get(0).keyword);
    }

    @Test
    public void parseChunkSpecs_capsAtSixChunks() {
        ResolvedTask parent = ResolvedTask.builder().rawInput("big").build();
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) json.append(',');
            json.append("{\"summary\":\"Step ").append(i).append("\"}");
        }
        json.append(']');
        assertEquals(6, TaskChunker.parseChunkSpecs(json.toString(), parent).size());
    }

    @Test
    public void chunk_enrichesWithFileSearcher_andForcesSimple() throws Exception {
        store.createProject("boucherie");
        store.setActive("boucherie");
        store.saveFile("header.xml",
                "<header id=\"nav\">Navigation</header>\n", false, false);

        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        TaskChunker chunker = new TaskChunker(searcher, store);
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais toute l'UI boucherie")
                .complexity(TaskComplexity.MASSIVE)
                .build();

        String plannerJson = "["
                + "{\"summary\":\"Header + navigation\",\"keyword\":\"nav\",\"file\":\"header.xml\"},"
                + "{\"summary\":\"Liste des articles\",\"keyword\":\"articles\",\"file\":null}"
                + "]";

        List<TaskChunk> chunks = chunker.chunk(ctx, parent, prompt -> plannerJson);
        assertEquals(2, chunks.size());
        for (TaskChunk c : chunks) {
            assertEquals(TaskComplexity.SIMPLE, c.task.complexity);
        }
        assertTrue(chunks.get(0).task.context.contains("header.xml")
                || !chunks.get(0).task.extractedKeyword.isEmpty());
    }

    @Test
    public void chunk_singleSpecFallback_yieldsOneChunk() throws Exception {
        store.createProject("demo");
        store.setActive("demo");
        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        TaskChunker chunker = new TaskChunker(searcher, store);
        ResolvedTask parent = ResolvedTask.builder()
                .rawInput("refais tout")
                .build();
        List<TaskChunk> chunks = chunker.chunk(ctx, parent, prompt -> "invalid");
        assertEquals(1, chunks.size());
        assertEquals(TaskComplexity.SIMPLE, chunks.get(0).task.complexity);
        assertEquals("refais tout", chunks.get(0).summary);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
