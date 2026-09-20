package com.pegasuscorp.orbe.sync;

/**
 * Dossier et paires sync Google Drive — doit rester aligné avec
 * {@code /etc/pegase/drive-sync.defaults.env} côté PC.
 *
 * <pre>
 * Pegase/                          ← DRIVE_FOLDER_NAME (casse exacte)
 *   android-&lt;deviceId&gt;/op-*.json   ← export téléphone (SAF)
 *   pc-pegase-arch/op-*.json        ← export PC ; importé par l'app (SAF)
 * </pre>
 *
 * Pas de sous-dossier {@code sync/} intermédiaire.
 */
public final class DriveSyncLayout {

    /** Dossier à choisir dans Google Drive (SAF). */
    public static final String DRIVE_FOLDER_NAME = "Pegase";

    private DriveSyncLayout() {}
}
