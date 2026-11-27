package com.pegasuscorp.orbe.iface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TypewriterPacerTest {

    private static final long FRAME = 16_000_000L;   // 16 ms

    private static int run(TypewriterPacer p, int frames, long[] clock) {
        for (int i = 0; i < frames; i++) { clock[0] += FRAME; p.tick(clock[0]); }
        return p.shown().length();
    }

    @Test public void blocEntier_ecritAcadenceReguliere() {
        TypewriterPacer p = new TypewriterPacer();
        long[] c = {1};
        p.markFinal("x".repeat(100));
        p.tick(c[0]);
        int apres1s = run(p, 62, c);               // ~1 s
        assertTrue("cadence de base ~40 c/s : " + apres1s, apres1s >= 35 && apres1s <= 45);
        run(p, 400, c);
        assertTrue(p.isDone());
    }

    @Test public void longBloc_accelereSansToutAfficher() {
        TypewriterPacer p = new TypewriterPacer();
        long[] c = {1};
        p.markFinal("y".repeat(1000));
        p.tick(c[0]);
        int apres1s = run(p, 62, c);
        assertTrue("accélère mais plafonne à 110 c/s : " + apres1s, apres1s > 45 && apres1s <= 115);
        assertFalse(p.isDone());
    }

    @Test public void morceauxIrreguliers_memeCadence() {
        TypewriterPacer p = new TypewriterPacer();
        long[] c = {1};
        p.setTarget("Bonjour");
        p.tick(c[0]);
        run(p, 30, c);
        p.setTarget("Bonjour, je vais très bien, merci de demander et toi ?");
        int avant = p.shown().length();
        int apres = run(p, 31, c);                 // ~0,5 s
        assertTrue(apres - avant >= 15 && apres - avant <= 23);
        assertFalse(p.isDone());                   // pas final tant que la réponse n'est pas finie
    }

    @Test public void texteReecrit_repartDuPrefixeCommun() {
        TypewriterPacer p = new TypewriterPacer();
        long[] c = {1};
        p.setTarget("abcdefghij");
        p.tick(c[0]);
        run(p, 30, c);
        p.setTarget("abcXYZ");
        assertEquals("abc", p.shown());
    }

    @Test public void emojiJamaisCoupe() {
        TypewriterPacer p = new TypewriterPacer();
        long[] c = {1};
        p.markFinal("ok 😂😂😂😂 fin");
        p.tick(c[0]);
        for (int i = 0; i < 100; i++) {
            c[0] += FRAME; p.tick(c[0]);
            String s = p.shown();
            if (!s.isEmpty()) assertFalse(Character.isHighSurrogate(s.charAt(s.length() - 1)));
        }
    }
}
