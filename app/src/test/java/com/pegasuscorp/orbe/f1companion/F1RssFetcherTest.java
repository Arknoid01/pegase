package com.pegasuscorp.orbe.f1companion;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class F1RssFetcherTest {

    @Test
    public void parseRss_extractsItems() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<rss version=\"2.0\"><channel><title>Test</title>"
                + "<item><title>McLaren reveals upgrade</title>"
                + "<link>https://example.com/mcl</link>"
                + "<guid>99</guid>"
                + "<description><![CDATA[<p>Big floor change</p>]]></description>"
                + "</item>"
                + "<item><title>Williams hire engineer</title>"
                + "<link>https://example.com/wil</link>"
                + "<guid>100</guid>"
                + "<description>Pit wall</description>"
                + "</item>"
                + "</channel></rss>";
        List<F1RssItem> items = F1RssFetcher.parseRss(xml, "Autosport");
        assertEquals(2, items.size());
        assertEquals("McLaren reveals upgrade", items.get(0).title);
        assertEquals("99", items.get(0).guid);
        assertEquals("https://example.com/mcl", items.get(0).link);
        assertTrue(items.get(0).description.contains("Big floor"));
        assertFalse(items.get(0).description.contains("<p>"));
    }

    @Test
    public void parseRss_rejectsHtmlPage() throws Exception {
        List<F1RssItem> items = F1RssFetcher.parseRss(
                "<!DOCTYPE html><html><body>no</body></html>", "F1");
        assertTrue(items.isEmpty());
    }

    @Test
    public void heuristic_prefixesTeamWhenMissing() {
        F1RssItem item = new F1RssItem("1", "Harsh penalty after lap-one clash", "", "", "x");
        String s = F1NewsSummarizer.heuristic(item, "Ferrari");
        assertTrue(s.startsWith("Ferrari — "));
    }
}
