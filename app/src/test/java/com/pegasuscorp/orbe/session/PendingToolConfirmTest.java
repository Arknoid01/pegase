package com.pegasuscorp.orbe.session;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class PendingToolConfirmTest {

    @After
    public void tearDown() {
        PendingToolConfirm.clear();
    }

    @Test
    public void confirm_yesNo() {
        final boolean[] ok = {false};
        final boolean[] no = {false};
        PendingToolConfirm.set("Lancer ?", () -> ok[0] = true, () -> no[0] = true);
        assertTrue(PendingToolConfirm.hasPending());
        assertFalse(PendingToolConfirm.tryResolve("peut-être"));
        assertTrue(PendingToolConfirm.hasPending());
        assertTrue(PendingToolConfirm.tryResolve("oui"));
        assertTrue(ok[0]);
        assertFalse(PendingToolConfirm.hasPending());
    }

    @Test
    public void choice_byNumber() {
        final int[] picked = {-1};
        PendingToolConfirm.setChoice("Choisir\n1. GPU A\n2. GPU B",
                new String[]{"1. GPU A", "2. GPU B"},
                i -> picked[0] = i,
                () -> {});
        assertTrue(PendingToolConfirm.isChoice());
        assertTrue(PendingToolConfirm.tryResolve("2"));
        assertEquals(1, picked[0]);
    }

    @Test
    public void choice_cancel() {
        final boolean[] cancelled = {false};
        PendingToolConfirm.setChoice("Choisir", new String[]{"A", "B"},
                i -> {},
                () -> cancelled[0] = true);
        assertTrue(PendingToolConfirm.tryResolve("annule"));
        assertTrue(cancelled[0]);
    }
}
