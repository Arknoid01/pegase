package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class A11yUiExecutorStripTest {

    @Test
    public void stripLeadingArticles_deMicro() {
        assertEquals("micro", A11yUiExecutor.stripLeadingArticles("de micro"));
        assertEquals("micro", A11yUiExecutor.stripLeadingArticles("du micro"));
        assertEquals("micro", A11yUiExecutor.stripLeadingArticles("le micro"));
        assertEquals("micro", A11yUiExecutor.stripLeadingArticles("de le micro"));
    }

    @Test
    public void unwrapThenStrip_iconMicro() {
        String unwrapped = A11yUiExecutor.unwrapIconTarget("[icône: micro]");
        assertEquals("micro", A11yUiExecutor.stripLeadingArticles(unwrapped));
    }
}
