package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Génération paire Ed25519 + format OpenSSH private key pour sshj sur Android.
 */
public final class SshKeyManager {

    private static final String COMMENT = "pegase-android";

    public static final class KeyMaterial {
        public final String privateKeyPem;
        public final String publicKeyLine;

        KeyMaterial(String privateKeyPem, String publicKeyLine) {
            this.privateKeyPem = privateKeyPem;
            this.publicKeyLine = publicKeyLine;
        }
    }

    private SshKeyManager() {}

    public static synchronized KeyMaterial emptyKeyMaterial() {
        return new KeyMaterial("", "");
    }

    public static synchronized KeyMaterial ensureKeyPair(Context context) {
        KeyMaterial material;
        if (PcShellPrefs.hasKeyPair(context)) {
            String priv = PcShellPrefs.getPrivateKeyPem(context);
            String pub = PcShellPrefs.getPublicKeyLine(context);
            if (priv.contains("BEGIN PRIVATE KEY")) {
                material = migratePkcs8ToOpenSsh(priv, pub);
            } else {
                material = new KeyMaterial(priv, pub);
            }
        } else {
            material = generate();
        }
        material = syncPublicFromPrivate(material);
        PcShellPrefs.saveKeyPair(context, material.privateKeyPem, material.publicKeyLine);
        return material;
    }

    public static synchronized KeyMaterial regenerate(Context context) {
        KeyMaterial material = generate();
        PcShellPrefs.saveKeyPair(context, material.privateKeyPem, material.publicKeyLine);
        return material;
    }

    /** Empreinte SHA256 — format {@code ssh-keygen -lf} (base64). */
    public static String fingerprintSha256(String publicKeyLine) {
        if (publicKeyLine == null || publicKeyLine.trim().isEmpty()) return "";
        try {
            String[] parts = publicKeyLine.trim().split("\\s+");
            if (parts.length < 2) return "";
            byte[] blob = Base64.decode(parts[1], Base64.DEFAULT);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(blob);
            String b64 = Base64.encodeToString(hash, Base64.NO_WRAP);
            int end = b64.indexOf('=');
            if (end > 0) b64 = b64.substring(0, end);
            return "SHA256:" + b64;
        } catch (Exception e) {
            return "";
        }
    }

    static KeyMaterial syncPublicFromPrivate(KeyMaterial material) {
        try {
            Ed25519PrivateKeyParameters priv = loadOpenSshPrivate(material.privateKeyPem);
            Ed25519PublicKeyParameters pub = priv.generatePublicKey();
            String pubLine = "ssh-ed25519 " + Base64.encodeToString(
                    OpenSSHPublicKeyUtil.encodePublicKey(pub), Base64.NO_WRAP)
                    + " " + COMMENT;
            return new KeyMaterial(material.privateKeyPem, pubLine);
        } catch (Exception e) {
            return material;
        }
    }

    static Ed25519PrivateKeyParameters loadOpenSshPrivate(String opensshPem) throws Exception {
        PcShellCrypto.ensureSecurityProviders();
        byte[] body = parsePemBody(opensshPem);
        return (Ed25519PrivateKeyParameters) OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(body);
    }

    static KeyMaterial migratePkcs8ToOpenSsh(String pkcs8Pem, String publicKeyLine) {
        PcShellCrypto.ensureSecurityProviders();
        try {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(parsePemBody(pkcs8Pem));
            Ed25519PrivateKeyParameters priv =
                    (Ed25519PrivateKeyParameters) PrivateKeyFactory.createKey(info);
            String opensshPem = toOpenSshPrivatePem(priv);
            String pubLine = publicKeyLine;
            if (pubLine == null || pubLine.trim().isEmpty()) {
                Ed25519PublicKeyParameters pub = priv.generatePublicKey();
                pubLine = "ssh-ed25519 " + Base64.encodeToString(
                        OpenSSHPublicKeyUtil.encodePublicKey(pub), Base64.NO_WRAP)
                        + " " + COMMENT;
            }
            return new KeyMaterial(opensshPem, pubLine);
        } catch (Exception e) {
            throw new IllegalStateException("Migration clé PKCS8 → OpenSSH impossible", e);
        }
    }

    static KeyMaterial generate() {
        PcShellCrypto.ensureSecurityProviders();
        try {
            Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
            gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
            org.bouncycastle.crypto.AsymmetricCipherKeyPair pair = gen.generateKeyPair();
            Ed25519PrivateKeyParameters priv = (Ed25519PrivateKeyParameters) pair.getPrivate();
            Ed25519PublicKeyParameters pub = (Ed25519PublicKeyParameters) pair.getPublic();

            String opensshPem = toOpenSshPrivatePem(priv);
            String pubLine = "ssh-ed25519 " + Base64.encodeToString(
                    OpenSSHPublicKeyUtil.encodePublicKey(pub), Base64.NO_WRAP)
                    + " " + COMMENT;
            return new KeyMaterial(opensshPem, pubLine);
        } catch (Exception e) {
            throw new IllegalStateException("Génération clé Ed25519 impossible", e);
        }
    }

    static String toOpenSshPrivatePem(Ed25519PrivateKeyParameters priv) throws Exception {
        byte[] encoded = OpenSSHPrivateKeyUtil.encodePrivateKey(priv);
        return wrapPem("OPENSSH PRIVATE KEY", encoded);
    }

    static byte[] parsePemBody(String pem) {
        if (pem == null) return new byte[0];
        String body = pem
                .replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replace("-----END OPENSSH PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.decode(body, Base64.DEFAULT);
    }

    static String wrapPem(String label, byte[] der) {
        String b64 = Base64.encodeToString(der, Base64.NO_WRAP);
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            pem.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        pem.append("-----END ").append(label).append("-----\n");
        return pem.toString();
    }

    /** Visible tests — PKCS8 PEM à partir de params BC. */
    static String toPkcs8Pem(Ed25519PrivateKeyParameters priv) throws Exception {
        return wrapPem("PRIVATE KEY",
                PrivateKeyInfoFactory.createPrivateKeyInfo(priv).getEncoded());
    }
}
