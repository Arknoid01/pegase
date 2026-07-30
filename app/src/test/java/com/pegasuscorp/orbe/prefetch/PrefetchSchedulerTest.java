package com.pegasuscorp.orbe.prefetch;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Calendar;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PrefetchSchedulerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PrefetchScheduler.cancel(ctx);
        PrefetchScheduler.setAlarmTime(ctx,
                PrefetchScheduler.DEFAULT_HOUR, PrefetchScheduler.DEFAULT_MINUTE);
    }

    @After
    public void tearDown() {
        PrefetchScheduler.cancel(ctx);
    }

    @Test
    public void defaultTime_is640() {
        assertEquals(6, PrefetchScheduler.getHour(ctx));
        assertEquals(40, PrefetchScheduler.getMinute(ctx));
        assertEquals("06:40", PrefetchScheduler.formatTimeLabel(ctx));
    }

    @Test
    public void setAlarmTime_persists() {
        PrefetchScheduler.setAlarmTime(ctx, 7, 15);
        assertEquals(7, PrefetchScheduler.getHour(ctx));
        assertEquals(15, PrefetchScheduler.getMinute(ctx));
        assertEquals("07:15", PrefetchScheduler.formatTimeLabel(ctx));
    }

    @Test
    public void nextTrigger_sameDayIfStillAhead() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.JULY, 15, 5, 0, 0);
        now.set(Calendar.MILLISECOND, 0);
        long trigger = PrefetchScheduler.nextTriggerMillis(6, 40, now.getTimeInMillis());
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(trigger);
        assertEquals(15, t.get(Calendar.DAY_OF_MONTH));
        assertEquals(6, t.get(Calendar.HOUR_OF_DAY));
        assertEquals(40, t.get(Calendar.MINUTE));
    }

    @Test
    public void nextTrigger_nextDayIfPast() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.JULY, 15, 8, 0, 0);
        now.set(Calendar.MILLISECOND, 0);
        long trigger = PrefetchScheduler.nextTriggerMillis(6, 40, now.getTimeInMillis());
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(trigger);
        assertEquals(16, t.get(Calendar.DAY_OF_MONTH));
        assertEquals(6, t.get(Calendar.HOUR_OF_DAY));
    }
}
