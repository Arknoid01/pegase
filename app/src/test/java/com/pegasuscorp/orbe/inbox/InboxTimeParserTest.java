package com.pegasuscorp.orbe.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

public class InboxTimeParserTest {

    /** Samedi 17 octobre 2026, 21:30. */
    private static long now() {
        Calendar c = Calendar.getInstance(Locale.FRENCH);
        c.set(2026, Calendar.OCTOBER, 17, 21, 30, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static Calendar at(long ms) {
        Calendar c = Calendar.getInstance(Locale.FRENCH);
        c.setTimeInMillis(ms);
        return c;
    }

    @Test
    public void heureDuJour() {
        Calendar c = at(InboxTimeParser.parse("20:42", now()));
        assertEquals(17, c.get(Calendar.DAY_OF_MONTH));
        assertEquals(20, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(42, c.get(Calendar.MINUTE));
    }

    @Test
    public void hierEtJourDeSemaine() {
        assertEquals(16, at(InboxTimeParser.parse("Hier", now())).get(Calendar.DAY_OF_MONTH));
        // Jeudi avant samedi 17 → 15.
        assertEquals(15, at(InboxTimeParser.parse("jeudi", now())).get(Calendar.DAY_OF_MONTH));
        // Le même jour de semaine = il y a une semaine.
        assertEquals(10, at(InboxTimeParser.parse("samedi", now())).get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void dateComplete() {
        Calendar c = at(InboxTimeParser.parse("05/10/2026", now()));
        assertEquals(5, c.get(Calendar.DAY_OF_MONTH));
        assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH));
    }

    @Test
    public void relatifMessenger() {
        long n = now();
        assertEquals(n - 2 * 3_600_000L, InboxTimeParser.parse("2 h", n));
        assertEquals(n - 3 * 7 * 86_400_000L, InboxTimeParser.parse("3 sem.", n));
        assertTrue(InboxTimeParser.parse("1 an", n) < InboxTimeParser.parse("48 sem.", n));
    }

    @Test
    public void inconnu() {
        assertEquals(0L, InboxTimeParser.parse("", now()));
        assertEquals(0L, InboxTimeParser.parse("bientôt", now()));
    }
}
