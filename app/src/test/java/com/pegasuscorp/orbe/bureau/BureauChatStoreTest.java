package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BureauChatStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    @Test
    public void append_load_isolatedPerDoc() {
        BureauChatStore.clear(ctx, "plan-a.md");
        BureauChatStore.clear(ctx, "plan-b.md");
        BureauChatStore.append(ctx, "plan-a.md", true, "Salut A");
        BureauChatStore.append(ctx, "plan-a.md", false, "Réponse A");
        BureauChatStore.append(ctx, "plan-b.md", true, "Salut B");

        List<BureauChatStore.Turn> a = BureauChatStore.load(ctx, "plan-a.md");
        List<BureauChatStore.Turn> b = BureauChatStore.load(ctx, "plan-b.md");
        assertEquals(2, a.size());
        assertEquals(1, b.size());
        assertTrue(a.get(0).fromUser);
        assertEquals("Salut A", a.get(0).text);
        assertEquals("Salut B", b.get(0).text);
    }

    @Test
    public void docIdFor_sanitizes() {
        assertEquals("session-2026-07-19", BureauChatStore.docIdFor("session-2026-07-19.md"));
        assertFalse(BureauChatStore.docIdFor("weird name!.md").contains("!"));
    }
}
