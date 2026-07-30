package com.pegasuscorp.orbe.diag;

import android.content.Context;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatSessionRegistry;
import com.pegasuscorp.orbe.memory.MemoryRepository;
import com.pegasuscorp.orbe.session.PegaseSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Sauvegarde la mémoire utilisateur, exécute les mini-tests sur une session vierge,
 * puis restaure la conversation réelle.
 */
public final class DiagScriptIsolation {

    private List<ChatBackend.Turn> memoryBackup;
    private boolean sessionWasActive;

    /** @return nombre de tours sauvegardés avant purge */
    public int prepareCleanSession(Context context) {
        Context app = context.getApplicationContext();
        MemoryRepository memory = MemoryRepository.getInstance(app);
        memoryBackup = memory.getRecentTurns();

        PegaseSession session = PegaseSession.get(app);
        sessionWasActive = session.isActive();
        if (sessionWasActive) {
            session.exit();
        }

        memory.clearRecentTurns();
        session.recreate(app);

        return memoryBackup != null ? memoryBackup.size() : 0;
    }

    public void restoreAfterSuite(Context context) {
        Context app = context.getApplicationContext();
        PegaseSession session = PegaseSession.get(app);
        session.recreate(app);

        if (memoryBackup != null) {
            MemoryRepository.getInstance(app).setRecentTurns(
                    new ArrayList<>(memoryBackup));
        }

        if (sessionWasActive) {
            session.enter();
        }

        memoryBackup = null;
        sessionWasActive = false;
    }

    public int backupTurnCount() {
        return memoryBackup != null ? memoryBackup.size() : 0;
    }
}
