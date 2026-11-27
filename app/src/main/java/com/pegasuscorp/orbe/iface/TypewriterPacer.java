package com.pegasuscorp.orbe.iface;

/**
 * Régulateur d'écriture de la bulle de Pégase : le texte reçu (morceaux irréguliers du modèle,
 * ou réponse entière d'un coup) est dévoilé à cadence régulière, pour que les mots apparaissent
 * toujours de la même façon (demande de Yannick, 2026-11-27).
 *
 * <p>Cadence de base {@link #BASE_CPS} caractères/s. Si le retard dépasse {@link #CATCH_UP_FROM}
 * caractères (longue réponse arrivée d'un bloc), l'écriture accélère doucement jusqu'à
 * {@link #MAX_CPS}, sans jamais tout afficher d'un coup.
 */
public final class TypewriterPacer {

    static final float BASE_CPS = 40f;
    static final float MAX_CPS = 110f;
    static final int CATCH_UP_FROM = 160;

    private String target = "";
    private int shown = 0;
    private float carry = 0f;
    private boolean finalText = false;
    private long lastNanos = 0L;

    /** Nouveau texte complet reçu jusqu'ici (cumulé). */
    public void setTarget(String text) {
        String t = text == null ? "" : text;
        if (!t.startsWith(target.substring(0, shown))) {
            // Le texte a été réécrit (nettoyage) : repartir du plus long préfixe commun.
            int n = 0, max = Math.min(shown, t.length());
            while (n < max && t.charAt(n) == target.charAt(n)) n++;
            shown = n;
        }
        target = t;
    }

    /** Plus rien n'arrivera : finir d'écrire puis s'arrêter. */
    public void markFinal(String finalTextValue) {
        if (finalTextValue != null) setTarget(finalTextValue);
        finalText = true;
    }

    public boolean isActive() {
        return !target.isEmpty() && !(finalText && shown >= target.length());
    }

    public boolean isFinal() {
        return finalText;
    }

    /** Fin d'écriture atteinte (réponse finale entièrement affichée). */
    public boolean isDone() {
        return finalText && shown >= target.length();
    }

    /** Rien encore affiché : on remet le compteur à zéro (attente de la fin de la réflexion). */
    public void holdStart() {
        if (shown == 0) carry = 0f;
    }

    /** Rattrape tout d'un coup (nouveau message envoyé, onglet quitté). */
    public void flush() {
        shown = target.length();
    }

    public String target() {
        return target;
    }

    public String shown() {
        return target.substring(0, shown);
    }

    public void reset() {
        target = "";
        shown = 0;
        carry = 0f;
        finalText = false;
        lastNanos = 0L;
    }

    /** Avance l'écriture. @return vrai si le texte affiché a changé. */
    public boolean tick(long nowNanos) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos;
            return false;
        }
        float dt = Math.min(0.1f, (nowNanos - lastNanos) / 1e9f);   // pas de bond après une pause
        lastNanos = nowNanos;
        int backlog = target.length() - shown;
        if (backlog <= 0) {
            carry = 0f;
            return false;
        }
        float cps = BASE_CPS;
        if (backlog > CATCH_UP_FROM) {
            cps = Math.min(MAX_CPS, BASE_CPS + (backlog - CATCH_UP_FROM) * 0.5f);
        }
        carry += cps * dt;
        int n = (int) carry;
        if (n <= 0) return false;
        carry -= n;
        int next = Math.min(target.length(), shown + n);
        // Ne pas couper un émoji (paire de substitution) en deux.
        if (next < target.length() && Character.isHighSurrogate(target.charAt(next - 1))) next++;
        shown = next;
        return true;
    }
}
