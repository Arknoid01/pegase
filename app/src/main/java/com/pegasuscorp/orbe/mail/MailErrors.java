package com.pegasuscorp.orbe.mail;

/** Erreurs IMAP traduites pour l'utilisateur. */
public final class MailErrors {

    private MailErrors() {}

    public static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.mail.AuthenticationFailedException) {
                return "adresse ou mot de passe d'application refusé (le code de 16 lettres, "
                        + "pas ton mot de passe Google ; validation en deux étapes requise)";
            }
            if (t instanceof java.net.UnknownHostException) return "pas de connexion internet";
            if (t instanceof java.net.SocketTimeoutException) return "Gmail ne répond pas (délai dépassé)";
            if (t instanceof javax.net.ssl.SSLException) return "connexion sécurisée impossible";
        }
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
