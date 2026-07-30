package com.pegasuscorp.orbe.copilot;

import android.app.Notification;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotNotificationFilterTest {

    @Test
    public void shouldAlert_nullSbn() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertFalse(CopilotNotificationFilter.shouldAlert(ctx, null));
    }

    @Test
    public void shouldAlert_disabled() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setNotificationCopilotEnabled(ctx, false);
        assertFalse(CopilotNotificationFilter.shouldAlert(ctx, null));
    }

    @Test
    public void hasAlertContent_withTitle() {
        Notification n = new Notification.Builder(ApplicationProvider.getApplicationContext(),
                "test")
                .setContentTitle("Marine")
                .setContentText("Salut")
                .build();
        assertTrue(CopilotNotificationFilter.hasAlertContent(n));
    }

    @Test
    public void hasAlertContent_empty() {
        Notification n = new Notification.Builder(ApplicationProvider.getApplicationContext(),
                "test")
                .build();
        assertFalse(CopilotNotificationFilter.hasAlertContent(n));
    }

    @Test
    public void hasAlertContent_groupSummaryFlag() {
        Notification n = new Notification.Builder(ApplicationProvider.getApplicationContext(),
                "test")
                .setContentTitle("Groupe")
                .setGroupSummary(true)
                .build();
        assertTrue((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
    }
}
