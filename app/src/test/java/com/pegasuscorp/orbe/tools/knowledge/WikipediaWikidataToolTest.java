package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class WikipediaWikidataToolTest {

    @After
    public void tearDown() {
        WikiHttp.setFetcherForTests(null);
    }

    @Test
    public void wikipedia_searchThenSummary() throws Exception {
        WikiHttp.setFetcherForTests(url -> {
            if (url.contains("/search/page")) {
                return "{\"pages\":[{\"key\":\"Coefficient_de_restitution\","
                        + "\"title\":\"Coefficient de restitution\"}]}";
            }
            if (url.contains("/page/summary/")) {
                assertTrue(url.contains("Coefficient_de_restitution")
                        || url.contains("Coefficient"));
                return "{\"title\":\"Coefficient de restitution\","
                        + "\"extract\":\"En physique, le coefficient de restitution "
                        + "mesure l'élasticité d'une collision.\"}";
            }
            fail("URL inattendue : " + url);
            return "{}";
        });
        String out = WikipediaTool.fetchSummary("coefficient de restitution", "fr");
        assertTrue(out.contains("Wikipedia"));
        assertTrue(out.contains("Coefficient de restitution"));
        assertTrue(out.contains("élasticité") || out.contains("elasticite")
                || out.contains("collision"));
    }

    @Test
    public void wikipedia_emptySearch() throws Exception {
        WikiHttp.setFetcherForTests(url -> "{\"pages\":[]}");
        String out = WikipediaTool.fetchSummary("zzzqqqinexistant", "fr");
        assertTrue(out.contains("rien trouvé") || out.contains("Wikipedia"));
    }

    @Test
    public void wikidata_searchEntities() throws Exception {
        WikiHttp.setFetcherForTests(url -> {
            assertTrue(url.contains("wbsearchentities"));
            return "{\"search\":[{\"id\":\"Q8811\",\"label\":\"HTML\","
                    + "\"description\":\"langage de balisage\"}]}";
        });
        String out = WikidataTool.fetchEntity("HTML", "fr");
        assertTrue(out.contains("Wikidata"));
        assertTrue(out.contains("HTML"));
        assertTrue(out.contains("Q8811"));
        assertTrue(out.contains("balisage"));
    }

    @Test
    public void encodeWikiTitle_handlesParens() {
        String enc = WikiHttp.encodeWikiTitle("Balle (physique)");
        assertTrue(enc.contains("Balle"));
        assertTrue(enc.contains("%28") || enc.contains("("));
        assertFalse(enc.contains(" "));
    }

    @Test
    public void registry_hasWikiTools() {
        ToolRegistry reg = new ToolRegistry();
        assertNotNull(reg.findById("wikipedia"));
        assertNotNull(reg.findById("wikidata"));
        assertEquals(ToolTag.WIKIPEDIA, reg.findById("wikipedia").tag());
        assertEquals(ToolTag.WIKIDATA, reg.findById("wikidata").tag());
    }
}
