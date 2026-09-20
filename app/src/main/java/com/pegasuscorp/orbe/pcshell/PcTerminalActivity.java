package com.pegasuscorp.orbe.pcshell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pegasuscorp.orbe.R;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal PC Phase 1.5 — scrollback, saisie libre, raccourcis via bouton +.
 */
public class PcTerminalActivity extends PcShellProtectedActivity {

    private static final int C_BG = Color.parseColor("#F00B0E14");
    private static final int C_CYAN = Color.parseColor("#35D0DD");
    private static final int C_MUTED = Color.parseColor("#88FFFFFF");
    private static final int C_OUT = Color.parseColor("#E8E8E8");
    private static final int C_ERR = Color.parseColor("#FF8A80");
    private static final int C_WARN = Color.parseColor("#FFFFAB40");
    private static final int C_CARD = Color.parseColor("#18FFFFFF");
    private static final int C_SEP = Color.parseColor("#22FFFFFF");
    private static final int C_DELETE = Color.parseColor("#FF5252");
    /** Seuil au-delà duquel proposer l'écran « Voir tout » dédié. */
    private static final int VIEW_ALL_THRESHOLD_CHARS = 16 * 1024;

    private LinearLayout inputBar;
    private LinearLayout rootLayout;

    private float density;
    private RecyclerView outputList;
    private LinearLayoutManager outputLayoutManager;
    private PcTerminalOutputAdapter outputAdapter;
    private EditText commandInput;
    private Button runButton;
    private TextView statusLine;
    private PcTerminalScrollback scrollback;
    private PcShellCommandStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private AtomicBoolean cancelFlag;

    private volatile boolean deferredRunRequested;

    @Override
    protected void onBeforeProtectedCreate(Intent intent) {
        PcShellDeepLink.captureAutoRun(this, intent);
    }

    @Override
    protected void onProtectedNewIntent(Intent intent) {
        deferredRunRequested = true;
        runPendingDeferredCommands();
    }

    @Override
    protected void onCreateProtected(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        store = PcShellCommandStore.getInstance(this);
        density = getResources().getDisplayMetrics().density;
        ensureSshKeysSafe();
        store.ensureBuiltin(
                PcShellAutoRunPending.SLUG_MISE_A_JOUR_ARCH,
                "Mise à jour Arch",
                "sudo pacman -Syu",
                PcShellPrefs.PACMAN_TIMEOUT_SEC);
        store.ensureBuiltin(
                "fastfetch-compact",
                "Fastfetch (compact)",
                "fastfetch --logo none");
        scrollback = new PcTerminalScrollback();

        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildToolbar());
        root.addView(buildOutputArea(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        inputBar = buildInputBar();
        root.addView(inputBar);

        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            int imeExtra = Math.max(0, ime.bottom - bars.bottom);
            inputBar.setPadding(dp(10), dp(8), dp(10), dp(10) + imeExtra);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        root.post(() -> ViewCompat.requestApplyInsets(root));

        appendWelcome();
        refreshStatus();
        runPendingDeferredCommands();
    }

    private void runPendingDeferredCommands() {
        if (store == null || running) return;
        String slug = PcShellAutoRunPending.takePendingSlug(this);
        if (slug != null) {
            SavedCommand bySlug = store.getBySlug(slug);
            if (bySlug != null) {
                deferredRunRequested = false;
                runShell(bySlug.command, bySlug.id, bySlug.label, bySlug.timeoutSeconds);
                return;
            }
        }
        String pendingId = PcShellVoicePending.takePendingCommandId();
        if (pendingId == null) {
            deferredRunRequested = false;
            return;
        }
        SavedCommand cmd = store.getById(pendingId);
        deferredRunRequested = false;
        if (cmd == null) return;
        runShell(cmd.command, cmd.id, cmd.label, cmd.timeoutSeconds);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && rootLayout != null) {
            ViewCompat.requestApplyInsets(rootLayout);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isTerminalUiReady()) return;
        refreshStatus();
        if (deferredRunRequested && !running) {
            runPendingDeferredCommands();
        }
    }

