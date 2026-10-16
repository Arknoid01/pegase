package com.pegasuscorp.orbe.sms;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SmsSpamRulesTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long OLD = NOW - 10L * 24 * 60 * 60 * 1000;

    @Test
    public void serviceSender_lettersOrShortCode() {
        assertTrue(SmsSpamRules.isServiceSender("AMELI"));
        assertTrue(SmsSpamRules.isServiceSender("38015"));
        assertFalse(SmsSpamRules.isServiceSender("+33612345678"));
        assertFalse(SmsSpamRules.isServiceSender("0756123456"));
    }

    @Test
    public void classify_oldMobileNumberIsCheckedSpam() {
        SmsSpamRules.Candidate c = new SmsSpamRules.Candidate(1, "+33612345678", "Votre colis…", OLD);
        SmsSpamRules.classify(c);
        assertEquals(SmsSpamRules.Category.LIKELY_SPAM, c.category);
        assertTrue(c.checked);
    }

    @Test
    public void classify_serviceNotChecked_recentMobileChecked() {
        SmsSpamRules.Candidate service = new SmsSpamRules.Candidate(2, "LaPoste", "Livraison", OLD);
        SmsSpamRules.classify(service);
        assertEquals(SmsSpamRules.Category.SERVICE, service.category);
        assertFalse(service.checked);

        SmsSpamRules.Candidate recent = new SmsSpamRules.Candidate(3, "+33612345678", "Colis", NOW - 1000);
        SmsSpamRules.classify(recent);
        assertEquals(SmsSpamRules.Category.LIKELY_SPAM, recent.category);
        assertTrue(recent.checked);
    }

    @Test
    public void llmVerdicts_parsedAndApplied() {
        String reply = "Voici :\n[{\"id\":2,\"verdict\":\"spam\",\"raison\":\"faux colis\"},"
                + "{\"id\":1,\"verdict\":\"legit\",\"raison\":\"banque\"}]";
        Map<Long, String[]> v = SmsSpamRules.parseLlmVerdicts(reply);
        assertEquals(2, v.size());

        SmsSpamRules.Candidate c = new SmsSpamRules.Candidate(2, "LaPoste", "x", OLD);
        SmsSpamRules.classify(c);
        SmsSpamRules.applyVerdict(c, SmsSpamRules.verdictOf(v.get(2L)[0]), v.get(2L)[1]);
        assertTrue(c.checked);
        assertEquals("faux colis", c.reason);

        SmsSpamRules.Candidate d = new SmsSpamRules.Candidate(1, "+33612345678", "x", OLD);
        SmsSpamRules.classify(d);
        SmsSpamRules.applyVerdict(d, SmsSpamRules.verdictOf(v.get(1L)[0]), v.get(1L)[1]);
        assertFalse(d.checked);
    }

    @Test
    public void llmVerdicts_garbageGivesEmpty() {
        assertTrue(SmsSpamRules.parseLlmVerdicts("désolé je ne peux pas").isEmpty());
        assertEquals(SmsSpamRules.Verdict.UNSURE, SmsSpamRules.verdictOf("doute"));
    }

    @Test
    public void prompt_containsIdsAndTruncates() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 100; i++) longBody.append("abcdef ");
        SmsSpamRules.Candidate c = new SmsSpamRules.Candidate(42, "+336", longBody.toString(), OLD);
        String p = SmsSpamRules.buildLlmPrompt(Arrays.asList(c));
        assertTrue(p.contains("\"id\":42"));
        assertTrue(p.contains("…"));
    }
}
