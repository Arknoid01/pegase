package com.pegasuscorp.orbe.orion;

import android.content.ClipboardManager;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionPanelTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        OrionStateStore.resetForTests();
        OrionStateStore.get().attach(ctx);
    }

    @After
    public void tearDown() {
        OrionStateStore.resetForTests();
    }

    @Test
    public void observer_notifiedOnStatusChange() {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<OrionStatus> last = new AtomicReference<>();
        OrionStateStore.Observer obs = status -> {
            count.incrementAndGet();
            last.set(status);
        };
        OrionStateStore store = OrionStateStore.get();
        store.addObserver(obs);
        store.markStarting("pod_abc", new GpuOffer("g", "RTX 3090", 24, 0.29f, true));
        store.notifyObserversForTests();
        assertTrue(count.get() >= 1);
        assertEquals(OrionStatus.STARTING, last.get());

        store.markReady();
        store.notifyObserversForTests();
        assertEquals(OrionStatus.READY, last.get());

        store.removeObserver(obs);
    }

    @Test
    public void codeServerUrl_builtFromPodId() {
        assertEquals("https://pod_xyz-8080.proxy.runpod.net",
                OrionStateStore.buildCodeServerUrl("pod_xyz"));
        assertEquals("https://pod_xyz-3000.proxy.runpod.net",
                OrionStateStore.buildFileServerUrl("pod_xyz"));
        assertEquals("https://pod_xyz-8188.proxy.runpod.net",
                OrionStateStore.buildComfyUiUrl("pod_xyz"));
        OrionStateStore store = OrionStateStore.get();
        store.markStarting("pod_xyz", new GpuOffer("g", "RTX", 24, 0.2f, true));
        store.markReady();
        assertEquals("https://pod_xyz-8080.proxy.runpod.net", store.getCodeServerUrl());
        assertEquals("https://pod_xyz-3000.proxy.runpod.net", store.getFileServerUrl());
        assertEquals("https://pod_xyz-8188.proxy.runpod.net", store.getComfyUiUrl());
        assertNull(OrionStateStore.buildCodeServerUrl(null));
        assertNull(OrionStateStore.buildComfyUiUrl(null));
    }

    @Test
    public void copyToClipboard_fillsClipboard() {
        String code = "public final class Demo {}";
        assertTrue(OrionFragment.copyToClipboard(ctx, code));
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cm);
        assertNotNull(cm.getPrimaryClip());
        assertEquals(code, cm.getPrimaryClip().getItemAt(0).coerceToText(ctx).toString());
    }

    @Test
    public void ollamaAndCodeServerUrls_differByPort() {
        String pod = "immediate_pod";
        assertEquals("https://immediate_pod-11435.proxy.runpod.net",
                OrionStateStore.buildOllamaUrl(pod));
        assertEquals("https://immediate_pod-8080.proxy.runpod.net",
                OrionStateStore.buildCodeServerUrl(pod));
    }
}
