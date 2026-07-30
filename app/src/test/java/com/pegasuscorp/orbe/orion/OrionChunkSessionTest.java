package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class OrionChunkSessionTest {

    private static TaskChunk chunk(int index, int total, String summary) {
        ResolvedTask task = ResolvedTask.builder()
                .mission(summary)
                .rawInput(summary)
                .build();
        return new TaskChunk(index, total, task, summary);
    }

    @Test
    public void current_startsAtFirstChunk() {
        OrionChunkSession session = new OrionChunkSession(Arrays.asList(
                chunk(1, 3, "Header"),
                chunk(2, 3, "Liste"),
                chunk(3, 3, "Styles")));
        assertEquals(1, session.current().index);
        assertEquals("Header", session.current().summary);
        assertFalse(session.isComplete());
    }

    @Test
    public void hasNext_andNext_advanceSession() {
        OrionChunkSession session = new OrionChunkSession(Arrays.asList(
                chunk(1, 2, "A"),
                chunk(2, 2, "B")));
        assertTrue(session.hasNext());
        TaskChunk second = session.next();
        assertEquals(2, second.index);
        assertEquals("B", second.summary);
        assertFalse(session.hasNext());
    }

    @Test
    public void progressPercent_reflectsCurrentStep() {
        OrionChunkSession session = new OrionChunkSession(Arrays.asList(
                chunk(1, 4, "A"),
                chunk(2, 4, "B"),
                chunk(3, 4, "C"),
                chunk(4, 4, "D")));
        assertEquals(25, session.progressPercent());
        session.next();
        assertEquals(50, session.progressPercent());
    }

    @Test
    public void chunk_toProgressLabel_formatsStep() {
        TaskChunk c = chunk(2, 4, "Liste des articles");
        assertEquals("Étape 2/4 — Liste des articles", c.toProgressLabel());
    }
}
