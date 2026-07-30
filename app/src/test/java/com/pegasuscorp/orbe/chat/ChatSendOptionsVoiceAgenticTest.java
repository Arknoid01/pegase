package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.*;

/** Budget voix sur étapes agentiques (synthèse search/wiki). */
public class ChatSendOptionsVoiceAgenticTest {

    @Test
    public void agenticVoiceStep_respectsVoiceBudgetBeforeSynthesisCap() {
        ChatSendOptions opts = ChatSendOptions.agenticStep(
                        EnumSet.allOf(ToolTag.class), false, Channel.VOICE)
                .withIntentName("fresh_data")
                .withVoiceTokenBudget(null);
        assertEquals(180, opts.replyMaxTokens());
        int voiceCap = opts.replyMaxTokens();
        int synthesisCap = Math.min(Math.max(voiceCap, 220), 280);
        assertEquals(220, synthesisCap);
    }

    @Test
    public void agenticVoiceDrive_cappedSynthesis() {
        // Simule drive 90 → synthèse plafonnée à 220 min, 280 max
        ChatSendOptions opts = ChatSendOptions.agenticStep(
                        EnumSet.allOf(ToolTag.class), false, Channel.VOICE)
                .withMaxTokens(90);
        int synthesisCap = Math.min(Math.max(opts.replyMaxTokens(), 220), 280);
        assertEquals(220, synthesisCap);
    }
}
