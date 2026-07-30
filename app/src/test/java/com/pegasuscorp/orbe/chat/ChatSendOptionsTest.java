package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.intentions.PegaseModeStore;
import com.pegasuscorp.orbe.memory.ContextAnalyzer;
import com.pegasuscorp.orbe.memory.IntentDetector;
import com.pegasuscorp.orbe.memory.ContextIntent;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ChatSendOptionsTest {

    @Test
    public void raconteHistoire_story_1500tokens() {
        ContextIntent intent = ContextAnalyzer.analyze(
                RuntimeEnvironment.getApplication(), "Raconte une histoire");
        assertEquals("story", intent.intent);
        int tokens = ChatSendOptions.forText(intent.allowedTools)
                .withIntent(intent)
                .replyMaxTokens();
        assertEquals(1500, tokens);
    }

    @Test
    public void tuPensesQuoi_philosophical_1500tokens() {
        ContextIntent intent = ContextAnalyzer.analyze(
                RuntimeEnvironment.getApplication(), "Tu penses quoi de ça ?");
        assertEquals("philosophical", intent.intent);
        assertEquals(1500, ChatSendOptions.legacy(Channel.TEXT)
                .withIntent(intent)
                .replyMaxTokens());
    }

    @Test
    public void metsAlarme_productivity_300tokens() {
        ContextIntent intent = ContextAnalyzer.analyze(
                RuntimeEnvironment.getApplication(), "Mets une alarme");
        // alarme → productivity ou general avec tools ; budget court
        int tokens = ChatSendOptions.forText(EnumSet.allOf(ToolTag.class))
                .withIntent(intent)
                .replyMaxTokens();
        assertEquals(300, tokens);
    }

    @Test
    public void quelTemps_freshData_300tokens() {
        ContextIntent intent = ContextAnalyzer.analyze(
                RuntimeEnvironment.getApplication(), "Quel temps ?");
        assertEquals("fresh_data", intent.intent);
        assertEquals(300, ChatSendOptions.forText(intent.allowedTools)
                .withIntent(intent)
                .replyMaxTokens());
    }

    @Test
    public void voice_default180_withoutBudget() {
        assertEquals(180, ChatSendOptions.forVoice(EnumSet.allOf(ToolTag.class))
                .withIntentName("story")
                .replyMaxTokens());
    }

    @Test
    public void voice_driveMode_90tokens() {
        Context ctx = RuntimeEnvironment.getApplication();
        PegaseModeStore.setMode(ctx, PegaseModeStore.Mode.DRIVE);
        try {
            assertEquals(90, ChatSendOptions.forVoice(EnumSet.allOf(ToolTag.class))
                    .withIntentName("fresh_data")
                    .withVoiceTokenBudget(ctx)
                    .replyMaxTokens());
        } finally {
            PegaseModeStore.setMode(ctx, PegaseModeStore.Mode.NORMAL);
        }
    }

    @Test
    public void voice_workMode_120tokens() {
        Context ctx = RuntimeEnvironment.getApplication();
        PegaseModeStore.setMode(ctx, PegaseModeStore.Mode.WORK);
        try {
            assertEquals(120, ChatSendOptions.forVoice(EnumSet.allOf(ToolTag.class))
                    .withIntentName("story")
                    .withVoiceTokenBudget(ctx)
                    .replyMaxTokens());
        } finally {
            PegaseModeStore.setMode(ctx, PegaseModeStore.Mode.NORMAL);
        }
    }

    @Test
    public void voice_productivity_140tokens() {
        assertEquals(140, ChatSendOptions.forVoice(EnumSet.allOf(ToolTag.class))
                .withIntentName("productivity")
                .withVoiceTokenBudget(RuntimeEnvironment.getApplication())
                .replyMaxTokens());
    }

    @Test
    public void bureau_1500_orion_2048() {
        assertEquals(1500, ChatSendOptions.legacy(Channel.BUREAU).replyMaxTokens());
        assertEquals(2048, ChatSendOptions.legacy(Channel.ORION).replyMaxTokens());
    }

    @Test
    public void looksLikeCreative_foldForms() {
        String fold = SpeechInputNormalizer.fold("Raconte une histoire").replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeCreative(fold));
        fold = SpeechInputNormalizer.fold("Écris un poème").replace('\'', ' ');
        assertTrue(IntentDetector.looksLikeCreative(fold));
    }
}
