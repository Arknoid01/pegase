package com.pegasuscorp.orbe.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MailTextTest {

    @Test
    public void htmlNettoye() {
        String t = MailText.htmlToText("<html><head><style>p{}</style></head><body>"
                + "<p>Bonjour&nbsp;Yannick,</p><p>Votre commande&amp;co</p>"
                + "<script>alert(1)</script></body></html>");
        assertTrue(t.startsWith("Bonjour Yannick,"));
        assertTrue(t.contains("Votre commande&co"));
        assertFalse(t.contains("alert"));
        assertFalse(t.contains("<"));
    }

    @Test
    public void motDePasseParBlocs() {
        assertEquals("abcdefghijklmnop",
                MailAccountStore.normalizePassword(" abcd efgh ijkl mnop "));
    }
}
