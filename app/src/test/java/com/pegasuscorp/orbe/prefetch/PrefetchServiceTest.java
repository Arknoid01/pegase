package com.pegasuscorp.orbe.prefetch;

import android.content.Context;

import com.pegasuscorp.orbe.diag.Trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PrefetchServiceTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        PrefetchService.skipNetworkForTests = true;
        PrefetchCache.nowOverrideMs = null;
        PrefetchCache.clear(ctx);
        PrefetchService.clearLaunchMarker(ctx);
        Trace.init(ctx);
        Trace.clear(ctx);
        // purge archives
        File dir = Trace.archivesDir();
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    @After
    public void tearDown() {
        PrefetchService.skipNetworkForTests = false;
        PrefetchCache.nowOverrideMs = null;
        PrefetchService.clearLaunchMarker(ctx);
        PrefetchCache.clear(ctx);
    }

    @Test
    public void firstRotation_createsYesterdayArchive() throws Exception {
        File trace = Trace.file();
        assertNotNull(trace);
        try (FileOutputStream out = new FileOutputStream(trace, false)) {
            out.write(("{\"t\":1,\"type\":\"user_message\",\"text\":\"bonjour\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(trace.length() > 0);

        PrefetchService.runBlocking(ctx);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        File archive = Trace.archiveFile(yesterday);
        assertNotNull(archive);
        assertTrue("archive d'hier doit exister après 1ère rotation",
                archive.exists() && archive.length() > 0);
        assertTrue("trace du jour repart vide", Trace.file().length() == 0);
        assertFalse(PrefetchService.isFirstLaunchToday(ctx));
    }

    @Test
    public void secondRunSameDay_doesNotReArchive() throws Exception {
        File trace = Trace.file();
        try (FileOutputStream out = new FileOutputStream(trace, false)) {
            out.write(("{\"t\":1,\"type\":\"user_message\",\"text\":\"a\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        PrefetchService.runBlocking(ctx);
        File archive = Trace.archiveFile(LocalDate.now().minusDays(1));
        long archiveSizeAfterFirst = archive.exists() ? archive.length() : 0;
        assertTrue(archiveSizeAfterFirst > 0);

        // Nouvelle activité du jour (ne doit pas partir en archive)
        try (FileOutputStream out = new FileOutputStream(Trace.file(), false)) {
            out.write(("{\"t\":2,\"type\":\"user_message\",\"text\":\"session du jour\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        long todaySize = Trace.file().length();
        assertTrue(todaySize > 0);

        PrefetchService.runBlocking(ctx);

        assertEquals("2e run du jour ne doit pas fusionner la session courante",
                todaySize, Trace.file().length());
        assertEquals(archiveSizeAfterFirst, archive.length());
    }

    @Test
    public void cacheTtl_expired_returnsNull_thenPutReloads() {
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "soleil");
        assertEquals("soleil", PrefetchCache.get(ctx, PrefetchCache.KEY_WEATHER,
                PrefetchCache.TTL_WEATHER_MS));

        long savedAt = PrefetchCache.now();
        PrefetchCache.nowOverrideMs = savedAt + PrefetchCache.TTL_WEATHER_MS + 1;

        assertNull("TTL expiré → cache vidé logique",
                PrefetchCache.get(ctx, PrefetchCache.KEY_WEATHER, PrefetchCache.TTL_WEATHER_MS));
        assertFalse(PrefetchCache.isFresh(ctx, PrefetchCache.KEY_WEATHER,
                PrefetchCache.TTL_WEATHER_MS));

        // « recharge » : nouveau put après expiry
        PrefetchCache.put(ctx, PrefetchCache.KEY_WEATHER, "orage");
        assertEquals("orage", PrefetchCache.get(ctx, PrefetchCache.KEY_WEATHER,
                PrefetchCache.TTL_WEATHER_MS));
    }

    @Test
    public void prefetchDiag_storesYesterdaySummaryInCache() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        File archive = Trace.archiveFile(yesterday);
        assertNotNull(archive);
        String line = "{\"t\":1,\"type\":\"tool_end\",\"tool\":\"notepad\","
                + "\"ok\":false,\"error\":\"text manquant\"}\n";
        try (FileOutputStream out = new FileOutputStream(archive, false)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }

        PrefetchService.clearLaunchMarker(ctx);
        PrefetchService.runBlocking(ctx);

        String diag = PrefetchCache.get(ctx, PrefetchCache.KEY_DIAG,
                PrefetchCache.TTL_DIAG_SESSION_MS);
        assertNotNull(diag);
        assertFalse(diag.trim().isEmpty());
    }
}
