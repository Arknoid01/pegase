package com.pegasuscorp.orbe.tools.life;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FrenchDateTimeParserTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Test
    public void parsesIsoDateTime() {
        long ms = FrenchDateTimeParser.parseToEpochMs("2026-07-20 11:30");
        LocalDateTime dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ms), PARIS);
        assertEquals(2026, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(20, dt.getDayOfMonth());
        assertEquals(11, dt.getHour());
        assertEquals(30, dt.getMinute());
    }

    @Test
    public void parsesDemainWithTime() {
        long ms = FrenchDateTimeParser.parseToEpochMs("demain 11h30");
        LocalDateTime dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ms), PARIS);
        assertEquals(LocalDate.now(PARIS).plusDays(1), dt.toLocalDate());
        assertEquals(11, dt.getHour());
        assertEquals(30, dt.getMinute());
    }

    @Test
    public void parsesRelativeHours() {
        long before = System.currentTimeMillis();
        long ms = FrenchDateTimeParser.parseToEpochMs("dans 2 heures");
        long after = System.currentTimeMillis();
        assertTrue(ms > before + 2 * 3600_000L - 5_000);
        assertTrue(ms < after + 2 * 3600_000L + 5_000);
    }

    @Test
    public void formatSpokenDemain() {
        LocalDateTime tomorrow = LocalDate.now(PARIS).plusDays(1).atTime(11, 30);
        long ms = tomorrow.atZone(PARIS).toInstant().toEpochMilli();
        String spoken = FrenchDateTimeParser.formatSpoken(ms);
        assertTrue(spoken.contains("demain"));
        assertTrue(spoken.contains("11h30") || spoken.contains("11h"));
    }
}
