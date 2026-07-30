package com.pegasuscorp.orbe.orion.prompt;

/**
 * Glossaire Yann — expressions naturelles → intention technique.
 * Toujours passer un {@link OrionMode} (pas de no-arg silencieux).
 */
public final class YannGlossary {

    private YannGlossary() {}

    public static String forPrompt(OrionMode mode) {
        OrionMode m = mode != null ? mode : OrionMode.PATCH;
        StringBuilder sb = new StringBuilder();
        sb.append("=== Glossaire Yann ===\n")
                .append("- « regarde un peu / regarde aussi » → trouver LE problème principal, "
                        + "corriger uniquement celui-ci.\n")
                .append("- « refais-moi ça / refais » → peu de changements, pas de réécriture complète.\n")
                .append("- « un peu bizarre / pas top » → UX/UI ciblée, pas redesign global.\n")
                .append("- « j'ai un lag / ça lag » → perf / focus / cycle de vie ; pas de délai artificiel.\n");
        if (m == OrionMode.FEATURE) {
            sb.append("- « feature / ajoute une feature » → implémenter la fonctionnalité demandée "
                    + "(et ce qui est prévu dans la spec / documents chargés).\n");
        } else {
            sb.append("- « plus de X / ajoute / augmente » → patch minimal, rien d'autre.\n");
        }
        sb.append("- « enlève / vire / supprime » → retirer uniquement l'élément nommé.\n")
                .append("- « c'est cassé » → bugfix ciblé");
        if (m == OrionMode.PATCH) {
            sb.append(", pas de nouvelle fonctionnalité");
        }
        sb.append(".\n")
                .append("- « fais au mieux » → choisir l'interprétation la plus sûre");
        if (m == OrionMode.PATCH) {
            sb.append(" (patch minimal, préserver perfs)");
        }
        sb.append(", documenter les hypothèses.\n");
        return sb.toString();
    }

    public static String permanentConstraints(OrionMode mode) {
        OrionMode m = mode != null ? mode : OrionMode.PATCH;
        StringBuilder sb = new StringBuilder();
        sb.append("=== Contraintes permanentes ===\n")
                .append("- Aucun refactoring non demandé.\n")
                .append("- Aucune nouvelle fonctionnalité non demandée.\n")
                .append("- Modifier uniquement les fichiers nécessaires.\n")
                .append("- Conserver architecture, noms, commentaires utiles.\n")
                .append("- Ne pas casser tests / build.\n");
        if (m == OrionMode.FEATURE) {
            sb.append("- MODE FEATURE : étendre le projet pour la fonctionnalité demandée ; "
                    + "plusieurs fichiers OK ; s'aligner sur la spec / documents chargés ; "
                    + "n'invente rien hors demande.\n");
        } else {
            sb.append("- Projet local présent + retouche → MODE PATCH (ne pas tout réécrire).\n");
        }
        sb.append("- Exception MODE GREENFIELD / première tâche Bureau : création d'un slice "
                + "autorisée (plusieurs petits fichiers OK).\n")
                .append("- Langage par défaut : HTML + CSS + JS. Java/Kotlin/Android seulement "
                        + "si demandé explicitement.\n");
        return sb.toString();
    }
}
