package com.pegasuscorp.orbe.fs;

import android.content.Context;

import java.io.File;

/**
 * Point d'accès unique au stockage local Pégase sous {@code files/}.
 */
public final class PegaseFileSystem {

    private static PegaseFileSystem instance;

    private final File root;

    private PegaseFileSystem(Context context) {
        root = context.getApplicationContext().getFilesDir();
    }

    public static synchronized PegaseFileSystem get(Context context) {
        if (instance == null) {
            instance = new PegaseFileSystem(context.getApplicationContext());
        }
        return instance;
    }

    /** Visible tests. */
    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public File root() {
        return root;
    }

    public File dir(String name) {
        File d = new File(root, name);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** {@code files/routines/} */
    public File routinesDir() {
        return dir("routines");
    }

    /** {@code files/routines/routines.json} */
    public File routinesJson() {
        return new File(routinesDir(), "routines.json");
    }

    public File contextsDir() {
        return dir("contexts");
    }

    public File bureauDir() {
        return dir("bureau");
    }

    public File diagDir() {
        return dir("diag");
    }

    /** {@code files/diag/corrections.md} — backlog corrections Pégase. */
    public File correctionsMd() {
        return new File(diagDir(), "corrections.md");
    }

    /** {@code files/routing/} */
    public File routingDir() {
        return dir("routing");
    }

    /** {@code files/routing/examples.json} — phrase → outil (UserExamples). */
    public File routingExamplesJson() {
        return new File(routingDir(), "examples.json");
    }

    /** {@code files/orion/} */
    public File orionDir() {
        return dir("orion");
    }

    /** {@code files/orion/projects/} — workspaces locaux Orion. */
    public File orionProjectsDir() {
        File d = new File(orionDir(), "projects");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }
}
