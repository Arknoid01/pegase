package com.pegasuscorp.orbe.intentions;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import com.pegasuscorp.orbe.intentions.rules.BatteryLowRule;
import com.pegasuscorp.orbe.intentions.rules.BriefReadyRule;
import com.pegasuscorp.orbe.intentions.rules.DriveBluetoothRule;
import com.pegasuscorp.orbe.intentions.rules.WorkWifiRule;

import java.util.Calendar;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class IntentionPolicyTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        IntentionPrefs.clearAll(ctx);
        IntentionPrefs.setEnabled(ctx, true);
        // Fenêtre calme placée hors de l'heure courante. Elle était figée à 22h-7h, si
        // bien que ces tests — qui ne portent pas sur les heures calmes — échouaient
        // systématiquement quand la suite tournait le soir ou la nuit.
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        IntentionPrefs.setQuietHours(ctx, (hour + 2) % 24, (hour + 3) % 24);
    }

    @Test
    public void suppress_blocks() {
        IntentionCandidate c = new IntentionCandidate(
                IntentionIds.BATTERY_LOW, "t", "b", "battery");
        assertTrue(IntentionPolicy.canFire(ctx, c));
        IntentionPrefs.suppress(ctx, IntentionIds.BATTERY_LOW);
        assertFalse(IntentionPolicy.canFire(ctx, c));
    }

    @Test
    public void snooze_per_id_does_not_block_other() {
        IntentionPrefs.snoozeFor(ctx, IntentionIds.BATTERY_LOW, IntentionPrefs.SNOOZE_MS);
        assertFalse(IntentionPolicy.canFire(ctx, new IntentionCandidate(
                IntentionIds.BATTERY_LOW, "t", "b", "battery")));
        assertTrue(IntentionPolicy.canFire(ctx, new IntentionCandidate(
                IntentionIds.BRIEF_READY, "t", "b", "brief")));
    }

    @Test
    public void quietHours_wrapsMidnight() {
        // Ce test-ci porte sur la fenêtre elle-même : il la pose explicitement, le setUp
        // commun l'ayant déplacée hors de l'heure courante pour les autres.
        IntentionPrefs.setQuietHours(ctx, 22, 7);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 0);
        assertTrue(IntentionPolicy.isQuietHours(ctx, cal.getTimeInMillis()));
        cal.set(Calendar.HOUR_OF_DAY, 10);
        assertFalse(IntentionPolicy.isQuietHours(ctx, cal.getTimeInMillis()));
    }

    @Test
    public void dailyQuota() {
        IntentionPrefs.markFired(ctx, IntentionIds.BATTERY_LOW);
        IntentionPrefs.markFired(ctx, IntentionIds.WORK_WIFI);
        assertFalse(IntentionPolicy.canFire(ctx, new IntentionCandidate(
                IntentionIds.BRIEF_READY, "t", "b", "brief")));
    }

    @Test
    public void batteryEdge_firesOnce() {
        BatteryLowRule rule = new BatteryLowRule();
        ContextSnapshot edge = new ContextSnapshot(
                18, false, "", 21, "", "", false, false, false, System.currentTimeMillis());
        assertNotNull(rule.evaluate(edge));
        ContextSnapshot stay = new ContextSnapshot(
                16, false, "", 17, "", "", false, false, false, System.currentTimeMillis());
        assertNull(rule.evaluate(stay));
        ContextSnapshot charging = new ContextSnapshot(
                18, true, "", 21, "", "", false, false, false, System.currentTimeMillis());
        assertNull(rule.evaluate(charging));
    }

    @Test
    public void workWifi_edgeAndUnknown() {
        WorkWifiRule rule = new WorkWifiRule();
        ContextSnapshot enter = new ContextSnapshot(
                80, false, "Boucherie", 80, "Maison", "Boucherie",
                false, false, false, System.currentTimeMillis());
        assertNotNull(rule.evaluate(enter));
        ContextSnapshot stay = new ContextSnapshot(
                80, false, "Boucherie", 80, "Boucherie", "Boucherie",
                false, false, false, System.currentTimeMillis());
        assertNull(rule.evaluate(stay));
        ContextSnapshot unknown = new ContextSnapshot(
                80, false, "<unknown ssid>", 80, "Maison", "Boucherie",
                false, false, false, System.currentTimeMillis());
        assertNull(rule.evaluate(unknown));
    }

    @Test
    public void briefReady_onlyOnFlag() {
        BriefReadyRule rule = new BriefReadyRule();
        assertNull(rule.evaluate(new ContextSnapshot(
                80, false, "", 80, "", "", false, false, false, System.currentTimeMillis())));
        assertNotNull(rule.evaluate(new ContextSnapshot(
                80, false, "", 80, "", "", false, false, true, System.currentTimeMillis())));
    }

    @Test
    public void driveBt_edge() {
        DriveBluetoothRule rule = new DriveBluetoothRule();
        assertNotNull(rule.evaluate(new ContextSnapshot(
                80, false, "", 80, "", "", true, false, false, System.currentTimeMillis())));
        assertNull(rule.evaluate(new ContextSnapshot(
                80, false, "", 80, "", "", true, true, false, System.currentTimeMillis())));
        assertNull(rule.evaluate(new ContextSnapshot(
                80, false, "", 80, "", "", false, false, false, System.currentTimeMillis())));
    }

    @Test
    public void firedToday_blocksSameId() {
        IntentionPrefs.markFired(ctx, IntentionIds.BRIEF_READY);
        assertFalse(IntentionPolicy.canFire(ctx, new IntentionCandidate(
                IntentionIds.BRIEF_READY, "t", "b", "brief")));
    }

    @Test
    public void workWifiSsid_pref() {
        IntentionPrefs.setWorkWifiSsid(ctx, "  WorkNet ");
        assertEquals("WorkNet", IntentionPrefs.getWorkWifiSsid(ctx));
    }

    @Test
    public void calendarId_validAndPolicy() {
        assertTrue(IntentionIds.isValid("calendar:42"));
        assertFalse(IntentionIds.isValid("calendar:"));
        assertEquals("RDV bientôt", IntentionIds.displayName("calendar:99"));
        IntentionCandidate c = new IntentionCandidate(
                "calendar:42", "Pégase", "RDV bientôt", "calendar");
        assertTrue(IntentionPolicy.canFire(ctx, c));
    }

    @Test
    public void drivePrefs() {
        IntentionPrefs.setDriveDestination(ctx, "  Maison ");
        IntentionPrefs.setDriveSpotifyQuery(ctx, " jazz ");
        assertEquals("Maison", IntentionPrefs.getDriveDestination(ctx));
        assertEquals("jazz", IntentionPrefs.getDriveSpotifyQuery(ctx));
    }

    @Test
    public void calendarFormatTime() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 14);
        cal.set(Calendar.MINUTE, 5);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        assertEquals("14:05", CalendarSoon.formatTime(cal.getTimeInMillis()));
    }
}
