package com.pegasuscorp.orbe.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SensitiveToolsTest {

    @Test
    public void outilsPersonnelsEtMcp_sontSensibles() {
        for (String id : new String[]{"sms", "Notifications", "contacts", "clipboard", "call",
                "app_usage", "read_image", "files", "pc_shell", "web_read", "mcp_notes_x"}) {
            assertTrue(id, SensitiveTools.isSensitive(id));
        }
    }

    @Test
    public void outilsOrdinaires_ne_lesont_pas() {
        for (String id : new String[]{"weather", "notepad", "spotify", "calculator", "timer",
                null, ""}) {
            assertFalse(String.valueOf(id), SensitiveTools.isSensitive(id));
        }
    }
}
