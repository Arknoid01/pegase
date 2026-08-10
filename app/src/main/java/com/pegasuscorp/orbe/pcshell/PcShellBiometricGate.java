package com.pegasuscorp.orbe.pcshell;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * BiometricPrompt pour accéder au Terminal PC et déverrouiller le coffre de clés SSH.
 */
public final class PcShellBiometricGate {

    public interface Callback {
        void onUnlocked();
        void onFailed(String message);
        void onCancelled();
    }

    private PcShellBiometricGate() {}

    public static boolean canAuthenticate(Context context) {
        if (PcShellPrefs.isBypassForTests()) return true;
        int code = BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return code == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static String unavailableMessage(Context context) {
        int code = BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        switch (code) {
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "Aucune empreinte enregistrée — configure la biométrie dans les paramètres Android.";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "Capteur biométrique indisponible sur cet appareil.";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "Capteur biométrique temporairement indisponible.";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return "Mise à jour de sécurité requise avant d'utiliser la biométrie.";
            default:
                return "Authentification biométrique indisponible (code " + code + ").";
        }
    }

    public static void authenticate(FragmentActivity activity, Callback callback) {
        if (PcShellPrefs.isBypassForTests()) {
            PcShellSession.unlock();
            PcShellKeyCache.unlock(activity);
            callback.onUnlocked();
            return;
        }
        if (!canAuthenticate(activity)) {
            callback.onFailed(unavailableMessage(activity));
            return;
        }
        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        PcShellSession.unlock();
                        try {
                            PcShellKeyCache.unlock(activity);
                            callback.onUnlocked();
                        } catch (Exception e) {
                            callback.onFailed("Impossible de déverrouiller la clé SSH : "
                                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_CANCELED) {
                            callback.onCancelled();
                        } else {
                            callback.onFailed(errString.toString());
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Tentative refusée — BiometricPrompt reste ouvert.
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Terminal PC")
                .setSubtitle("Authentification requise pour accéder au shell distant")
                .setDescription("Empreinte ou reconnaissance faciale — session 30 min")
                .setNegativeButtonText("Annuler")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        prompt.authenticate(info);
    }
}
