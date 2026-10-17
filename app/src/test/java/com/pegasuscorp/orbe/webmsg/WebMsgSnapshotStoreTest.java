package com.pegasuscorp.orbe.webmsg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class WebMsgSnapshotStoreTest {

    @Test
    public void parseChats_listeValide() {
        List<WebMsgSnapshotStore.Chat> c = WebMsgSnapshotStore.parseChats(
                "[{\"name\":\"On the road\",\"time\":\"20:42\",\"last\":\"Sarah: Ouii\",\"unread\":9,\"group\":true},"
                        + "{\"name\":\"\",\"unread\":1}]");
        assertEquals(1, c.size());
        assertEquals(9, c.get(0).unread);
        org.junit.Assert.assertTrue(c.get(0).group);
    }

    @Test
    public void parseChats_pageDeConnexion() {
        assertNull(WebMsgSnapshotStore.parseChats("NO_LIST"));
        assertNull(WebMsgSnapshotStore.parseChats(null));
    }

    @Test
    public void unquote_chaineJson() {
        assertEquals("[1]", WebMsgScrapers.unquote("\"[1]\""));
        assertNull(WebMsgScrapers.unquote("null"));
    }
}
