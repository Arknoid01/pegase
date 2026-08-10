package com.pegasuscorp.orbe.pcshell;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.security.SecureRandom;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PcShellCommandStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        PcShellPrefs.setBypassForTests(true);
        PcShellSession.unlock();
        PcShellCrypto.resetForTests();
        PcShellCrypto.ensureSecurityProviders();
        PcShellCommandStore.resetForTests(ctx);
        PcShellPrefs.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        PcShellCommandStore.resetForTests(ctx);
    }

    @Test
    public void addAndList_persistsCommand() {
        PcShellCommandStore store = PcShellCommandStore.getInstance(ctx);
        SavedCommand c = store.add("Test uptime", "uptime");
        assertNotNull(c);
        assertEquals(1, store.listAll().size());
        assertEquals("uptime", store.listAll().get(0).command);

        PcShellCommandStore.resetInstanceForTests();
        assertEquals(1, PcShellCommandStore.getInstance(ctx).listAll().size());
    }

    @Test
    public void sshKeyManager_generatesOpenSshPrivateKey() {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        assertTrue(k.publicKeyLine.startsWith("ssh-ed25519 "));
        assertTrue(k.publicKeyLine.contains("pegase-android"));
        assertTrue(k.privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY"));
        assertFalse(k.publicKeyLine.isEmpty());
        assertFalse(SshKeyManager.fingerprintSha256(k.publicKeyLine).isEmpty());
    }

    @Test
    public void sshKeyManager_migratePkcs8_preservesPublicLine() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        org.bouncycastle.crypto.AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        Ed25519PrivateKeyParameters priv = (Ed25519PrivateKeyParameters) pair.getPrivate();
        SshKeyManager.KeyMaterial reference = SshKeyManager.generate();
        String pkcs8 = SshKeyManager.toPkcs8Pem(priv);
        SshKeyManager.KeyMaterial migrated =
                SshKeyManager.migratePkcs8ToOpenSsh(pkcs8, reference.publicKeyLine);
        assertTrue(migrated.privateKeyPem.contains("BEGIN OPENSSH PRIVATE KEY"));
        assertEquals(reference.publicKeyLine, migrated.publicKeyLine);
    }

    @Test
    public void pcShellCrypto_registersX25519() throws Exception {
        PcShellCrypto.ensureSecurityProviders();
        java.security.KeyFactory.getInstance("X25519", "BC");
    }
}
