package com.pegasuscorp.orbe.voice;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.pegasuscorp.orbe.ApiSettingsActivity;
import com.pegasuscorp.orbe.GestureHintsStore;
import com.pegasuscorp.orbe.InPlaceVoiceActivity;
import com.pegasuscorp.orbe.FloatingOrbService;
import com.pegasuscorp.orbe.PegaseInterfaceState;
import com.pegasuscorp.orbe.PegaseWakeService;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatSpokenErrors;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.contextstore.ContextEditor;
import com.pegasuscorp.orbe.conversation.InteractionStateStore;
import com.pegasuscorp.orbe.diag.CorrectionsEditor;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.llm.LlmEngineManager;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.memory.MemoryEditor;
import com.pegasuscorp.orbe.notepad.NotepadEditor;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.session.SessionObserver;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.knowledge.NasaImageHelper;
import com.pegasuscorp.orbe.tools.knowledge.NasaReplyHelper;
import com.pegasuscorp.orbe.ui.OrbUiController;

import java.util.concurrent.ExecutorService;

/**
 * Orchestration voix-IN : transcripts, chat mode, outils directs, reprise micro.
 * Wrap {@link VoiceManager} — ne crée pas de SpeechRecognizer.
 */
public final class VoiceInputHandler {

    private static final long GREETING_SPEAK_DELAY_MS = 1200;
    private static final long GREETING_LISTEN_RESUME_MS = 1200;

    /**
     * Hooks Activity pour les bits non déplaçables (intents launcher, lock UI, mic).
     */
    public interface VoiceInputCallback {
        boolean isActivityAlive();

        void runOnUiThread(Runnable action);

        void startActivity(Intent intent);

        void showToast(String message, int length);

        /** Demande RECORD_AUDIO si besoin ; true si déjà accordée. */
        boolean ensureMic();

        void applyLockScreenUi();

        void executeLauncherCommand(IntentParser.Command cmd, String rawText);

        void openBureau();

        void openAppDrawer();

        ExecutorService importExecutor();

        /** Annule le déchargement LLM idle avant un chargement à la demande. */
        void cancelLlmIdleUnload();
    }

    private final Activity activity;
    private final VoiceManager voiceManager;
    private final VoiceOutputHandler output;
    private final OrbUiController orbUi;
    private final VoiceInputCallback callback;
    private final Handler mainHandler;

    private PegaseSession pegaseSession;
    private ConversationManager conversation;
    private IntentParser intentParser;
    private MemoryEditor memoryEditor;
    private NotepadEditor notepadEditor;
    private CorrectionsEditor correctionsEditor;
    private ContextEditor contextEditor;
    private SpeechRulesEditor speechRulesEditor;

    private boolean isFirstLocalExchange = true;
    private boolean lockedChatMode = false;
    private boolean speakerVerifiedSession = false;
    private boolean pendingEnterChatAfterMic = false;
    private int chatRequestId = 0;
    private Runnable resumeChatListeningRunnable;

