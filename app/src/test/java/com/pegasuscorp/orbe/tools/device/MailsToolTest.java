package com.pegasuscorp.orbe.tools.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.mail.MailMessage;

import org.junit.Test;

import java.util.Arrays;

public class MailsToolTest {

    @Test
    public void nonLusSeulement() {
        StringBuilder spoken = new StringBuilder();
        StringBuilder llm = new StringBuilder();
        MailsTool.describe("moi@gmail.com", Arrays.asList(
                new MailMessage("moi@gmail.com", 2, "Banque", "b@x.fr", "Relevé", 2000, false,
                        "Votre relevé est disponible"),
                new MailMessage("moi@gmail.com", 1, "", "ami@x.fr", "Photos", 1000, true, "")),
                "à jour", true, spoken, llm);
        assertTrue(spoken.toString().contains("1 non lu"));
        assertTrue(llm.toString().contains("Banque [non lu]"));
        assertFalse(llm.toString().contains("Photos"));
    }
}
