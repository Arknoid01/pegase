package com.pegasuscorp.orbe.tools.knowledge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.InetAddress;

public class WebReadToolTest {

    @Test
    public void adressesPriveesEtLocalesBloquees() throws Exception {
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("127.0.0.1")));
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("192.168.1.10")));
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("10.0.0.5")));
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("100.101.102.103")));
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("169.254.169.254")));
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("::1")));
        assertTrue(WebReadTool.isBlocked(InetAddress.getByName("fd00::1")));
        assertFalse(WebReadTool.isBlocked(InetAddress.getByName("93.184.216.34")));
        assertFalse(WebReadTool.isBlocked(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void httpEtHoteLocal_refuses() {
        try {
            WebReadTool.checkUrl("http://example.com/x");
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("https"));
        }
        try {
            WebReadTool.checkUrl("https://127.0.0.1/admin");
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("non autorisée"));
        }
    }

    @Test
    public void htmlToText_retireScriptsEtBalises() {
        String text = WebReadTool.htmlToText("<html><head><title>Mon article</title>"
                + "<style>p{color:red}</style></head><body><script>alert(1)</script>"
                + "<h1>Titre</h1><p>Un &amp; deux</p><p>Trois</p></body></html>");
        assertTrue(text.startsWith("Mon article"));
        assertTrue(text.contains("Titre"));
        assertTrue(text.contains("Un & deux"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color:red"));
        assertFalse(text.contains("<"));
    }
}