    public VoiceInputHandler(Activity activity,
                             VoiceManager voiceManager,
                             VoiceOutputHandler output,
                             OrbUiController orbUi,
                             VoiceInputCallback callback) {
        this.activity = activity;
        this.voiceManager = voiceManager;
        this.output = output;
        this.orbUi = orbUi;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void bind(PegaseSession pegaseSession,
                     ConversationManager conversation,
                     IntentParser intentParser,
                     MemoryEditor memoryEditor,
                     NotepadEditor notepadEditor,
                     CorrectionsEditor correctionsEditor,
                     ContextEditor contextEditor,
                     SpeechRulesEditor speechRulesEditor) {
        this.pegaseSession = pegaseSession;
        this.conversation = conversation;
        this.intentParser = intentParser;
        this.memoryEditor = memoryEditor;
        this.notepadEditor = notepadEditor;
        this.correctionsEditor = correctionsEditor;
        this.contextEditor = contextEditor;
        this.speechRulesEditor = speechRulesEditor;
    }

    public void setConversation(ConversationManager conversation) {
        this.conversation = conversation;
    }

    public ConversationManager getConversation() {
        return conversation;
    }

    public boolean isConversationActive() {
        return conversation != null && conversation.isActive();
    }

    public boolean isPendingEnterChatAfterMic() {
        return pendingEnterChatAfterMic;
    }

    public void clearPendingEnterChatAfterMic() {
        pendingEnterChatAfterMic = false;
    }

    public void attachVoiceHost() {
        if (voiceManager != null) {
            voiceManager.attachHost(activity);
            voiceManager.setOnListenFailed(this::onVoiceListenFailed);
            voiceManager.setOnListeningStateListener(active -> {
                if (orbUi != null) orbUi.setListening(active);
            });
        }
    }

    private void onVoiceListenFailed() {
        // Bureau gère ses propres retries via ChatVoiceBridge.
    }

    /** Entrée micro unique (launcher ou interface). */
    public void handleVoiceTranscript(String transcript) {
        if (conversation != null && conversation.isActive()) {
            handleChatInput(transcript);
            return;
        }
        handleLauncherVoiceCommand(transcript);
    }

    public void onChatTranscript(String transcript) {
        handleVoiceTranscript(transcript);
    }

    private void handleLauncherVoiceCommand(String rawTranscript) {
        String t = rawTranscript.toLowerCase();
        if (t.contains("mode discussion") || t.contains("discuter")) {
            enterChatMode();
            return;
        }
        IntentParser.Command cmd = intentParser.parse(rawTranscript);
        callback.executeLauncherCommand(cmd, rawTranscript);
    }

    public void deliverPendingTranscriptIfAny(Intent intent) {
        if (intent == null) return;
        String pending = intent.getStringExtra(ChatVoiceBridge.EXTRA_PENDING_TRANSCRIPT);
        if (pending == null || pending.isEmpty()) return;
        intent.removeExtra(ChatVoiceBridge.EXTRA_PENDING_TRANSCRIPT);
        if (conversation != null && conversation.isActive()) {
            mainHandler.postDelayed(() -> handleChatInput(pending), 500);
        }
    }

    public void enterChatMode() {
        GestureHintsStore.markDiscovered(activity, GestureHintsStore.HINT_VOICE);
        if (orbUi != null) orbUi.hideGestureHintNow();
        if (VoiceMuteStore.isMuted(activity)) {
            callback.showToast("Micro coupé — réactive-le dans le tiroir", Toast.LENGTH_SHORT);
            return;
        }
        try {
            if (!callback.ensureMic()) {
                pendingEnterChatAfterMic = true;
                PegaseWakeService.resume(activity);
                return;
            }
            pendingEnterChatAfterMic = false;
            if (SpeakerVerifyGate.isRequired(activity) && !speakerVerifiedSession) {
                if (orbUi != null) orbUi.deployWings();
                PegaseWakeService.pause(activity);
                output.speak("Dis Pégase pour confirmer.", () ->
                        SpeakerVerifyGate.runAfterPrompt(activity, this::pauseMicForSpeakerCapture,
                                speakerGateCallback(
                                        () -> {
                                            speakerVerifiedSession = true;
                                            enterChatModeInternal();
                                        })));
                return;
            }
            enterChatModeInternal();
        } catch (Exception e) {
            android.util.Log.e("VoiceInputHandler", "enterChatMode", e);
            callback.showToast("Impossible de lancer Pégase : " + e.getMessage(),
                    Toast.LENGTH_LONG);
            PegaseWakeService.resume(activity);
        }
    }

    private void enterChatModeInternal() {
        if (!callback.ensureMic()) {
            PegaseWakeService.resume(activity);
            return;
        }
        ensureHeavyNativesReady();
        lockedChatMode = LockSessionPolicy.isDeviceLocked(activity);
        if (lockedChatMode) {
            callback.applyLockScreenUi();
        }
        if (orbUi != null) orbUi.deployWings();
        PegaseWakeController.setVoiceChatActive(true);
        PegaseWakeService.pause(activity);
        AssistantVolumeGuard.activate(activity);
        if (conversation.isActive()) {
            scheduleListeningResume();
            return;
        }
        conversation.enter();
        callback.importExecutor().execute(() -> {
            try {
                SpeechRulesStore rules = SpeechRulesStore.getInstance(activity);
                rules.warmUp();
                InteractionStateStore state = InteractionStateStore.getInstance(activity);
                String greeting = state.pickGreeting(
                        com.pegasuscorp.orbe.memory.UserProfileStore.getInstance(activity).getUserName());
                int ruleCount = rules.getCachedRuleCount();
                callback.runOnUiThread(() -> {
                    if (!callback.isActivityAlive()) return;
                    if (ruleCount > 0) {
                        android.util.Log.d("SpeechRules",
                                "Dictionnaire chargé en RAM : " + ruleCount + " règles");
                    }
                    if (voiceManager != null) {
                        output.speak(greeting, this::scheduleListeningResumeAfterGreeting,
                                GREETING_SPEAK_DELAY_MS);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("VoiceInputHandler", "enterChatModeInternal", e);
                callback.runOnUiThread(() -> {
                    if (!callback.isActivityAlive()) return;
                    callback.showToast("Erreur assistant : " + e.getMessage(), Toast.LENGTH_LONG);
                    PegaseWakeService.resume(activity);
                });
            }
        });
    }

    private void handleChatInput(String rawTranscript) {
        VoiceSessionContext session = VoiceSessionContext.get();
        boolean correctionContext = session.isAwaitingCorrection();
        String transcript = SpeechInputNormalizer.normalize(activity, rawTranscript);
        if (SpeechInputNormalizer.changedMeaningfully(rawTranscript, transcript)) {
            android.util.Log.d("VoiceInput", rawTranscript + " → " + transcript);
            if (correctionContext) {
                callback.showToast("J'ai compris : " + transcript, Toast.LENGTH_SHORT);
            }
        }
        session.onUserMessage(transcript);

        String inlineCorrection = VoiceCorrectionStore.extractCorrectionPhrase(transcript);
        if (inlineCorrection != null) {
            String rejected = session.getLastRejectedHeard();
            if (rejected == null || rejected.isEmpty()) rejected = rawTranscript;
            VoiceCorrectionStore.getInstance(activity).learn(rejected, inlineCorrection, null);
            transcript = SpeechInputNormalizer.normalize(activity, inlineCorrection);
            session.clearAwaitingCorrection();
            callback.showToast("Correction mémorisée", Toast.LENGTH_SHORT);
        } else if (session.isAwaitingCorrection()) {
            String rejected = session.getLastRejectedHeard();
            if (rejected != null && !rejected.isEmpty()) {
                VoiceCorrectionStore.getInstance(activity).learn(rejected, transcript, null);
                callback.showToast("Correction mémorisée", Toast.LENGTH_SHORT);
            }
            session.clearAwaitingCorrection();
            transcript = SpeechInputNormalizer.normalize(activity, transcript);
        }

        if (Trace.looksLikeStressToggle(transcript)) {
            toggleStressTestMode();
            return;
        }

        if (VoiceHelpHints.isHelpRequest(transcript)) {
            output.speak(VoiceHelpHints.buildHelpMessage(activity), this::scheduleListeningResume);
            return;
        }

        Trace.userMessage(transcript, "voice", lockedChatMode);

        if (com.pegasuscorp.orbe.session.PendingToolConfirm.hasPending()) {
            if (com.pegasuscorp.orbe.session.PendingToolConfirm.tryResolve(transcript)) {
                conversation.recordUserMessage(transcript);
                return;
            }
            String q = com.pegasuscorp.orbe.session.PendingToolConfirm.question();
            if (com.pegasuscorp.orbe.session.PendingToolConfirm.isChoice()) {
                output.speak("Je n'ai pas bien compris. "
                                + (q != null ? q : "Dis le numéro, ou annule."),
                        this::scheduleListeningResume);
            } else {
                output.speak("Je n'ai pas bien compris. "
                                + (q != null ? q : "Confirme ?")
                                + " Dis oui, non, ou annule.",
                        this::scheduleListeningResume);
            }
            return;
        }

        VoiceConfirmation.Pending pending = session.getPendingConfirmation();
        if (pending != null) {
            if (VoiceConfirmation.isCancel(transcript)) {
                session.clearPendingConfirmation();
                output.speak("D'accord, j'annule.", this::scheduleListeningResume);
                return;
            }
            if (VoiceConfirmation.isYesForPending(transcript)) {
                session.clearPendingConfirmation();
                String learnedPhrase = pending.teachUtterance != null && !pending.teachUtterance.isEmpty()
                        ? pending.teachUtterance : pending.userLine;
                if (LearnModeStore.isEnabled(activity)) {
                    VoiceIntentLearnStore.getInstance(activity).recordConfirmation(
                            learnedPhrase, pending.toolJson, pending.intentHint);
                }
                if (pending.teachOnly) {
                    output.speak("C'est noté.", this::scheduleListeningResume);
                    return;
                }
                executeDirectVoiceTool(pending.toolJson, pending.userLine, pending.intentHint);
                return;
            }
            if (VoiceConfirmation.isNo(transcript)) {
                session.clearPendingConfirmation();
                session.markAwaitingCorrection(pending.userLine);
                output.speak("D'accord. Que voulais-tu dire ?",
                        this::scheduleListeningResume);
                return;
            }
            if (VoiceConfirmation.shouldOverridePending(activity, transcript, pending)) {
                session.clearPendingConfirmation();
            } else {
                output.speak("Je n'ai pas bien compris. " + pending.question
                                + " Dis oui, non, ou annule.",
                        this::scheduleListeningResume);
                return;
            }
        }

        VoiceSessionContext.DisambiguationPending disambiguation =
                session.getPendingDisambiguation();
        if (disambiguation != null) {
            if (VoiceConfirmation.isCancel(transcript)) {
                session.clearPendingDisambiguation();
                output.speak("D'accord, j'annule.", this::scheduleListeningResume);
                return;
            }
            VoiceIntentRouter.DisambiguationOption picked =
                    VoiceConfirmation.resolveDisambiguationChoice(
                            transcript, disambiguation.options);
            if (picked != null) {
                session.clearPendingDisambiguation();
                if (LearnModeStore.isEnabled(activity)) {
                    VoiceIntentLearnStore.getInstance(activity).recordDisambiguationChoice(
                            disambiguation.userLine, picked.toolJson, picked.intentHint);
                }
                executeDirectVoiceTool(picked.toolJson, disambiguation.userLine, picked.intentHint);
                return;
            }
            output.speak("Je n'ai pas compris. "
                            + VoiceConfirmation.buildDisambiguationQuestion(disambiguation.options)
                            + " Ou dis annule.",
                    this::scheduleListeningResume);
            return;
        }

        String t = transcript.toLowerCase();
        if (looksLikeOpenApiSettings(t)) {
            callback.startActivity(new Intent(activity, ApiSettingsActivity.class));
            callback.showToast("Réglages API", Toast.LENGTH_SHORT);
            scheduleListeningResume();
            return;
        }
        if (looksLikeOpenNotepad(t)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            PegaseInterfaceState.openNotepad(activity);
            callback.showToast("Bloc-notes ouvert", Toast.LENGTH_SHORT);
            return;
        }
        if (looksLikeOpenInterface(t)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            if (PegaseInterfaceState.isOpen()) {
                PegaseInterfaceState.openOrBringToFront(activity);
                callback.showToast("Interface déjà ouverte", Toast.LENGTH_SHORT);
            } else {
                PegaseInterfaceState.openOrBringToFront(activity);
                callback.showToast("Interface ouverte", Toast.LENGTH_SHORT);
            }
            return;
        }
        if (t.contains("au revoir") || t.startsWith("stop") || t.contains("quitte le mode")) {
            exitChatMode();
            return;
        }
        if (SpeechRulesEditor.looksLikeSpeechRuleEdit(transcript)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            trySpeechRuleEdit(transcript);
            return;
        }
        if (NotepadEditor.looksLikeNotepadEdit(transcript)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            tryNotepadEdit(transcript);
            return;
        }
        if (CorrectionsEditor.looksLikeCorrectionsCommand(transcript)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            tryCorrectionsEdit(transcript);
            return;
        }
        if (ContextEditor.looksLikeContextCommand(transcript)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            tryContextEdit(transcript);
            return;
        }
        if (MemoryEditor.looksLikeMemoryEdit(transcript)) {
            if (!toolsAllowed()) {
                speakUnlockRequired();
                return;
            }
            tryMemoryEdit(transcript);
            return;
        }

        VoiceIntentRouter.RoutedIntent followUp = session.resolveFollowUp(transcript);
        if (followUp != null && followUp.directToolJson != null) {
            maybeConfirmOrExecute(followUp);
            return;
        }

        if (tryTeacherMode(transcript)) {
            return;
        }

        VoiceIntentRouter.RoutedIntent routed = VoiceIntentRouter.analyze(activity, transcript);
        maybeConfirmOrExecute(routed);
    }

    private boolean tryTeacherMode(String transcript) {
        if (!LearnModeStore.isEnabled(activity) || !toolsAllowed()) return false;
        VoiceTeacherParser.TeachRequest teach = VoiceTeacherParser.parse(transcript);
        if (teach == null) return false;

        VoiceIntentRouter.RoutedIntent action =
                VoiceIntentRouter.resolveTeachAction(activity, teach.actionPhrase);
        if (action.directToolJson == null) {
            output.speak(
                    "Je n'ai pas compris l'action à mémoriser. Essaie avec une action plus précise.",
                    this::scheduleListeningResume);
            return true;
        }

        String question = VoiceTeacherParser.buildConfirmQuestion(
                teach, LearnedToolPayload.label(action.directToolJson));
        VoiceSessionContext.get().setPendingConfirmation(new VoiceConfirmation.Pending(
                action.directToolJson,
                teach.actionPhrase,
                action.intentHint,
                question,
                1.0,
                true,
                teach.utterance));
        output.speak(question, this::scheduleListeningResume);
        return true;
    }

    private boolean toolsAllowed() {
        return LockSessionPolicy.allowsTools(activity, lockedChatMode);
    }

    private boolean toolsAllowed(String intentHint, String toolJson) {
        return LockSessionPolicy.allowsTool(activity, lockedChatMode, intentHint, toolJson);
    }

    private void speakUnlockRequired() {
        output.speak(LockSessionPolicy.UNLOCK_TOOL_MESSAGE, this::scheduleListeningResume);
    }

    private void toggleStressTestMode() {
        Trace.setStressTest(!Trace.isStressTest());
        String msg = Trace.isStressTest()
                ? "Mode test activé. Les prochains événements sont marqués stress."
                : "Mode test désactivé. Retour à l'usage réel.";
        callback.showToast(msg, Toast.LENGTH_LONG);
        output.speak(msg, this::scheduleListeningResume);
    }

    public void handleWakeIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("wake_activate", false)) return;
        intent.removeExtra("wake_activate");

        String command = intent.getStringExtra("wake_command");
        if (command == null) command = "";
        command = command.trim();
        if (WakeWordMatcher.containsWakeWord(command)) {
            command = WakeWordMatcher.stripWakePrefix(command);
        }
        final String wakeCommand = command;

        if (intent.getBooleanExtra("wake_speaker_verified", false)) {
            speakerVerifiedSession = true;
            intent.removeExtra("wake_speaker_verified");
        }

        if (SpeakerVerifyGate.isRequired(activity) && !speakerVerifiedSession) {
            PegaseWakeService.pause(activity);
            output.speak("Dis Pégase pour confirmer.", () ->
                    SpeakerVerifyGate.runAfterPrompt(activity, this::pauseMicForSpeakerCapture,
                            speakerGateCallback(
                                    () -> {
                                        speakerVerifiedSession = true;
                                        proceedWakeActivation(wakeCommand);
                                    })));
            return;
        }
        proceedWakeActivation(wakeCommand);
    }

    private void pauseMicForSpeakerCapture() {
        if (voiceManager != null) voiceManager.stopListening();
    }

    private SpeakerVerifyGate.Callback speakerGateCallback(Runnable onVerified) {
        return new SpeakerVerifyGate.Callback() {
            @Override
            public void onVerified() {
                callback.runOnUiThread(onVerified);
            }

            @Override
            public void onRejected() {
                callback.runOnUiThread(() -> {
                    if (voiceManager != null) {
                        output.speak("Je ne te reconnais pas.",
                                () -> PegaseWakeService.resume(activity));
                    } else {
                        PegaseWakeService.resume(activity);
                    }
                });
            }

            @Override
            public void onSkipped() {
                callback.runOnUiThread(onVerified);
            }
        };
    }

    private void proceedWakeActivation(String command) {
        lockedChatMode = LockSessionPolicy.isDeviceLocked(activity);
        if (lockedChatMode) {
            callback.applyLockScreenUi();
        }

        if (!callback.ensureMic()) {
            PegaseWakeService.resume(activity);
            return;
        }
        if (orbUi != null) orbUi.deployWings();
        PegaseWakeController.setVoiceChatActive(true);
        PegaseWakeService.pause(activity);
        AssistantVolumeGuard.activate(activity);

        if (!conversation.isActive()) {
            conversation.enter();
        }

        final String toHandle = command;
        String ack = InteractionStateStore.getInstance(activity).pickWakeAck(lockedChatMode);
        if (LearnModeStore.shouldSpeakIntro(activity)) {
            LearnModeStore.markIntroSpoken(activity);
            ack = LearnModeStore.introMessage() + " " + ack;
        } else if (LearnModeStore.shouldPromptWeeklyReview(activity)) {
            int count = VoiceIntentLearnStore.getInstance(activity).countLearnedThisWeek();
            LearnModeStore.markWeeklyReviewShown(activity);
            ack = ack + " Cette semaine, j'ai appris " + count
                    + " nouvelles formulations. Tu peux les valider dans les réglages.";
        }
        output.speak(ack, () -> {
            if (!toHandle.isEmpty()) {
                handleChatInput(toHandle);
            } else {
                scheduleListeningResumeAfterGreeting();
            }
        });
    }

    private void maybeConfirmOrExecute(VoiceIntentRouter.RoutedIntent routed) {
        if (routed.directToolJson != null && !toolsAllowed(routed.intentHint, routed.directToolJson)) {
            speakUnlockRequired();
            return;
        }
        if (routed.directToolJson == null) {
            if (routed.needsDisambiguation()) {
                String question = VoiceConfirmation.buildDisambiguationQuestion(
                        routed.disambiguationOptions);
                VoiceSessionContext.get().setPendingDisambiguation(
                        new VoiceSessionContext.DisambiguationPending(
                                routed.forLlm, routed.disambiguationOptions));
                output.speak(question, this::scheduleListeningResume);
                return;
            }
            sendChatMessage(routed.forLlm != null ? routed.forLlm : "");
            return;
        }
        if (routed.needsConfirmation) {
            String question = VoiceConfirmation.buildQuestion(activity, routed);
            VoiceSessionContext.get().setPendingConfirmation(new VoiceConfirmation.Pending(
                    routed.directToolJson,
                    routed.forLlm,
                    routed.intentHint,
                    question,
                    routed.confidence,
                    routed.teachOnly,
                    routed.teachUtterance));
            output.speak(question, this::scheduleListeningResume);
            return;
        }
        executeDirectVoiceTool(routed.directToolJson, routed.forLlm, routed.intentHint);
    }

    private void executeDirectVoiceTool(String toolJson, String userLine, String intentHint) {
        if (!toolsAllowed(intentHint, toolJson)) {
            speakUnlockRequired();
            return;
        }
        if (lockedChatMode && LockScreenToolPolicy.requiresSpeakerVerifyOnLock(intentHint, toolJson)) {
            if (!SpeakerVerifyGate.isRequired(activity)) {
                // pas de modèle locuteur — refus discret
                LockScreenNotifier.postAgendaDenied(activity);
                scheduleListeningResume();
                return;
            }
            output.speak("Confirme ta voix pour l'agenda.", () ->
                    SpeakerVerifyGate.runAfterPrompt(activity, this::pauseMicForSpeakerCapture,
                            new SpeakerVerifyGate.Callback() {
                                @Override
                                public void onVerified() {
                                    callback.runOnUiThread(() -> executeDirectVoiceToolInner(
                                            toolJson, userLine, intentHint));
                                }

                                @Override
                                public void onRejected() {
                                    callback.runOnUiThread(() -> {
                                        LockScreenNotifier.postAgendaDenied(activity);
                                        scheduleListeningResume();
                                    });
                                }

                                @Override
                                public void onSkipped() {
                                    onVerified();
                                }
                            }));
            return;
        }
        executeDirectVoiceToolInner(toolJson, userLine, intentHint);
    }

    private void executeDirectVoiceToolInner(String toolJson, String userLine, String intentHint) {
        VoiceSessionContext session = VoiceSessionContext.get();
        String rejected = session.getLastRejectedHeard();
        if (LearnModeStore.isEnabled(activity) && rejected != null && !rejected.isEmpty()) {
            VoiceIntentLearnStore.getInstance(activity).recordCorrection(
                    rejected, userLine, toolJson, intentHint);
            session.clearAwaitingCorrection();
        }

        final int requestId = ++chatRequestId;
        PegaseWakeController.setAssistantThinking(true);
        if (orbUi != null) orbUi.setThinking(true);
        InteractionStateStore interaction = InteractionStateStore.getInstance(activity);
        interaction.onUserMessage(userLine);
        VoiceSessionContext.get().recordExecution(
                new VoiceIntentRouter.RoutedIntent(userLine, toolJson, intentHint, 1.0, false));

        if (!pegaseSession.executeToolFromJson(toolJson, userLine, voiceToolObserver(requestId, interaction))) {
            if (orbUi != null) orbUi.setThinking(false);
            sendChatMessage(userLine);
        }
    }

    private static final class VoiceStreamState {
        boolean started;
    }

    private void feedVoicePartial(int requestId, VoiceStreamState stream, String accumulated) {
        if (requestId != chatRequestId) return;
        if (!VoiceOutputHandler.readyForStreamTts(accumulated)) return;
        if (!stream.started) {
            stream.started = true;
            if (orbUi != null) orbUi.setThinking(false);
            output.beginSpeakStream(null);
        }
        output.feedSpeakStream(accumulated);
    }

    private void deliverVoiceReply(int requestId, VoiceStreamState stream, String text,
            com.pegasuscorp.orbe.conversation.InteractionMood mood, Runnable onComplete) {
        if (requestId != chatRequestId) return;
        if (orbUi != null) orbUi.setThinking(false);
        if (text != null && !text.trim().isEmpty()) {
            VoiceSessionContext.get().onAssistantReply(text);
        }
        if (stream.started) {
            output.endSpeakStream(text, onComplete);
        } else if (text != null && !text.trim().isEmpty()) {
            output.speakWithMood(text, mood, onComplete);
        } else if (onComplete != null) {
            onComplete.run();
        }
    }

    private SessionObserver voiceToolObserver(int requestId, InteractionStateStore interaction) {
        final VoiceStreamState stream = new VoiceStreamState();
        return new SessionObserver() {
            @Override
            public void onReply(String text, boolean toolFired) {
                if (requestId != chatRequestId) return;
                if (toolFired) return;
                if (text == null || text.trim().isEmpty()) return;
                deliverVoiceReply(requestId, stream, text, interaction.getMood(),
                        VoiceInputHandler.this::scheduleListeningResume);
            }

            @Override
            public void onToolResult(ToolResult result) {
                if (requestId != chatRequestId) return;
                if (orbUi != null) orbUi.setThinking(false);
                handleVoiceToolResult(result, interaction);
            }

            @Override
            public void onToolExit(ToolResult result) {
                if (requestId != chatRequestId) return;
                if (orbUi != null) orbUi.setThinking(false);
                handleVoiceToolExit(result);
            }

            @Override
            public void onError(String error) {
                if (requestId != chatRequestId) return;
                if (orbUi != null) orbUi.setThinking(false);
                String userMsg = ChatSpokenErrors.toUserMessage(error);
                conversation.recordToolReply(userMsg);
                output.speak(userMsg, VoiceInputHandler.this::scheduleListeningResume);
            }

            @Override
            public boolean onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                return false;
            }

            @Override
            public boolean onChoiceNeeded(String title, String[] labels,
                    java.util.function.IntConsumer onChosen, Runnable onCancel) {
                return false;
            }

            @Override
            public void onPartial(String accumulated) {
                feedVoicePartial(requestId, stream, accumulated);
            }

            @Override
            public void onToolProgress(String message) {
                if (requestId != chatRequestId) return;
                if (message == null || message.isEmpty()) return;
                callback.runOnUiThread(() -> {
                    if (orbUi != null) orbUi.setThinking(true);
                    callback.showToast(message, Toast.LENGTH_SHORT);
                });
            }
        };
    }

    private void tryNotepadEdit(String transcript) {
        if (orbUi != null) orbUi.setThinking(true);
        notepadEditor.process(transcript, result -> callback.runOnUiThread(() -> {
            if (orbUi != null) orbUi.setThinking(false);
            if (result.fallbackToChat) {
                sendChatMessage(transcript);
                return;
            }
            if (result.success && result.toastMessage != null) {
                callback.showToast("✓ Liste : " + result.toastMessage, Toast.LENGTH_LONG);
            }
            if (result.spokenReply != null && !result.spokenReply.isEmpty()) {
                output.speak(result.spokenReply, this::scheduleListeningResume);
            } else {
                scheduleListeningResume();
            }
        }));
    }

    private void tryCorrectionsEdit(String transcript) {
        if (orbUi != null) orbUi.setThinking(true);
        correctionsEditor.process(transcript, result -> callback.runOnUiThread(() -> {
            if (orbUi != null) orbUi.setThinking(false);
            if (result.fallbackToChat) {
                sendChatMessage(transcript);
                return;
            }
            if (result.success && result.toastMessage != null) {
                callback.showToast("✓ Corrections : " + result.toastMessage, Toast.LENGTH_SHORT);
            }
            if (result.spokenReply != null && !result.spokenReply.isEmpty()) {
                output.speak(result.spokenReply, this::scheduleListeningResume);
            } else {
                scheduleListeningResume();
            }
        }));
    }

    private void tryContextEdit(String transcript) {
        if (orbUi != null) orbUi.setThinking(true);
        contextEditor.process(transcript, result -> callback.runOnUiThread(() -> {
            if (orbUi != null) orbUi.setThinking(false);
            if (result.fallbackToChat) {
                sendChatMessage(transcript);
                return;
            }
            if (result.success && result.toastMessage != null) {
                callback.showToast("✓ Contexte : " + result.toastMessage, Toast.LENGTH_LONG);
            }
            if (result.spokenReply != null && !result.spokenReply.isEmpty()) {
                output.speak(result.spokenReply, this::scheduleListeningResume);
            } else {
                scheduleListeningResume();
            }
        }));
    }

    private void trySpeechRuleEdit(String transcript) {
        if (orbUi != null) orbUi.setThinking(true);
        speechRulesEditor.process(transcript, result -> callback.runOnUiThread(() -> {
            if (orbUi != null) orbUi.setThinking(false);
            if (result.fallbackToChat) {
                sendChatMessage(transcript);
                return;
            }
            if (result.success && result.toastMessage != null) {
                callback.showToast("✓ Règle vocale : " + result.toastMessage, Toast.LENGTH_LONG);
            }
            if (result.spokenReply != null && !result.spokenReply.isEmpty()) {
                output.speak(result.spokenReply, this::scheduleListeningResume);
            } else {
                scheduleListeningResume();
            }
        }));
    }

    private static boolean looksLikeOpenApiSettings(String t) {
        String fold = t.replace("é", "e").replace("è", "e")
                .replace("'", " ").replace("’", " ");
        return fold.contains("cles api") || fold.contains("clés api")
                || fold.contains("reglages api") || fold.contains("réglages api")
                || fold.contains("reglage api") || fold.contains("réglage api")
                || fold.contains("changer le modele") || fold.contains("changer d api")
                || fold.contains("ouvre les api") || fold.contains("montre les api");
    }

    private static boolean looksLikeOpenNotepad(String t) {
        String fold = t.replace("é", "e").replace("è", "e")
                .replace("'", " ").replace("’", " ");
        if (!fold.contains("bloc") && !fold.contains("liste des choses")) return false;
        if (!fold.contains("note") && !fold.contains("liste") && !fold.contains("choses")) {
            return false;
        }
        return fold.contains("montre")
                || fold.contains("affiche")
                || fold.contains("ouvre")
                || fold.contains("peux tu")
                || fold.contains("peut tu")
                || fold.contains("peux-tu")
                || fold.contains("peut-tu");
    }

    private static boolean looksLikeOpenInterface(String t) {
        return t.contains("ouvre ton interface")
                || t.contains("ouvre l'interface")
                || t.contains("ouvre l interface")
                || t.contains("montre ton interface")
                || t.contains("montre l'interface")
                || t.contains("affiche ton interface")
                || t.contains("affiche l'interface")
                || t.contains("affiche la conversation")
                || t.contains("montre la conversation");
    }

    private void tryMemoryEdit(String transcript) {
        if (orbUi != null) orbUi.setThinking(true);
        memoryEditor.process(transcript, result -> callback.runOnUiThread(() -> {
            if (orbUi != null) orbUi.setThinking(false);
            if (result.fallbackToChat) {
                sendChatMessage(transcript);
                return;
            }
            if (result.success && result.toastMessage != null) {
                callback.showToast("✓ " + result.toastMessage, Toast.LENGTH_LONG);
            }
            if (result.spokenReply != null && !result.spokenReply.isEmpty()) {
                output.speak(result.spokenReply, this::scheduleListeningResume);
            } else {
                scheduleListeningResume();
            }
        }));
    }

    private void sendChatMessage(String transcript) {
        final int requestId = ++chatRequestId;
        if (ModelStore.useLocalLlm(activity)
                && !LlmEngineManager.getInstance().getEngine().isModelLoaded()) {
            output.speak("Le modèle est encore en cours de chargement, "
                    + "une petite seconde.", this::scheduleListeningResume);
            return;
        }
        if (ModelStore.useLocalLlm(activity) && isFirstLocalExchange) {
            isFirstLocalExchange = false;
            output.speak("Je réfléchis, ça peut prendre une minute au démarrage.",
                    () -> dispatchChatMessage(requestId, transcript));
            return;
        }
        dispatchChatMessage(requestId, transcript);
    }

    private void dispatchChatMessage(int requestId, String transcript) {
        if (orbUi != null) orbUi.setThinking(true);
        InteractionStateStore interaction = InteractionStateStore.getInstance(activity);
        interaction.onUserMessage(transcript);
        String payload = lockedChatMode
                ? transcript + LockSessionPolicy.LOCKED_LLM_HINT
                : transcript;
        final VoiceStreamState stream = new VoiceStreamState();
        pegaseSession.send(payload, transcript, new SessionObserver() {
            @Override
            public void onPartial(String accumulated) {
                feedVoicePartial(requestId, stream, accumulated);
            }

            @Override
            public void onReply(String text, boolean toolFired) {
                if (requestId != chatRequestId || toolFired) return;
                deliverVoiceReply(requestId, stream, text, interaction.getMood(),
                        VoiceInputHandler.this::scheduleListeningResume);
            }

            @Override
            public void onToolResult(ToolResult result) {
                if (requestId != chatRequestId) return;
                if (orbUi != null) orbUi.setThinking(false);
                handleVoiceToolResult(result, interaction);
            }

            @Override
            public void onToolExit(ToolResult result) {
                if (requestId != chatRequestId) return;
                if (orbUi != null) orbUi.setThinking(false);
                handleVoiceToolExit(result);
            }

            @Override
            public void onToolBlocked() {
                if (requestId != chatRequestId) return;
                if (orbUi != null) orbUi.setThinking(false);
                speakUnlockRequired();
            }

            @Override
            public boolean allowToolExecution() {
                return toolsAllowed();
            }

            @Override
            public void onError(String error) {
                handleChatError(requestId, stream.started, error);
            }
        });
    }

    private void handleChatError(int requestId, boolean fromStream, String error) {
        if (requestId != chatRequestId) return;
        if (orbUi != null) orbUi.setThinking(false);
        isFirstLocalExchange = true;
        if (fromStream) output.stopSpeaking();
        output.speak(error, VoiceInputHandler.this::scheduleListeningResume);
    }

    private void handleVoiceToolResult(ToolResult result, InteractionStateStore interaction) {
        if (result == null) return;

        if (result.kind == ToolResult.Kind.IMAGE_URL
                && result.imageUrl != null && !result.imageUrl.isEmpty()) {
            NasaImageHelper.showImageUrl(activity, result.imageUrl);
            // Synthèse agentique en cours → onReply parlera ; sinon traduire le brief APOD.
            if (pegaseSession == null || !pegaseSession.hasActiveAgenticChain()) {
                speakNasaDescription(result, interaction);
            }
            return;
        }

        VoiceSessionContext.get().onAssistantReply(result.text);
        if (result.kind == ToolResult.Kind.ERROR) {
            output.speak(result.text, this::scheduleListeningResume);
            return;
        }
        output.speakWithMood(result.text, interaction.getMood(), this::scheduleListeningResume);
    }

    /** Voix directe (router local) : pas de synthèse agentique — traduire/résumer en FR. */
    private void speakNasaDescription(ToolResult result, InteractionStateStore interaction) {
        String english = result != null ? result.text : "";
        if (english == null || english.trim().isEmpty()) {
            scheduleListeningResume();
            return;
        }
        boolean looksLikeApodBrief = english.contains("NASA APOD")
                || english.contains("Titre :")
                || english.contains("Explication :");
        if (!looksLikeApodBrief) {
            VoiceSessionContext.get().onAssistantReply(english);
            output.speakWithMood(english, interaction.getMood(), this::scheduleListeningResume);
            return;
        }
        output.speak("Une seconde…", null);
        NasaReplyHelper.translate(activity, english, new NasaReplyHelper.TranslateCallback() {
            @Override
            public void onTranslated(String french) {
                if (french == null || french.trim().isEmpty()) {
                    scheduleListeningResume();
                    return;
                }
                VoiceSessionContext.get().onAssistantReply(french);
                if (conversation != null && conversation.isActive()) {
                    conversation.recordToolReply(french);
                }
                output.speakWithMood(french, interaction.getMood(),
                        VoiceInputHandler.this::scheduleListeningResume);
            }

            @Override
            public void onError(String error) {
                String fallback = extractNasaTitle(english);
                if (fallback.isEmpty()) {
                    fallback = "Voici la photo NASA du jour.";
                }
                VoiceSessionContext.get().onAssistantReply(fallback);
                output.speak(fallback, VoiceInputHandler.this::scheduleListeningResume);
            }
        });
    }

    private static String extractNasaTitle(String brief) {
        if (brief == null) return "";
        int i = brief.indexOf("Titre :");
        if (i < 0) i = brief.indexOf("Titre:");
        if (i < 0) return "";
        int start = brief.indexOf(':', i) + 1;
        int end = brief.indexOf('\n', start);
        String title = (end > start ? brief.substring(start, end) : brief.substring(start)).trim();
        return title.isEmpty() ? "" : "Photo NASA du jour : " + title + ".";
    }

    private void handleVoiceToolExit(ToolResult result) {
        if (result != null && result.text != null && !result.text.trim().isEmpty()) {
            output.speak(result.text, null);
        }
    }

    public void recordToolReplyFromBridge(String reply) {
        if (conversation != null && conversation.isActive()) {
            conversation.recordToolReply(reply);
        }
    }

    public void scheduleListeningResume() {
        scheduleListeningResume(400);
    }

    private void scheduleListeningResumeAfterGreeting() {
        scheduleListeningResume(GREETING_LISTEN_RESUME_MS);
    }

    private void scheduleListeningResume(long delayMs) {
        if (voiceManager == null) return;
        if (VoiceMuteStore.isMuted(activity)) return;
        if (conversation == null || !conversation.isActive()) return;
        if (PegaseInterfaceState.isOpen()) {
            PegaseInterfaceState.requestResumeListening();
            return;
        }
        if (ChatVoiceBridge.isInterfaceTakingMic()) return;
        voiceManager.resumeListeningAfterReply(delayMs);
    }

    public void pauseVoiceForInterface() {
        if (voiceManager != null) {
            voiceManager.cancelScheduledListening();
            voiceManager.stopListening();
        }
    }

    public void resumeVoiceAfterInterface() {
        scheduleListeningResume();
        PegaseWakeController.resumeWakeIfAllowed(activity);
    }

    public boolean isChatActiveForBridge() {
        return ChatSessionRegistry.isActive();
    }

    private void cancelResumeChatListening() {
        if (resumeChatListeningRunnable != null) {
            mainHandler.removeCallbacks(resumeChatListeningRunnable);
            resumeChatListeningRunnable = null;
        }
    }

    public void resumeChatListeningIfNeeded() {
        if (conversation == null || !conversation.isActive() || voiceManager == null) return;
        cancelResumeChatListening();
        resumeChatListeningRunnable = () -> {
            resumeChatListeningRunnable = null;
            if (conversation == null || !conversation.isActive() || voiceManager == null) return;
            if (PegaseInterfaceState.isOpen()) return;
            if (ChatVoiceBridge.isInterfaceTakingMic()) return;
            if (output.isSpeaking()) {
                resumeChatListeningIfNeeded();
                return;
            }
            voiceManager.resumeListeningAfterReply();
        };
        mainHandler.postDelayed(resumeChatListeningRunnable, 300);
    }

    public void exitChatMode() {
        finalizeChatSession(true);
    }

    /** Termine la discussion et persiste la mémoire (idempotent). */
    public void finalizeChatSession(boolean announce) {
        if (conversation == null || !conversation.isActive()) return;
        VoiceSessionContext.get().clear();
        lockedChatMode = false;
        PegaseWakeController.setVoiceChatActive(false);
        if (PegaseWakeController.isInPlaceVoiceActive()) {
            PegaseWakeController.setInPlaceVoiceActive(false);
            FloatingOrbService.hide(activity);
            if (activity instanceof InPlaceVoiceActivity) {
                activity.finish();
            }
        }
        PegaseWakeController.resumeWakeIfAllowed(activity);
        boolean saved = conversation.exit();
        if (orbUi != null) {
            orbUi.setThinking(false);
            orbUi.setListening(false);
        }
        cancelResumeChatListening();
        if (voiceManager != null) {
            voiceManager.cancelScheduledListening();
            voiceManager.stopListening();
            voiceManager.stopSpeaking();
        }
        if (saved) {
            callback.showToast("Mémoire mise à jour", Toast.LENGTH_SHORT);
        }
        if (announce && voiceManager != null) {
            output.speak("À bientôt.", () -> AssistantVolumeGuard.deactivate(activity));
        } else {
            AssistantVolumeGuard.deactivate(activity);
        }
    }

    public void ensureHeavyNativesReady() {
        callback.cancelLlmIdleUnload();
        if (ModelStore.useLocalLlm(activity)
                && !LlmEngineManager.getInstance().getEngine().isModelLoaded()) {
            LlmEngineManager.getInstance().loadActiveModel(activity,
                    new com.pegasuscorp.orbe.llm.LocalLlmEngine.LoadCallback() {
                        @Override public void onLoaded() { /* silencieux */ }
                        @Override public void onError(String error) { /* réglages */ }
                    });
        }
        if (voiceManager != null && PiperModelStore.usePiper(activity)) {
            output.probePiperAsync();
        }
    }

    public void stopListeningForNavigation() {
        if (voiceManager != null) {
            voiceManager.cancelScheduledListening();
            voiceManager.stopListening();
        }
    }

    public void speakUnknownCommand(String rawText) {
        output.speak("Je n'ai pas compris : " + rawText, null);
    }

    public void speakTimerStarted() {
        output.speak("Minuteur lancé.");
    }

    public void speakAppNotFound(String label) {
        output.speak("Application introuvable : " + label);
    }

    public void recreateConversationBackend(PegaseSession session) {
        this.pegaseSession = session;
        boolean wasActive = conversation != null && conversation.isActive();
        if (wasActive) conversation.exit();
        conversation = pegaseSession.recreate(activity);
        if (wasActive) conversation.enter();
    }
}
