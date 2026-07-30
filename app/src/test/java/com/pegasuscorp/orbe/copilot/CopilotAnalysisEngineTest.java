package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class CopilotAnalysisEngineTest {

    @Test
    public void shouldSendToCloud_skipsShortText() {
        assertFalse(CopilotLocaleFilter.needsTranslation("hello"));
    }

    @Test
    public void shouldSendToCloud_detectsCjk() {
        String cjk = "这是一段中文测试文本用于检测语言过滤逻辑是否正常工作";
        assertTrue(CopilotLocaleFilter.needsTranslation(cjk));
    }

    @Test
    public void parseContextName_fromVoice() {
        assertEquals("orion", ShareIngestRouter.parseContextName("ajoute ca a orion"));
        assertEquals("orion", ShareIngestRouter.parseContextName("ajoute ça à orion"));
    }

    @Test
    public void looksLikeRemember() {
        assertTrue(ShareIngestRouter.looksLikeRemember("pegase retiens ca"));
        assertTrue(ShareIngestRouter.looksLikeRemember("retenir ça"));
    }
}