    private boolean isTerminalUiReady() {
        return isProtectedContentReady() && statusLine != null && commandInput != null;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Terminal PC");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(iconButton("⚙", v ->
                startActivity(new Intent(this, PcShellSettingsActivity.class))));
        top.addView(spacer(dp(6)));
        top.addView(iconButton("📜", v ->
                startActivity(new Intent(this, PcShellHistoryActivity.class))));
        top.addView(spacer(dp(6)));
        top.addView(iconButton("⎘", v -> copyScrollback()));
        bar.addView(top);

        statusLine = new TextView(this);
        statusLine.setTextColor(C_MUTED);
        statusLine.setTextSize(10);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        statusLine.setPadding(0, dp(4), 0, 0);
        bar.addView(statusLine);
        return bar;
    }

    private View buildOutputArea() {
        outputList = new RecyclerView(this);
        outputList.setBackgroundColor(Color.parseColor("#0A0C10"));
        outputList.setPadding(dp(10), dp(8), dp(10), dp(8));
        outputList.setClipToPadding(false);
        outputList.setItemAnimator(null);
        outputLayoutManager = new LinearLayoutManager(this);
        outputList.setLayoutManager(outputLayoutManager);
        outputAdapter = new PcTerminalOutputAdapter(scrollback);
        outputList.setAdapter(outputAdapter);
        return outputList;
    }

    private LinearLayout buildInputBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(10));
        bar.setBackgroundColor(Color.parseColor("#141820"));

        commandInput = new EditText(this);
        commandInput.setHint("Commande…");
        commandInput.setHintTextColor(C_MUTED);
        commandInput.setTextColor(Color.WHITE);
        commandInput.setTextSize(13);
        commandInput.setTypeface(Typeface.MONOSPACE);
        commandInput.setSingleLine(true);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        commandInput.setBackground(inputBg());
        commandInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitCommand();
                return true;
            }
            return false;
        });
        bar.addView(commandInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        runButton = iconButton("▶", v -> submitCommand());
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runLp.setMargins(dp(6), 0, 0, 0);
        bar.addView(runButton, runLp);

        Button plus = iconButton("+", v -> showCommandPicker());
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plusLp.setMargins(dp(6), 0, 0, 0);
        bar.addView(plus, plusLp);
        return bar;
    }

    private void appendWelcome() {
        appendLine("Terminal PC · SSH via Tailscale", C_MUTED);
        if (PcShellPrefs.isConfigured(this)) {
            appendLine(promptPrefix(), C_CYAN);
        } else {
            appendLine("⚠ Configure le PC dans ⚙ Réglages (hostname + clé SSH).", C_WARN);
        }
        appendLine("Saisis une commande ou appuie sur + pour un raccourci.\n", C_MUTED);
        flushOutput();
    }

    private void refreshStatus() {
        if (!isTerminalUiReady()) return;
        String host = PcShellPrefs.getHost(this);
        if (TextUtils.isEmpty(host)) {
            statusLine.setText("Non configuré");
            statusLine.setTextColor(C_WARN);
        } else if (!PcShellPrefs.hasKeyPair(this)) {
            statusLine.setText("Clé SSH manquante");
            statusLine.setTextColor(C_WARN);
        } else if (running) {
            statusLine.setText("Exécution en cours…");
            statusLine.setTextColor(C_CYAN);
        } else {
            statusLine.setText(PcShellPrefs.getUser(this) + "@" + host
                    + ":" + PcShellPrefs.getPort(this));
            statusLine.setTextColor(C_MUTED);
        }
        setInputEnabled(!running);
    }

    private void setInputEnabled(boolean enabled) {
        if (commandInput == null || runButton == null) return;
        commandInput.setEnabled(enabled);
        runButton.setEnabled(enabled);
        commandInput.setAlpha(enabled ? 1f : 0.5f);
        runButton.setAlpha(enabled ? 1f : 0.5f);
    }

    private String promptPrefix() {
        return PcShellPrefs.getUser(this) + "@" + PcShellPrefs.getHost(this) + "$ ";
    }

    /** Prompt affiché quand la commande est terminée (prêt pour la suivante). */
    private void appendCompletionPrompt() {
        appendLine(promptPrefix(), C_CYAN);
    }

    private void submitCommand() {
        if (running) return;
        String cmd = commandInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        if (!PcShellPrefs.isConfigured(this)) {
            Toast.makeText(this, "Configure d'abord le PC dans Réglages.", Toast.LENGTH_LONG).show();
            return;
        }
        commandInput.setText("");
        runShell(cmd, null, null, null);
    }

    private void runShell(String command, String commandId, String label,
            Integer timeoutSeconds) {
        running = true;
        cancelFlag = new AtomicBoolean(false);
        refreshStatus();

        appendSpan(promptPrefix(), C_CYAN);
        appendLine(command, C_OUT);
        flushOutput();

        io.execute(() -> {
            SshConnectionManager.ExecResult result =
                    SshConnectionManager.execute(this, command, timeoutSeconds, cancelFlag);
            runOnUiThread(() -> {
                running = false;
                String fullOutput = PcShellExecutionLog.combineOutput(result.stdout, result.stderr);
                appendResult(result, command, label != null ? label : command);
                if (commandId != null) {
                    store.recordRun(commandId, result.exitCode);
                }
                PcShellExecutionLog.append(this, new PcShellExecutionLog.Entry(
                        System.currentTimeMillis(),
                        commandId != null ? commandId : "",
                        label != null ? label : "",
                        command,
                        result.exitCode,
                        PcShellExecutionLog.preview(result.stdout, result.stderr),
                        !TextUtils.isEmpty(result.error) ? result.error : fullOutput,
                        !TextUtils.isEmpty(result.error) ? result.error : "",
                        result.cancelled));
                appendCompletionPrompt();
                flushOutput();
                refreshStatus();
            });
        });
    }

    private void appendResult(SshConnectionManager.ExecResult result, String command, String label) {
        if (result.cancelled) {
            appendLine("[annulé]", C_WARN);
            return;
        }
        if (!TextUtils.isEmpty(result.error)) {
            String errFull = result.error.trim();
            appendOutputBlock(errFull, C_ERR);
            if (shouldOfferViewAll(errFull)) {
                appendViewAllLink(buildOutputTitle(label, command), errFull);
            }
            if (result.exitCode != 0) {
                appendLine("[code retour " + result.exitCode + "]", C_WARN);
            }
            return;
        }

        String full = PcShellExecutionLog.combineOutput(result.stdout, result.stderr);
        if (!TextUtils.isEmpty(full)) {
            appendOutputBlock(full, C_OUT);
            if (shouldOfferViewAll(full)) {
                appendViewAllLink(buildOutputTitle(label, command), full);
            }
        } else {
            appendLine("(aucune sortie)", C_MUTED);
        }
        if (result.exitCode != 0) {
            appendLine("[code retour " + result.exitCode + "]", C_WARN);
        }
    }

    private static String buildOutputTitle(String label, String command) {
        if (!TextUtils.isEmpty(label)) return label;
        if (!TextUtils.isEmpty(command)) return command;
        return "Sortie";
    }

    private static boolean shouldOfferViewAll(String full) {
        if (TextUtils.isEmpty(full)) return false;
        if (full.contains("[sortie tronquée")) return true;
        return full.length() > VIEW_ALL_THRESHOLD_CHARS;
    }

    /** Sortie commande (jusqu'à {@link PcShellPrefs#MAX_OUTPUT_CHARS}) — blocs virtualisés. */
    private void appendOutputBlock(String text, int color) {
        if (TextUtils.isEmpty(text)) return;
        if (AnsiText.needsTerminalGrid(text)) {
            AnsiTerminalRenderer.appendTo(scrollback.currentChunk(), text,
                    SshConnectionManager.PTY_COLS, color);
            scrollback.afterAppend();
        } else {
            appendSpan(text, color);
        }
        if (!text.endsWith("\n")) {
            scrollback.appendNewline();
        }
    }

    private void appendViewAllLink(String title, String fullBody) {
        if (TextUtils.isEmpty(fullBody)) return;
        String link = "▸ Voir tout";
        SpannableStringBuilder chunk = scrollback.currentChunk();
        int start = chunk.length();
        chunk.append(link);
        chunk.append('\n');
        chunk.setSpan(new ForegroundColorSpan(C_CYAN), start, start + link.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        chunk.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                openFullOutput(title, fullBody);
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(C_CYAN);
                ds.setUnderlineText(false);
            }
        }, start, start + link.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        scrollback.afterAppend();
    }

    private void openFullOutput(String title, String body) {
        PcShellOutputHolder.put(title, body);
        startActivity(new Intent(this, PcShellOutputActivity.class)
                .putExtra(PcShellOutputHolder.EXTRA_TITLE, title));
    }

    private MaterialAlertDialogBuilder darkDialog() {
        return new MaterialAlertDialogBuilder(this, R.style.Theme_Orbe_DarkDialog);
    }

    private void appendSpan(String text, int color) {
        if (text == null || text.isEmpty()) return;
        SpannableStringBuilder chunk = scrollback.currentChunk();
        AnsiText.appendTo(chunk, text, color);
        scrollback.afterAppend();
    }

    private void appendLine(String text, int color) {
        appendSpan(text, color);
        if (text == null || !text.endsWith("\n")) {
            scrollback.appendNewline();
        }
    }

    private void flushOutput() {
        int chunks = scrollback.getChunkCount();
        if (chunks == 0) return;
        outputAdapter.notifyDataSetChanged();
        outputList.post(this::scrollOutputToBottom);
    }

    private void scrollOutputToBottom() {
        int last = scrollback.getChunkCount() - 1;
        if (last < 0) return;
        outputLayoutManager.scrollToPosition(last);
    }

    private void showCommandPicker() {
        List<SavedCommand> cmds = store.listAll();

        ScrollView scroll = new ScrollView(this);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));

        scroll.setBackgroundColor(C_BG);
        list.setBackgroundColor(C_BG);

        View addRow = buildAddCommandRow();
        list.addView(addRow, matchWrapWithBottomMargin());

        if (cmds.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucune commande — ajoute-en une ci-dessus.");
            empty.setTextColor(C_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(8), dp(12), dp(8), dp(8));
            list.addView(empty);
        } else {
            for (SavedCommand cmd : cmds) {
                list.addView(buildCommandRow(cmd), matchWrapWithBottomMargin());
            }
        }

        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        darkDialog()
                .setTitle("Commandes enregistrées")
                .setView(scroll)
                .setNegativeButton("Fermer", null)
                .show();
    }

    private View buildAddCommandRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));
        row.setBackground(cardBg());
        TextView label = new TextView(this);
        label.setText("＋ Nouvelle commande…");
        label.setTextColor(C_CYAN);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(label);
        row.setOnClickListener(v -> showAddEditDialog(null));
        return row;
    }

    private View buildCommandRow(SavedCommand cmd) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(4), dp(10));
        row.setBackground(cardBg());

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textLp);

        TextView label = new TextView(this);
        label.setText(cmd.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(null, Typeface.BOLD);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(label);

        TextView command = new TextView(this);
        command.setText(cmd.command);
        command.setTextColor(C_MUTED);
        command.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        command.setTypeface(Typeface.MONOSPACE);
        command.setMaxLines(2);
        command.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(command);

        row.addView(textCol);
        row.setOnClickListener(v -> showCommandActions(cmd));

        Button delete = iconButton("✕", v -> confirmDelete(cmd));
        delete.setTextColor(C_DELETE);
        delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        delete.setPadding(dp(8), dp(4), dp(8), dp(4));
        delete.setMinWidth(dp(36));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        delLp.setMarginStart(dp(4));
        row.addView(delete, delLp);

        return row;
    }

    private LinearLayout.LayoutParams matchWrapWithBottomMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private void showCommandActions(SavedCommand cmd) {
        darkDialog()
                .setTitle(cmd.label)
                .setMessage(cmd.command)
                .setPositiveButton("▶ Lancer", (d, w) -> {
                    if (!PcShellPrefs.isConfigured(this)) {
                        Toast.makeText(this, "Configure d'abord le PC.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (running) return;
                    runShell(cmd.command, cmd.id, cmd.label, cmd.timeoutSeconds);
                })
                .setNeutralButton("Insérer", (d, w) -> commandInput.setText(cmd.command))
                .setNegativeButton("Gérer", (d, w) -> showManageDialog(cmd))
                .show();
    }

    private void showManageDialog(SavedCommand cmd) {
        darkDialog()
                .setTitle(cmd.label)
                .setItems(new CharSequence[]{"✎ Modifier", "🗑 Supprimer"}, (d, which) -> {
                    if (which == 0) {
                        showAddEditDialog(cmd);
                    } else {
                        confirmDelete(cmd);
                    }
                })
                .show();
    }

    private void showAddEditDialog(SavedCommand existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.setBackgroundColor(C_BG);
        EditText labelField = new EditText(this);
        labelField.setHint("Nom (ex: Espace disque)");
        labelField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        labelField.setHintTextColor(C_MUTED);
        labelField.setTextColor(Color.WHITE);
        if (existing != null) labelField.setText(existing.label);
        box.addView(labelField);
        EditText cmdField = new EditText(this);
        cmdField.setHint("Commande (ex: df -h)");
        cmdField.setHintTextColor(C_MUTED);
        cmdField.setTextColor(Color.WHITE);
        cmdField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdField.setTypeface(Typeface.MONOSPACE);
        if (existing != null) cmdField.setText(existing.command);
        box.addView(cmdField);

        darkDialog()
                .setTitle(existing == null ? "Nouvelle commande" : "Modifier")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String lab = labelField.getText().toString().trim();
                    String cmd = cmdField.getText().toString().trim();
                    if (lab.isEmpty() || cmd.isEmpty()) {
                        Toast.makeText(this, "Nom et commande requis.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (existing == null) {
                        store.add(lab, cmd);
                        Toast.makeText(this, "Commande enregistrée.", Toast.LENGTH_SHORT).show();
                    } else {
                        store.update(existing.id, lab, cmd);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmDelete(SavedCommand cmd) {
        darkDialog()
                .setTitle("Supprimer « " + cmd.label + " » ?")
                .setPositiveButton("Supprimer", (d, w) -> store.delete(cmd.id))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void copyScrollback() {
        if (scrollback.length() == 0) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("pc-terminal",
                    AnsiText.strip(scrollback.toPlainString())));
            Toast.makeText(this, "Terminal copié", Toast.LENGTH_SHORT).show();
        }
    }

    private Button iconButton(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(click);
        b.setBackground(cardBg());
        b.setTextColor(C_CYAN);
        return b;
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), C_SEP);
        return g;
    }

    private GradientDrawable inputBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#1A2030"));
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), C_SEP);
        return g;
    }

    private View spacer(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }
}
