package com.pegasuscorp.orbe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.provider.Settings;

import com.pegasuscorp.orbe.tools.device.ContactsTool;
import com.pegasuscorp.orbe.tools.device.NotificationsTool;
import com.pegasuscorp.orbe.tools.media.ReadImageTool;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
public class ToolRequirementTest {

    @Test
    public void permissionManquante_donneUnMessageEtLaFicheDeLApplication() {
        Application app = RuntimeEnvironment.getApplication();
        ToolRequirement req = new ContactsTool().requirement(app);

        assertNotNull(req);
        assertTrue(req.message.contains("contacts"));
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, req.fixIntent(app).getAction());
        assertTrue((req.fixIntent(app).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void permissionAccordee_pasDeRequis() {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);
        assertNull(new ContactsTool().requirement(app));
    }

    @Test
    public void accesSpeciaux_ouvrentLeBonReglage() {
        Application app = RuntimeEnvironment.getApplication();
        ToolRequirement notif = new NotificationsTool().requirement(app);
        assertNotNull(notif);
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                notif.fixIntent(app).getAction());
        assertNotNull(new ReadImageTool().requirement(app));
    }

    @Test
    public void outilSansPrerequis_estUtilisable() {
        assertNull(new com.pegasuscorp.orbe.tools.device.CalculatorTool()
                .requirement(RuntimeEnvironment.getApplication()));
    }
}
