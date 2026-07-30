package com.pegasuscorp.orbe.tools.life;

import org.junit.Test;

import static org.junit.Assert.*;

public class DurationParserTest {

    @Test
    public void parseToSeconds_minutes() {
        assertEquals(300, DurationParser.parseToSeconds("5 minutes"));
    }

    @Test
    public void parseToSeconds_relative() {
        assertEquals(1800, DurationParser.parseToSeconds("dans 30 minutes"));
    }

    @Test
    public void parseToSeconds_hoursMinutes() {
        assertEquals(5400, DurationParser.parseToSeconds("1h30"));
    }

    @Test
    public void parseToSeconds_unknown() {
        assertEquals(-1, DurationParser.parseToSeconds("bientôt"));
    }
}
