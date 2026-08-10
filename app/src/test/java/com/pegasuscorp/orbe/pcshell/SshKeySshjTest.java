package com.pegasuscorp.orbe.pcshell;

import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.password.PasswordFinder;

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SshKeySshjTest {

    @Before
    public void setUp() {
        PcShellCrypto.resetForTests();
        PcShellCrypto.ensureSecurityProviders();
        PcShellPrefs.setBypassForTests(true);
        PcShellSession.unlock();
    }

    @Test
    public void generatedKey_openSshV1KeyFile_loadsPublicKey() throws Exception {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        OpenSSHKeyV1KeyFile keyFile = new OpenSSHKeyV1KeyFile();
        keyFile.init(new StringReader(k.privateKeyPem), (PasswordFinder) null);
        assertNotNull(keyFile.getPublic());
        assertTrue(keyFile.getPublic().getAlgorithm().startsWith("Ed"));
    }

    @Test
    public void generatedKey_legacyOpenSshKeyFile_doesNotLoadV1Format() throws Exception {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        OpenSSHKeyFile keyFile = new OpenSSHKeyFile();
        try {
            keyFile.init(new StringReader(k.privateKeyPem), (PasswordFinder) null);
            keyFile.getPublic();
            fail("OpenSSHKeyFile ne doit pas charger le format openssh-key-v1");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("OPENSSH PRIVATE KEY")
                    || expected.getMessage().contains("unrecognised"));
        }
    }

    @Test
    public void generatedKey_pemContainsOpenSshMagic() {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        byte[] body = SshKeyManager.parsePemBody(k.privateKeyPem);
        assertTrue(body.length > 15);
        String magic = new String(body, 0, 15);
        assertEquals("openssh-key-v1\u0000", magic);
    }

    @Test
    public void syncPublicFromPrivate_matchesGenerate() throws Exception {
        SshKeyManager.KeyMaterial k = SshKeyManager.generate();
        SshKeyManager.KeyMaterial synced = SshKeyManager.syncPublicFromPrivate(k);
        assertEquals(k.publicKeyLine, synced.publicKeyLine);
        assertEquals(
                SshKeyManager.fingerprintSha256(k.publicKeyLine),
                SshKeyManager.fingerprintSha256(synced.publicKeyLine));
    }
}
