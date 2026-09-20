package com.pegasuscorp.orbe.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TraceExternalToolTest {

    @Test
    public void outilsMcp_sontExternes_pasLesAutres() {
        assertTrue(Trace.isExternalTool("mcp_notes_chercher"));
        assertFalse(Trace.isExternalTool("weather"));
        assertFalse(Trace.isExternalTool(null));
    }

    @Test
    public void paramKeysOnly_neContientJamaisLesValeurs() throws Exception {
        String out = Trace.paramKeysOnly(new JSONObject().put("query", "mon secret")
                .put("token", "abc123"));
        assertFalse(out.contains("mon secret"));
        assertFalse(out.contains("abc123"));
        assertTrue(out.contains("query") && out.contains("token"));
        assertEquals("{}", Trace.paramKeysOnly(null));
    }
}
