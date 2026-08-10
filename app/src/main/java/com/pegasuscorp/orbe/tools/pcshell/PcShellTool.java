package com.pegasuscorp.orbe.tools.pcshell;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.pegasuscorp.orbe.pcshell.PcShellCommandMatcher;
import com.pegasuscorp.orbe.pcshell.PcShellCommandStore;
import com.pegasuscorp.orbe.pcshell.PcShellExecutionLog;
import com.pegasuscorp.orbe.pcshell.PcShellKeyCache;
import com.pegasuscorp.orbe.pcshell.PcShellSession;
import com.pegasuscorp.orbe.pcshell.PcShellVoicePending;
import com.pegasuscorp.orbe.pcshell.PcTerminalActivity;
import com.pegasuscorp.orbe.pcshell.SavedCommand;
import com.pegasuscorp.orbe.pcshell.SshConnectionManager;
import com.pegasuscorp.orbe.pcshell.SshKeyManager;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolResult;
import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal PC distant — ouverture UI (biométrie obligatoire) et exécution de commandes enregistrées.
 */
public final class PcShellTool implements Tool {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pc-shell-voice");
        t.setDaemon(true);
        return t;
    });

    private static final int SPEAK_OUTPUT_MAX = 1200;

    @Override
    public String id() {
        return "pc_shell";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.PC_SHELL;
    }

    @Override
    public String description() {
        return "pc_shell(action:\"open\"|\"execute\"|\"list\", commandId?:str, label?:str) — "
                + "Terminal PC SSH (commandes enregistrées uniquement). open → UI + biométrie ; "
                + "execute → lance une SavedCommand par id ou label ; list → libellés disponibles.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (ctx == null || params == null) {
            cb.onError("Paramètres terminal PC manquants.");
            return;
        }
        String action = params.optString("action", "open").trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "open":
                openTerminal(ctx, cb);
                break;
            case "execute":
                executeRegistered(ctx, params, cb);
                break;
            case "list":
                listCommands(ctx, cb);
                break;
            case "nomatch":
                noMatch(ctx, params, cb);
                break;
            default:
                cb.onError("Action terminal PC inconnue — open, execute ou list.");
        }
    }

    static void openTerminal(Context ctx, ToolCallback cb) {
        Intent i = new Intent(ctx, PcTerminalActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ctx.startActivity(i);
        cb.onSuccessAndExit(ToolResult.text(
                "J'ouvre le terminal PC. Authentifie-toi pour accéder au shell distant."));
    }

    static void listCommands(Context ctx, ToolCallback cb) {
        List<SavedCommand> cmds = PcShellCommandStore.getInstance(ctx).listAll();
        if (cmds.isEmpty()) {
            cb.onSuccessAndExit(ToolResult.text(
                    "Tu n'as aucune commande enregistrée dans le terminal PC. "
                            + "Ajoute-en une dans l'app, section Terminal PC."));
            return;
        }
        List<String> labels = PcShellCommandMatcher.listLabels(cmds);
        StringBuilder sb = new StringBuilder("Commandes terminal PC disponibles : ");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append(i == labels.size() - 1 ? " et " : ", ");
            sb.append('«').append(labels.get(i)).append('»');
        }
        sb.append('.');
        cb.onSuccessAndExit(ToolResult.text(sb.toString()));
    }

    static void noMatch(Context ctx, JSONObject params, ToolCallback cb) {
        String spoken = params.optString("spokenLabel", "").trim();
        List<SavedCommand> cmds = PcShellCommandStore.getInstance(ctx).listAll();
        if (cmds.isEmpty()) {
            cb.onSuccessAndExit(ToolResult.text(
                    "Je ne trouve pas de commande correspondante"
                            + (spoken.isEmpty() ? "" : " pour « " + spoken + " »")
                            + ". Tu n'as encore aucune commande enregistrée dans le terminal PC."));
            return;
        }
        List<String> labels = PcShellCommandMatcher.listLabels(cmds);
        StringBuilder sb = new StringBuilder();
        sb.append("Je ne trouve pas de commande correspondante");
        if (!spoken.isEmpty()) {
            sb.append(" pour « ").append(spoken).append("»");
        }
        sb.append(". Commandes disponibles : ");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append(i == labels.size() - 1 ? " et " : ", ");
            sb.append('«').append(labels.get(i)).append('»');
        }
        sb.append('.');
        cb.onSuccessAndExit(ToolResult.text(sb.toString()));
    }

    static void executeRegistered(Context ctx, JSONObject params, ToolCallback cb) {
        SavedCommand cmd = resolveCommand(ctx, params);
        if (cmd == null) {
            cb.onError("Commande terminal PC introuvable.");
            return;
        }

        if (!PcShellSession.isUnlocked() || !PcShellKeyCache.isLoaded()) {
            PcShellVoicePending.setExecuteAfterUnlock(cmd.id);
            Intent i = new Intent(ctx, PcTerminalActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            cb.onSuccessAndExit(ToolResult.text(
                    "Authentifie-toi pour lancer « " + cmd.label + " » sur le terminal PC."));
            return;
        }

        SshKeyManager.ensureKeyPair(ctx);
        AtomicBoolean cancel = new AtomicBoolean(false);
        IO.execute(() -> {
            SshConnectionManager.ExecResult result =
                    SshConnectionManager.execute(ctx, cmd.command, cmd.timeoutSeconds, cancel);
            PcShellCommandStore.getInstance(ctx).recordRun(cmd.id, result.exitCode);
            String fullOutput = PcShellExecutionLog.combineOutput(result.stdout, result.stderr);
            PcShellExecutionLog.append(ctx, new PcShellExecutionLog.Entry(
                    System.currentTimeMillis(),
                    cmd.id,
                    cmd.label,
                    cmd.command,
                    result.exitCode,
                    PcShellExecutionLog.preview(result.stdout, result.stderr),
                    !TextUtils.isEmpty(result.error) ? result.error : fullOutput,
                    !TextUtils.isEmpty(result.error) ? result.error : "",
                    result.cancelled));
            cb.onSuccessAndExit(ToolResult.text(formatVoiceResult(cmd, result)));
        });
    }

    static SavedCommand resolveCommand(Context ctx, JSONObject params) {
        String commandId = params.optString("commandId", "").trim();
        if (!commandId.isEmpty()) {
            return PcShellCommandStore.getInstance(ctx).getById(commandId);
        }
        String label = params.optString("label", "").trim();
        if (!label.isEmpty()) {
            PcShellCommandMatcher.Match match = PcShellCommandMatcher.bestMatch(
                    PcShellCommandStore.getInstance(ctx).listAll(), label);
            return match != null ? match.command : null;
        }
        return null;
    }

    static String formatVoiceResult(SavedCommand cmd, SshConnectionManager.ExecResult result) {
        if (result.cancelled) {
            return "Commande « " + cmd.label + " » annulée.";
        }
        if (!TextUtils.isEmpty(result.error)) {
            return result.error;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("« ").append(cmd.label).append(" » — code ").append(result.exitCode).append('.');
        if (!TextUtils.isEmpty(result.stdout)) {
            sb.append(' ').append(truncateForSpeech(result.stdout.trim()));
        }
        if (!TextUtils.isEmpty(result.stderr)) {
            sb.append(" Erreur : ").append(truncateForSpeech(result.stderr.trim()));
        }
        if (TextUtils.isEmpty(result.stdout) && TextUtils.isEmpty(result.stderr)) {
            sb.append(" Aucune sortie.");
        }
        return sb.toString();
    }

    static String truncateForSpeech(String text) {
        if (text.length() <= SPEAK_OUTPUT_MAX) return text;
        return text.substring(0, SPEAK_OUTPUT_MAX) + "… [sortie tronquée]";
    }
}
