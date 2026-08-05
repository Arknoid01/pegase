package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Robolectric requis : l'initialisation statique de {@link A11yUiExecutor} construit un
 * {@code Handler(Looper.getMainLooper())}, indisponible sur une JVM nue.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
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
