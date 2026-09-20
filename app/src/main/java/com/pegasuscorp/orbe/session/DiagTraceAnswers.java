package com.pegasuscorp.orbe.session;

import com.pegasuscorp.orbe.diag.Trace;

/** Réponses locales tirées de la trace de diagnostic, sans passer par le LLM. */
final class DiagTraceAnswers {

    private DiagTraceAnswers() {}

    /** Lit la trace locale : l'outil diag a-t-il été appelé récemment ? */
    static String toolUsage(android.content.Context appContext) {
        try {
            if (appContext != null) Trace.init(appContext);
            java.io.File f = Trace.file();
            if (f == null || !f.isFile()) {
                return "Non — je n'ai pas d'appel récent à l'outil diag dans mes traces. "
                        + "Tu veux que je lance un bilan maintenant ?";
            }
            boolean found = false;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("\"tool\":\"diag\"")
                            || line.contains("\"tool\": \"diag\"")) {
                        if (line.contains("tool_start") || line.contains("tool_end")) {
                            found = true;
                        }
                    }
                }
            }
            if (found) {
                return "Oui — j'ai bien enregistré au moins un appel à l'outil diag dans mes traces. "
                        + "Dis « analyse tes problèmes » ou « bilan de session » pour le détail.";
            }
            return "Non — sur ta dernière demande, je n'ai pas appelé l'outil diag. "
                    + "Je peux le faire maintenant : dis « bilan de session » ou "
                    + "« analyse tes problèmes ».";
        } catch (Exception e) {
            return "Je n'ai pas pu vérifier la trace localement. "
                    + "Dis « bilan de session » pour forcer un diagnostic.";
        }
    }
}
