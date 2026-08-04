package com.pegasuscorp.orbe.tools.device;

import android.app.AlarmManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowNotificationManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Régression : minuteur posé écran verrouillé → notification HIGH + son à la fin.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PegaseTimerLockedScreenTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        PegaseTimerScheduler.cancel(app);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancelAll();
    }

    @Test
    public void channel_isImportanceHigh_withSound() {
        PegaseTimerReceiver.ensureChannel(app);
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
                PegaseTimerReceiver.channelImportance(app));
        assertTrue(PegaseTimerReceiver.channelHasSound(app));
    }

    @Test
    public void schedule_usesAlarmClockOrExactWakeup() {
        long fireAt = PegaseTimerScheduler.schedule(app, 120, "thé");
        assertTrue(fireAt > System.currentTimeMillis());

        AlarmManager am = app.getSystemService(AlarmManager.class);
        assertNotNull(am);
        ShadowAlarmManager shadow = Shadows.shadowOf(am);
        ShadowAlarmManager.ScheduledAlarm next = shadow.peekNextScheduledAlarm();
        assertNotNull("alarme planifiée attendue", next);
        // Preferé : setAlarmClock (RTC_WAKEUP). Fallback : ELAPSED_REALTIME_WAKEUP.
        int type = next.getType();
        assertTrue("type=" + type,
                type == AlarmManager.RTC_WAKEUP
                        || type == AlarmManager.ELAPSED_REALTIME_WAKEUP);
    }

    @Test
    public void lockedScreen_timerFire_postsAlarmNotification() {
        KeyguardManager km = app.getSystemService(KeyguardManager.class);
        ShadowKeyguardManager skm = Shadows.shadowOf(km);
        skm.setKeyguardLocked(true);
        assertTrue(PegaseTimerReceiver.isKeyguardLocked(app));

        Notification notif = PegaseTimerReceiver.showTimerFinishedNotification(
                app, 90, "pâtes");
        assertNotNull(notif);

        ShadowNotificationManager snm =
                Shadows.shadowOf(app.getSystemService(NotificationManager.class));
        assertEquals(1, snm.size());
        Notification posted = snm.getAllNotifications().get(0);
        assertEquals(PegaseTimerReceiver.CHANNEL_ID, posted.getChannelId());
        assertEquals(Notification.CATEGORY_ALARM, posted.category);
        assertTrue((posted.flags & Notification.FLAG_INSISTENT) != 0);
        // Son configuré (channel ou notification)
        assertTrue(PegaseTimerReceiver.channelHasSound(app)
                || posted.sound != null
                || (posted.defaults & Notification.DEFAULT_SOUND) != 0);
    }

    @Test
    public void receiver_onReceive_postsWhenLocked() {
        KeyguardManager km = app.getSystemService(KeyguardManager.class);
        Shadows.shadowOf(km).setKeyguardLocked(true);

        android.content.Intent fire = new android.content.Intent(app, PegaseTimerReceiver.class)
                .setAction(PegaseTimerReceiver.ACTION_FIRE)
                .putExtra(PegaseTimerScheduler.EXTRA_SECONDS, 60)
                .putExtra(PegaseTimerScheduler.EXTRA_LABEL, "test");
        new PegaseTimerReceiver().onReceive(app, fire);

        ShadowNotificationManager snm =
                Shadows.shadowOf(app.getSystemService(NotificationManager.class));
        assertEquals(1, snm.size());
        assertEquals(PegaseTimerReceiver.CHANNEL_ID,
                snm.getAllNotifications().get(0).getChannelId());
    }

    @Test
    public void timerTool_startWhileLocked_schedulesPegaseAlarm() throws Exception {
        KeyguardManager km = app.getSystemService(KeyguardManager.class);
        Shadows.shadowOf(km).setKeyguardLocked(true);
        TimerTool.resetForTests();

        final java.util.concurrent.atomic.AtomicReference<String> reply =
                new java.util.concurrent.atomic.AtomicReference<>();
        new TimerTool().execute(app,
                new org.json.JSONObject().put("seconds", 45).put("label", "œuf"),
                new com.pegasuscorp.orbe.tools.ToolCallback() {
                    @Override
                    public void onSuccess(com.pegasuscorp.orbe.tools.ToolResult result) {}

                    @Override
                    public void onSuccessAndExit(com.pegasuscorp.orbe.tools.ToolResult result) {
                        reply.set(result != null ? result.text : null);
                    }

                    @Override
                    public void onConfirmNeeded(String q, Runnable ok, Runnable no) {}

                    @Override
                    public void onError(String error) {
                        throw new AssertionError(error);
                    }
                });

        assertNotNull(reply.get());
        assertTrue(reply.get().toLowerCase().contains("minuteur"));
        assertTrue(reply.get().contains("verrouillé") || reply.get().contains("verrouille"));

        ShadowAlarmManager shadow =
                Shadows.shadowOf(app.getSystemService(AlarmManager.class));
        assertNotNull(shadow.peekNextScheduledAlarm());
    }
}
