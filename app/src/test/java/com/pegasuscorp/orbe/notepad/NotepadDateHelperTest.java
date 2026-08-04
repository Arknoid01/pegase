package com.pegasuscorp.orbe.notepad;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.*;

public class NotepadDateHelperTest {

    @Test
    public void resolve_noDayNoHour_defaultsToOneHour() {
        long before = System.currentTimeMillis();
        NotepadDateHelper.ReminderResolution r = NotepadDateHelper.resolveReminder(
                "rappelle-moi d'appeler", "rappelle moi d appeler", "", 0, true);
        long after = System.currentTimeMillis();
        assertTrue(r.appliedDefault);
        assertEquals("", r.dueDate);
        assertTrue(r.reminderAt >= before + NotepadDateHelper.DEFAULT_REMINDER_OFFSET_MS - 2000);
        assertTrue(r.reminderAt <= after + NotepadDateHelper.DEFAULT_REMINDER_OFFSET_MS + 2000);
        assertTrue(r.spokenWhen.contains("heure"));
    }

    @Test
    public void resolve_demainWithoutHour_defaultsTo9am() {
        NotepadDateHelper.ReminderResolution r = NotepadDateHelper.resolveReminder(
                "rappelle-moi demain d'acheter du pain",
                "rappelle moi demain d acheter du pain", "", 0, true);
        assertTrue(r.appliedDefault);
        assertEquals(NotepadDateHelper.tomorrow(), r.dueDate);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(r.reminderAt);
        assertEquals(NotepadDateHelper.DEFAULT_REMINDER_HOUR, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, cal.get(Calendar.MINUTE));
        assertTrue(r.spokenWhen.contains("demain"));
        assertTrue(r.spokenWhen.contains("9"));
    }

    @Test
    public void resolve_simpleNote_noReminder() {
        NotepadDateHelper.ReminderResolution r = NotepadDateHelper.resolveReminder(
                "courgette huile", "courgette huile", "", 0, false);
        assertFalse(r.appliedDefault);
        assertEquals(0, r.reminderAt);
        assertEquals("", r.dueDate);
        assertEquals("", r.spokenWhen);
    }

    @Test
    public void resolve_explicitTime_noDefault() {
        NotepadDateHelper.ReminderResolution r = NotepadDateHelper.resolveReminder(
                "rappelle-moi demain à 14h d'appeler",
                "rappelle moi demain a 14h d appeler", "", 0, true);
        assertFalse(r.appliedDefault);
        assertEquals(NotepadDateHelper.tomorrow(), r.dueDate);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(r.reminderAt);
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void dueDate_demain() {
        assertEquals(NotepadDateHelper.tomorrow(),
                NotepadDateHelper.parseDueDate("ajoute pain demain"));
    }
}
