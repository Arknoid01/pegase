package com.pegasuscorp.orbe.orion;

import io.noties.prism4j.annotations.PrismBundle;

/**
 * Grammaires Prism4j pour la coloration Orion (généré : {@code GrammarLocatorOrion}).
 */
@PrismBundle(
        include = {
                "clike", "c", "cpp", "csharp", "java", "kotlin",
                "javascript", "python", "go",
                "json", "markup", "css",
                "sql", "yaml", "markdown", "groovy"
        },
        grammarLocatorClassName = ".GrammarLocatorOrion"
)
interface OrionPrismBundle {
}
