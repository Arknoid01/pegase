package com.pegasuscorp.orbe.tools.device;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneFilesTest {

    @Test
    public void normalizeFolder_aliases() {
        assertEquals("downloads", PhoneFiles.normalizeFolder("Téléchargements"));
        assertEquals("documents", PhoneFiles.normalizeFolder("Documents"));
        assertEquals("dcim", PhoneFiles.normalizeFolder("appareil photo"));
        assertEquals("pictures", PhoneFiles.normalizeFolder("photos"));
    }

    @Test
    public void importantMove_smallDocInDownloads_notImportant() {
        PhoneFiles.Entry e = new PhoneFiles.Entry(
                "note.txt",
                "/storage/emulated/0/Download/note.txt",
                null,
                1200,
                "text/plain",
                "Download/");
        assertFalse(PhoneFiles.isImportantMove(e, "documents"));
    }

    @Test
    public void importantMove_photoOrLarge_isImportant() {
        PhoneFiles.Entry photo = new PhoneFiles.Entry(
                "IMG.jpg",
                "/storage/emulated/0/DCIM/IMG.jpg",
                null,
                500_000,
                "image/jpeg",
                "DCIM/");
        assertTrue(PhoneFiles.isImportantMove(photo, "downloads"));

        PhoneFiles.Entry big = new PhoneFiles.Entry(
                "archive.zip",
                "/storage/emulated/0/Download/archive.zip",
                null,
                5_000_000,
                "application/zip",
                "Download/");
        assertTrue(PhoneFiles.isImportantMove(big, "documents"));
    }
}
