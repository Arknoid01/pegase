package com.pegasuscorp.orbe;

import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.tools.ToolOrchestrator;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.chat.CloudModelStore;
import com.pegasuscorp.orbe.chat.ConversationManager;

/**
 * Écran de test texte pour discuter avec Pégase (phase 1 LLM local).
 */
public class ChatTestActivity extends AppCompatActivity {

    private TextView transcriptView;
    private EditText inputField;
    private Button sendButton;
    private ConversationManager conversation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F00B0E14"));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Discussion Pégase (texte)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        root.addView(title);

        TextView modelInfo = new TextView(this);
        modelInfo.setTextColor(Color.parseColor("#88FFFFFF"));
        modelInfo.setTextSize(12);
        modelInfo.setPadding(0, (int) (4 * density), 0, (int) (8 * density));
        root.addView(modelInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        transcriptView = new TextView(this);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setTextSize(15);
        transcriptView.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(transcriptView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, pad, 0, 0);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(inputRow, rowLp);

        inputField = new EditText(this);
        inputField.setHint("Écris à Pégase…");
        inputField.setHintTextColor(Color.parseColor("#55FFFFFF"));
        inputField.setTextColor(Color.WHITE);
        inputField.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        inputField.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputRow.addView(inputField, inputLp);

        sendButton = new Button(this);
        sendButton.setText("Envoyer");
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(pad, bars.top + pad, pad, bars.bottom + pad);
            return insets;
        });

        setContentView(root);

        conversation = new ConversationManager(this, ChatBackendFactory.create(this));

        updateModelInfo(modelInfo);
        appendLine("Pégase", "Salut Yannick. Écris-moi.");

        sendButton.setOnClickListener(v -> sendMessage());
        inputField.setOnEditorActionListener((tv, actionId, event) -> {
            sendMessage();
            return true;
        });
    }

    private void updateModelInfo(TextView label) {
        label.setText("Actif : " + CloudModelStore.displayNameForActive(this));
    }

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;
        inputField.setText("");
        appendLine("Yannick", text);
        sendButton.setEnabled(false);

        if (!conversation.isActive()) conversation.enter();

        conversation.send(text, new ChatBackend.OnReply() {
            @Override
            public void onReply(String reply) {
                ToolOrchestrator orchestrator = new ToolOrchestrator();
                if (orchestrator.handleIfToolCall(ChatTestActivity.this, reply,
                        new ToolOrchestrator.ReplyHandler() {
                            @Override
                            public void onSpokenReply(String text) {
                                appendLine("Pégase", text);
                                sendButton.setEnabled(true);
                            }
                            @Override
                            public void onError(String error) {
                                appendLine("Erreur", error);
                                sendButton.setEnabled(true);
                            }
                        })) {
                    return;
                }
                appendLine("Pégase", reply);
                sendButton.setEnabled(true);
            }

            @Override
            public void onError(String error) {
                appendLine("Erreur", error);
                sendButton.setEnabled(true);
                Toast.makeText(ChatTestActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void appendLine(String speaker, String text) {
        String current = transcriptView.getText().toString();
        if (!current.isEmpty() && !current.endsWith("\n\n")) {
            current += "\n\n";
        }
        transcriptView.setText(current + speaker + " : " + text + "\n");
        transcriptView.post(() -> {
            int scroll = transcriptView.getLayout() != null
                    ? transcriptView.getLayout().getLineTop(transcriptView.getLineCount()) : 0;
            transcriptView.scrollTo(0, scroll);
        });
    }

    @Override
    protected void onDestroy() {
        if (conversation != null && conversation.isActive()) {
            conversation.exit();
        }
        super.onDestroy();
    }
}
