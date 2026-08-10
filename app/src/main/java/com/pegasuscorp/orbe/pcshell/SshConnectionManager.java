package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.text.TextUtils;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;
import net.schmizz.sshj.userauth.password.PasswordFinder;

import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Connexion SSH unique + exécution d'une commande (sshj / Tailscale).
 */
public final class SshConnectionManager {

    /** TTY virtuel pour couleurs ANSI et binaires type fastfetch (évite « @notty »). */
    public static final String PTY_TERM = "xterm-256color";
    public static final int PTY_COLS = 120;
    public static final int PTY_ROWS = 48;

    public static final class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final String error;
        public final boolean cancelled;

        ExecResult(int exitCode, String stdout, String stderr, String error, boolean cancelled) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.error = error != null ? error : "";
            this.cancelled = cancelled;
        }

        static ExecResult cancelled() {
            return new ExecResult(-1, "", "", "Annulé.", true);
        }

        static ExecResult connectionError(String msg) {
            return new ExecResult(-1, "", "", msg, false);
        }
    }

    private SshConnectionManager() {}

    public static ExecResult execute(Context context, String command,
            AtomicBoolean cancelFlag) {
        return execute(context, command, null, cancelFlag);
    }

    public static ExecResult execute(Context context, String command,
            Integer savedTimeoutSec, AtomicBoolean cancelFlag) {
        long timeoutMs = SshCommandHelper.resolveTimeoutMs(command, savedTimeoutSec);
        return execute(context, command, timeoutMs, cancelFlag);
    }

    public static ExecResult execute(Context context, String command, long timeoutMs,
            AtomicBoolean cancelFlag) {
        PcShellCrypto.ensureSecurityProviders();
        if (context == null || TextUtils.isEmpty(command)) {
            return ExecResult.connectionError("Commande vide.");
        }
        String host = PcShellPrefs.getHost(context);
        if (TextUtils.isEmpty(host)) {
            return ExecResult.connectionError(
                    "PC non configuré — renseigne l'hostname Tailscale dans les réglages.");
        }
        SshKeyManager.KeyMaterial keys = SshKeyManager.ensureKeyPair(context);
        String pem = keys.privateKeyPem;
        int port = PcShellPrefs.getPort(context);
        String user = PcShellPrefs.getUser(context);

        boolean detached = SshCommandHelper.useDetachedSession(command, timeoutMs);
        String remoteCommand = detached
                ? SshCommandHelper.wrapDetached(command)
                : command;

        AtomicReference<SSHClient> clientRef = new AtomicReference<>();
        AtomicReference<Session> sessionRef = new AtomicReference<>();

        Thread cancelWatcher = null;
        if (cancelFlag != null) {
            cancelWatcher = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    if (cancelFlag.get()) {
                        closeQuietly(sessionRef.get());
                        closeQuietly(clientRef.get());
                        return;
                    }
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "pc-shell-cancel");
            cancelWatcher.setDaemon(true);
            cancelWatcher.start();
        }

        SSHClient client = new SSHClient();
        clientRef.set(client);
        try {
            client.addHostKeyVerifier(new PromiscuousVerifier());
            client.setConnectTimeout((int) Math.min(timeoutMs, 30_000L));
            client.setTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
            client.connect(host, port);

            OpenSSHKeyV1KeyFile keyFile = new OpenSSHKeyV1KeyFile();
            keyFile.init(new StringReader(pem), (PasswordFinder) null);
            client.authPublickey(user, keyFile);

            if (cancelFlag != null && cancelFlag.get()) {
                return ExecResult.cancelled();
            }

            try (Session session = client.startSession()) {
                sessionRef.set(session);
                if (!detached) {
                    session.allocatePTY(PTY_TERM, PTY_COLS, PTY_ROWS, 0, 0,
                            Collections.emptyMap());
                }
                Session.Command cmd = session.exec(remoteCommand);

                AtomicReference<String> stdoutRef = new AtomicReference<>("");
                AtomicReference<String> stderrRef = new AtomicReference<>("");
                AtomicReference<Exception> readErr = new AtomicReference<>();
                Thread outThread = new Thread(() -> {
                    try {
                        stdoutRef.set(readLimited(cmd.getInputStream(), PcShellPrefs.MAX_OUTPUT_CHARS));
                    } catch (Exception e) {
                        readErr.set(e);
                    }
                }, "pc-shell-stdout");
                Thread errThread = new Thread(() -> {
                    try {
                        stderrRef.set(readLimited(cmd.getErrorStream(), PcShellPrefs.MAX_OUTPUT_CHARS));
                    } catch (Exception e) {
                        readErr.set(e);
                    }
                }, "pc-shell-stderr");
                outThread.start();
                errThread.start();

                cmd.join(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                outThread.join(5_000L);
                errThread.join(5_000L);
                if (readErr.get() != null) {
                    throw readErr.get();
                }

                Integer exit = cmd.getExitStatus();
                if (cancelFlag != null && cancelFlag.get()) {
                    return ExecResult.cancelled();
                }
                return new ExecResult(
                        exit != null ? exit.intValue() : -1,
                        stdoutRef.get(),
                        stderrRef.get(),
                        "",
                        false);
            }
        } catch (Exception e) {
            if (cancelFlag != null && cancelFlag.get()) {
                return ExecResult.cancelled();
            }
            return ExecResult.connectionError(friendlyError(e, command));
        } finally {
            if (cancelWatcher != null) {
                cancelWatcher.interrupt();
            }
            closeQuietly(client);
            clientRef.set(null);
            sessionRef.set(null);
        }
    }

    private static String readLimited(InputStream in, int maxChars) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream baos = IOUtils.readFully(in);
        String raw = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        if (raw.length() <= maxChars) return raw;
        return raw.substring(0, maxChars) + "\n… [sortie tronquée à " + maxChars + " car.]";
    }

    private static void closeQuietly(Session session) {
        if (session == null) return;
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(SSHClient client) {
        if (client == null) return;
        try {
            client.disconnect();
        } catch (Exception ignored) {
        }
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    private static String friendlyError(Exception e, String command) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        String lower = msg.toLowerCase();
        if (lower.contains("connection refused") || lower.contains("failed to connect")) {
            return "PC injoignable — vérifie qu'il est allumé, sur Tailscale, et que sshd tourne.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            String hint = SshCommandHelper.looksLikeLongPackageCommand(command)
                    ? " Si pacman était en cours, vérifie sur le PC : "
                    + "sudo rm -f /var/lib/pacman/db.lck puis relance."
                    : "";
            return "Délai dépassé — le PC ne répond pas assez vite." + hint;
        }
        if (lower.contains("auth fail") || lower.contains("publickey")
                || lower.contains("permission denied")
                || lower.contains("exhausted available authentication")) {
            return "Clé SSH refusée — vérifie que la clé publique Pégase est dans "
                    + "authorized_keys (pegase-remote) et que l'empreinte SHA256 correspond.";
        }
        if (lower.contains("invalid") && lower.contains("key")) {
            return "Clé privée invalide — rouvre Réglages Terminal PC (migration auto) "
                    + "ou régénère la clé et remets-la sur le PC.";
        }
        if (lower.contains("x25519") || lower.contains("no such algorithm")
                || lower.contains("1.3.101.112") || lower.contains("unsupported algorithm")) {
            return "Crypto SSH indisponible — redémarre l'app. Si ça persiste, mets à jour Pégase.";
        }
        return "Erreur SSH : " + msg;
    }
}
