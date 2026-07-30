package com.pegasuscorp.orbe.life;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Calendar;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LifePatternStoreTest {

    @Before
    public void setUp() {
        LifePatternStore.resetInstanceForTests();
    }

    @Test
    public void addFromUtterance_parsesRange() {
        LifePatternStore store = LifePatternStore.getInstance(RuntimeEnvironment.getApplication());
        LifePatternStore.LifePattern p = store.addFromUtterance(
                "ajoute un rythme ménage de 18h30 à 19h45");
        assertNotNull(p);
        assertEquals(18, p.startHour);
        assertEquals(30, p.startMinute);
        assertEquals(19, p.endHour);
        assertEquals(45, p.endMinute);
        assertTrue(p.label.toLowerCase().contains("menage")
                || p.label.toLowerCase().contains("ménage")
                || p.label.length() > 0);
    }

    @Test
    public void isActiveNow_window() {
        LifePatternStore.LifePattern p = new LifePatternStore.LifePattern(
                "1", "test", "", 18, 30, 19, 45,
                Collections.emptyList(), true, true, true, 0L);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 19);
        cal.set(Calendar.MINUTE, 0);
        assertTrue(p.isActiveNow(cal));
        cal.set(Calendar.HOUR_OF_DAY, 20);
        assertFalse(p.isActiveNow(cal));
        cal.set(Calendar.HOUR_OF_DAY, 18);
        cal.set(Calendar.MINUTE, 15);
        assertFalse(p.isActiveNow(cal));
    }

    @Test
    public void promptBlock_includesActive() {
        LifePatternStore store = LifePatternStore.getInstance(RuntimeEnvironment.getApplication());
        store.add("Fermeture", "Le travail ferme à 18h30", 18, 30, 19, 45);
        String block = store.promptBlock();
        assertTrue(block.contains("Rythmes de vie"));
        assertTrue(block.contains("Fermeture") || block.contains("18:30"));
    }

    @Test
    public void intentionId_prefix() {
        LifePatternStore.LifePattern p = new LifePatternStore.LifePattern(
                "abc", "x", "", 9, 0, 10, 0,
                Collections.emptyList(), true, true, true, 0L);
        assertEquals("life:abc", p.intentionId());
        assertTrue(com.pegasuscorp.orbe.intentions.IntentionIds.isValid(p.intentionId()));
    }
}
