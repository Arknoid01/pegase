package com.pegasuscorp.orbe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.chat.ChatVoiceBridge;
import com.pegasuscorp.orbe.chat.ConversationManager;
import com.pegasuscorp.orbe.contextstore.ContextEditor;
import com.pegasuscorp.orbe.conversation.ResponseDelivery;
import com.pegasuscorp.orbe.diag.CorrectionsEditor;
import com.pegasuscorp.orbe.memory.MemoryEditor;
import com.pegasuscorp.orbe.notepad.NotepadEditor;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.voice.LocalKeywordParser;
import com.pegasuscorp.orbe.voice.PegaseWakeController;
import com.pegasuscorp.orbe.voice.SpeechRulesEditor;
import com.pegasuscorp.orbe.voice.VoiceInputHandler;
import com.pegasuscorp.orbe.voice.VoiceManager;
import com.pegasuscorp.orbe.voice.VoiceMuteStore;
import com.pegasuscorp.orbe.voice.VoiceOutputHandler;
import com.pegasuscorp.orbe.voice.WakeToSttTrace;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wake in-place (v3 P1) — session vocale par-dessus l'app en cours, sans ramener HOME.
 */
public class InPlaceVoiceActivity extends AppCompatActivity
        implements VoiceInputHandler.VoiceInputCallback {

    private static final int REQ_MIC = 8801;

    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    private VoiceManager voiceManager;
    private VoiceOutputHandler voiceOutput;
    private VoiceInputHandler voiceInput;
    private ResponseDelivery responseDelivery;
    private PegaseSession pegaseSession;
    private ConversationManager conversation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        PegaseWakeController.setInPlaceVoiceActive(true);
        FloatingOrbService.show(this);

        pegaseSession = PegaseSession.get(this);
        conversation = ChatSessionRegistry.get(this);
        responseDelivery = new ResponseDelivery();

        voiceManager = ChatVoiceBridge.getSharedVoice(this);
        voiceOutput = new VoiceOutputHandler(this, voiceManager, responseDelivery);
        voiceInput = new VoiceInputHandler(this, voiceManager, voiceOutput, null, this);
        voiceInput.bind(pegaseSession, conversation,
                new LocalKeywordParser(this),
                new MemoryEditor(this),
                new NotepadEditor(this),
                new CorrectionsEditor(this),
                new ContextEditor(this),
                new SpeechRulesEditor(this));
        voiceInput.attachVoiceHost();
        ChatVoiceBridge.registerInPlace(this, voiceInput);
        VoiceMuteStore.syncController(this);

        WakeToSttTrace.adoptFromIntent(getIntent());
        WakeToSttTrace.mark(this, "wake_ui_opened");
        voiceInput.handleWakeIntent(getIntent());
        // moveTaskToBack après ack TTS (onWakeAckFinished) — pas ici :
        // un retour trop tôt + destroy OEM / recreate MainActivity coupait la phrase.
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        WakeToSttTrace.adoptFromIntent(intent);
        if (voiceInput != null) voiceInput.handleWakeIntent(intent);
    }

    @Override
    public void onWakeAckFinished() {
        // Écran verrouillé : rester au premier plan (surface showWhenLocked) —
        // moveTaskToBack derrière le keyguard tuait le STT (ERROR_CLIENT en série).
        if (com.pegasuscorp.orbe.voice.LockSessionPolicy.isDeviceLocked(this)) {
            return;
        }
        if (!isFinishing() && !isDestroyed()) {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        // Ne PAS exitChatMode ici : après moveTaskToBack l'OEM peut détruire
        // l'Activity mid-TTS → conversation.exit + resume wake → STT mort.
        // Clear seulement inPlace ; voiceChatActive reste jusqu'à finalizeChatSession.
        ChatVoiceBridge.unregisterInPlace(this);
        PegaseWakeController.setInPlaceVoiceActive(false);
        ChatVoiceBridge.releaseSharedVoiceIfIdle();
        importExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (voiceInput != null && voiceInput.isConversationActive()) {
            voiceInput.exitChatMode();
            finish();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    // runOnUiThread / startActivity : fournis par Activity (satisfont VoiceInputCallback)

    @Override
    public void showToast(String message, int length) {
        Toast.makeText(this, message, length).show();
    }

    @Override
    public boolean ensureMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        return false;
    }

    @Override
    public void applyLockScreenUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    @Override
    public void executeLauncherCommand(com.pegasuscorp.orbe.voice.IntentParser.Command cmd,
            String rawText) {
        PegaseInterfaceActivity.open(this);
    }

    @Override
    public void openBureau() {
        startActivity(new Intent(this, com.pegasuscorp.orbe.bureau.BureauCanvasActivity.class));
    }

    @Override
    public void openAppDrawer() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public ExecutorService importExecutor() {
        return importExecutor;
    }

    @Override
    public void cancelLlmIdleUnload() {
        // Pas de lifecycle bridge in-place — no-op.
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && voiceInput != null) {
            voiceInput.handleWakeIntent(getIntent());
        }
    }
}
