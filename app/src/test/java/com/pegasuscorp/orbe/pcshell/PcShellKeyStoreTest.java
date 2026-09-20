package com.pegasuscorp.orbe.pcshell;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PcShellKeyStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        PcShellPrefs.resetForTests(ctx);
        PcShellPrefs.setBypassForTests(true);
        PcShellSession.unlock();
        PcShellKeyCache.resetForTests();
    }

    @After
    public void tearDown() {
        PcShellPrefs.resetForTests(ctx);
        PcShellKeyCache.resetForTests();
    }

    @Test
    public void saveKeyPair_publicKeyReadableWithoutPrivateInMemory() {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        PcShellPrefs.saveKeyPair(ctx, k.privateKeyPem, k.publicKeyLine);
        PcShellKeyCache.lock();
        PcShellSession.resetForTests();

        assertTrue(PcShellPrefs.hasKeyPair(ctx));
        assertEquals(k.publicKeyLine, PcShellPrefs.getPublicKeyLine(ctx));
    }

    @Test
    public void saveKeyPair_rejectsEmptyPair() {
        try {
            PcShellPrefs.saveKeyPair(ctx, "", "");
            fail("Expected IllegalStateException for empty key pair");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("vide"));
        }
    }

    @Test
    public void saveKeyPair_keepsPublicKeyAfterPrivateCacheCleared() {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        PcShellPrefs.saveKeyPair(ctx, k.privateKeyPem, k.publicKeyLine);
        PcShellKeyCache.lock();
        assertEquals(k.publicKeyLine, PcShellPrefs.getPublicKeyLine(ctx));
    }

    @Test
    public void regenerate_replacesStoredKeys() {
        SshKeyManager.KeyMaterial first = SshKeyManager.ensureKeyPair(ctx);
        SshKeyManager.KeyMaterial second = SshKeyManager.regenerate(ctx);
        assertNotEquals(first.publicKeyLine, second.publicKeyLine);
        assertEquals(second.publicKeyLine, PcShellPrefs.getPublicKeyLine(ctx));
    }
}
