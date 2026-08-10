package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PcShellDeepLinkTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        PcShellAutoRunPending.resetForTests();
        PcShellCommandStore.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        PcShellAutoRunPending.resetForTests();
        PcShellCommandStore.resetForTests(ctx);
    }

    @Test
    public void slugFromUri_parsesAutoRunCommand() {
        Uri uri = Uri.parse("pegase://pc-shell/run?autoRunCommand=mise-a-jour-arch");
        assertEquals("mise-a-jour-arch", PcShellDeepLink.slugFromUri(uri));
    }

    @Test
    public void captureAutoRun_persistsSlug() {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("pegase://pc-shell/run?autoRunCommand=mise-a-jour-arch"));
        PcShellDeepLink.captureAutoRun(ctx, i);
        assertEquals("mise-a-jour-arch", PcShellAutoRunPending.takePendingSlug(ctx));
    }

    @Test
    public void ensureBuiltin_createsBuildApk() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        SavedCommand cmd = store.ensureBuiltin(
                PcShellAutoRunPending.SLUG_BUILD_APK,
                "Build APK (main → Drive)",
                "sudo -u Pegase /usr/local/bin/pegase-sync-build-apk.sh",
                PcShellPrefs.BUILD_APK_TIMEOUT_SEC);
        assertNotNull(cmd);
        assertEquals("build-apk", cmd.slug);
        assertEquals(Integer.valueOf(PcShellPrefs.BUILD_APK_TIMEOUT_SEC), cmd.timeoutSeconds);
    }

    @Test
    public void ensureBuiltin_createsMiseAJourArch() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        SavedCommand cmd = store.ensureBuiltin(
                PcShellAutoRunPending.SLUG_MISE_A_JOUR_ARCH,
                "Mise à jour Arch",
                "sudo pacman -Syu",
                PcShellPrefs.PACMAN_TIMEOUT_SEC);
        assertNotNull(cmd);
        assertEquals("mise-a-jour-arch", cmd.slug);
        assertEquals("sudo pacman -Syu", cmd.command);
        assertEquals(Integer.valueOf(PcShellPrefs.PACMAN_TIMEOUT_SEC), cmd.timeoutSeconds);
        assertNotNull(store.getBySlug("mise-a-jour-arch"));
    }

    @Test
    public void ensureBuiltin_upgradesMissingTimeout() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        SavedCommand legacy = store.ensureBuiltin(
                PcShellAutoRunPending.SLUG_MISE_A_JOUR_ARCH,
                "Mise à jour Arch",
                "sudo pacman -Syu");
        assertNull(legacy.timeoutSeconds);

        SavedCommand upgraded = store.ensureBuiltin(
                PcShellAutoRunPending.SLUG_MISE_A_JOUR_ARCH,
                "Mise à jour Arch",
                "sudo pacman -Syu",
                PcShellPrefs.PACMAN_TIMEOUT_SEC);
        assertEquals(Integer.valueOf(PcShellPrefs.PACMAN_TIMEOUT_SEC), upgraded.timeoutSeconds);
        assertEquals(Integer.valueOf(PcShellPrefs.PACMAN_TIMEOUT_SEC),
                store.getBySlug(PcShellAutoRunPending.SLUG_MISE_A_JOUR_ARCH).timeoutSeconds);
    }

    @Test
    public void savedCommand_jsonRoundTrip_timeout() throws Exception {
        SavedCommand original = new SavedCommand(
                "id1", "slug", "Label", "cmd", 600, 1000L, null, null);
        SavedCommand parsed = SavedCommand.fromJson(original.toJson());
        assertNotNull(parsed);
        assertEquals(Integer.valueOf(600), parsed.timeoutSeconds);
    }
}
