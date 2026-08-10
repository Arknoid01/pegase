package com.pegasuscorp.orbe.pcshell;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SshCommandHelperTest {

    @Test
    public void resolveTimeoutSec_savedOverridesDefault() {
        assertEquals(900, SshCommandHelper.resolveTimeoutSec("uptime", 900));
    }

    @Test
    public void resolveTimeoutSec_pacmanHeuristic() {
        assertEquals(PcShellPrefs.PACMAN_TIMEOUT_SEC,
                SshCommandHelper.resolveTimeoutSec("sudo pacman -Syu", null));
    }

    @Test
    public void resolveTimeoutSec_defaultForShortCommand() {
        assertEquals(PcShellPrefs.DEFAULT_TIMEOUT_SEC,
                SshCommandHelper.resolveTimeoutSec("uptime", null));
    }

    @Test
    public void resolveTimeoutSec_capsAtMax() {
        assertEquals(PcShellPrefs.MAX_TIMEOUT_SEC,
                SshCommandHelper.resolveTimeoutSec("uptime", 99999));
    }

    @Test
    public void looksLikeLongPackageCommand_detectsPacman() {
        assertTrue(SshCommandHelper.looksLikeLongPackageCommand("sudo pacman -Syu"));
        assertTrue(SshCommandHelper.looksLikeLongPackageCommand("yay -Syu"));
        assertFalse(SshCommandHelper.looksLikeLongPackageCommand("uptime"));
    }

    @Test
    public void useDetachedSession_forPacman() {
        long ms = SshCommandHelper.resolveTimeoutMs("sudo pacman -Syu", null);
        assertTrue(SshCommandHelper.useDetachedSession("sudo pacman -Syu", ms));
    }

    @Test
    public void wrapDetached_usesSetsid() {
        String wrapped = SshCommandHelper.wrapDetached("sudo pacman -Syu");
        assertTrue(wrapped.startsWith("setsid bash -lc '"));
        assertTrue(wrapped.endsWith("' </dev/null"));
    }

    @Test
    public void shellQuote_escapesSingleQuotes() {
        assertEquals("echo '\\''hi'\\''", SshCommandHelper.shellQuote("echo 'hi'"));
    }
}
