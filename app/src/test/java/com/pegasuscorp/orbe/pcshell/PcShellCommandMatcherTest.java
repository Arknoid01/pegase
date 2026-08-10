package com.pegasuscorp.orbe.pcshell;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.voice.VoiceIntentRouter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PcShellCommandMatcherTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        PcShellPrefs.setBypassForTests(true);
        PcShellSession.unlock();
        PcShellCommandStore.resetForTests(ctx);
        PcShellPrefs.resetForTests(ctx);
        PcShellVoicePending.resetForTests();
    }

    @After
    public void tearDown() {
        PcShellCommandStore.resetForTests(ctx);
        PcShellVoicePending.resetForTests();
    }

    @Test
    public void bestMatch_exactLabel() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        store.add("Espace disque", "df -h");
        store.add("Uptime", "uptime");

        PcShellCommandMatcher.Match m =
                PcShellCommandMatcher.bestMatch(store.listAll(), "espace disque");
        assertNotNull(m);
        assertEquals("Espace disque", m.command.label);
        assertTrue(m.score >= PcShellCommandMatcher.HIGH_CONFIDENCE);
    }

    @Test
    public void bestMatch_fuzzyLabel() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        store.add("Espace disque", "df -h");

        PcShellCommandMatcher.Match m =
                PcShellCommandMatcher.bestMatch(store.listAll(), "espace disques");
        assertNotNull(m);
        assertEquals("Espace disque", m.command.label);
        assertTrue(m.score >= PcShellCommandMatcher.MATCH_THRESHOLD);
    }

    @Test
    public void bestMatch_noMatch() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        store.add("Uptime", "uptime");

        assertNull(PcShellCommandMatcher.bestMatch(store.listAll(), "pacman update"));
    }

    @Test
    public void requiresSudo_detectsSudoCommand() {
        SavedCommand sudo = new SavedCommand("1", "", "Màj", "sudo pacman -Syu", 0L, null, null);
        SavedCommand plain = new SavedCommand("2", "", "Up", "uptime", 0L, null, null);
        assertTrue(PcShellCommandMatcher.requiresSudo(sudo));
        assertFalse(PcShellCommandMatcher.requiresSudo(plain));
    }

    @Test
    public void voiceRouter_openTerminal() {
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(ctx, "ouvre le terminal pc");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"pc_shell\""));
        assertTrue(r.directToolJson.contains("\"open\""));
    }

    @Test
    public void voiceRouter_executeFuzzy() {
        PcShellCommandStore.getInstance(ctx).add("Espace disque", "df -h");
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(ctx, "exécute espace disque");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"execute\""));
        assertTrue(r.directToolJson.contains("Espace disque"));
    }

    @Test
    public void voiceRouter_nomatchListsAvailable() {
        PcShellCommandStore.getInstance(ctx).add("Uptime", "uptime");
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(ctx, "exécute pacman");
        assertNotNull(r.directToolJson);
        assertTrue(r.directToolJson.contains("\"nomatch\""));
    }
}
