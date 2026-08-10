package com.pegasuscorp.orbe.pcshell;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.KeyFactory;
import java.security.Security;

/**
 * Enregistre Bouncy Castle pour sshj sur Android (X25519, Ed25519).
 * Android/Conscrypt ne fournit pas toujours ces algos — BC doit être en tête.
 */
public final class PcShellCrypto {

    private static volatile BouncyCastleProvider provider;

    private PcShellCrypto() {}

    /**
     * Idempotent — réinsère BC si absent ou si Ed25519 n'est pas disponible.
     */
    public static synchronized void ensureSecurityProviders() {
        if (provider != null && Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == provider) {
            if (canUseEd25519()) return;
        }
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception ignored) {
        }
        provider = new BouncyCastleProvider();
        Security.insertProviderAt(provider, 1);
    }

    static boolean canUseEd25519() {
        try {
            KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME);
            KeyFactory.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Visible tests. */
    public static synchronized void resetForTests() {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception ignored) {
        }
        provider = null;
    }
}
