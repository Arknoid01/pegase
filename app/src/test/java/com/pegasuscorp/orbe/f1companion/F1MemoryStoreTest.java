package com.pegasuscorp.orbe.f1companion;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class F1MemoryStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        F1MemoryStore.clearAll(ctx);
    }

    @After
    public void tearDown() {
        F1MemoryStore.clearAll(ctx);
    }

    @Test
    public void addTakeAndPrediction_persist() {
        WeekendSnapshot snap = new WeekendSnapshot();
        snap.event = "Grand Prix de Test";
        snap.sessionKey = 42;

        F1MemoryStore.addTake(ctx, "Hamilton n'aurait pas dû être pénalisé", snap);
        F1MemoryStore.addPrediction(ctx, "Norris gagne", snap);
        F1MemoryStore.addNote(ctx, "Je préfère le style Leclerc");

        F1FanMemory mem = F1MemoryStore.load(ctx);
        assertEquals(1, mem.takes.size());
        assertEquals(1, mem.predictions.size());
        assertEquals(1, mem.notes.size());
        assertFalse(mem.predictions.get(0).resolved);
        assertTrue(mem.toMarkdown(null).contains("Norris"));
    }

    @Test
    public void resolveAgainstRace_scoresWinner() {
        WeekendSnapshot snap = new WeekendSnapshot();
        snap.event = "Grand Prix de Belgique";
        snap.sessionKey = 99;
        WeekendSnapshot.ResultRow w = new WeekendSnapshot.ResultRow();
        w.position = 1;
        w.driver = "Max Verstappen";
        w.team = "Red Bull Racing";
        snap.results.add(w);
        WeekendSnapshot.ResultRow p2 = new WeekendSnapshot.ResultRow();
        p2.position = 2;
        p2.driver = "Lando Norris";
        p2.team = "McLaren";
        snap.results.add(p2);

        F1MemoryStore.addPrediction(ctx, "Verstappen gagne Spa", snap);
        F1MemoryStore.addPrediction(ctx, "Norris gagne Spa", snap);

        int n = F1MemoryStore.resolveAgainstRace(ctx, snap);
        assertEquals(2, n);

        F1FanMemory mem = F1MemoryStore.load(ctx);
        assertTrue(mem.predictions.get(0).resolved);
        assertEquals(Boolean.TRUE, mem.predictions.get(0).correct);
        assertEquals(Boolean.FALSE, mem.predictions.get(1).correct);
    }

    @Test
    public void scorePrediction_negation() {
        assertEquals(Boolean.FALSE, F1MemoryStore.scorePrediction(
                "pas verstappen", "Max Verstappen", "Red Bull", "Max, Lando, Charles"));
        assertEquals(Boolean.TRUE, F1MemoryStore.scorePrediction(
                "victoire Ferrari", "Charles Leclerc", "Ferrari", "Charles, Max, Lando"));
    }
}
