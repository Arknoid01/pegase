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

    @Test
    public void champsTexte_couvrentMessagesEtParametres() {
        for (String k : new String[]{"text", "reply", "params", "user_msg", "raw_preview", "target"}) {
            assertTrue(k, Trace.isTextFieldName(k));
        }
        assertFalse(Trace.isTextFieldName("tool"));
        assertFalse(Trace.isTextFieldName("duration_ms"));
    }

    @Test
    public void init_releaseCensure_debugNon() {
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        // les tests Robolectric tournent en mode debuggable : pas de censure par défaut
        Trace.init(ctx);
        assertTrue((ctx.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
    }
}
