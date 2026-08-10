package com.pegasuscorp.orbe.pcshell;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scrollback terminal découpé en blocs (~4 Ko) pour affichage virtualisé.
 */
public final class PcTerminalScrollback {

    /** Scrollback session (plusieurs commandes). */
    public static final int MAX_SESSION_CHARS = 512 * 1024;
    /** Taille cible d'un bloc RecyclerView. */
    public static final int CHUNK_TARGET_CHARS = 4096;

    private final ArrayList<SpannableStringBuilder> chunks = new ArrayList<>();

    public PcTerminalScrollback() {
        chunks.add(new SpannableStringBuilder());
    }

    public SpannableStringBuilder currentChunk() {
        return chunks.get(chunks.size() - 1);
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public CharSequence getChunk(int index) {
        return chunks.get(index);
    }

    public List<CharSequence> snapshotChunks() {
        return Collections.unmodifiableList(new ArrayList<>(chunks));
    }

    public int length() {
        int total = 0;
        for (SpannableStringBuilder chunk : chunks) {
            total += chunk.length();
        }
        return total;
    }

    public void appendNewline() {
        currentChunk().append('\n');
        afterAppend();
    }

    /** Découpe si besoin puis applique la limite session. */
    public void afterAppend() {
        splitOverflowChunks();
        trimSession();
    }

    public String toPlainString() {
        StringBuilder out = new StringBuilder(length());
        for (SpannableStringBuilder chunk : chunks) {
            out.append(chunk);
        }
        return out.toString();
    }

    private void splitOverflowChunks() {
        while (currentChunk().length() > CHUNK_TARGET_CHARS) {
            SpannableStringBuilder cur = currentChunk();
            int splitAt = findSplitPoint(cur, CHUNK_TARGET_CHARS);
            if (splitAt <= 0 || splitAt >= cur.length()) {
                splitAt = Math.min(CHUNK_TARGET_CHARS, cur.length());
            }
            SpannableStringBuilder tail = new SpannableStringBuilder(
                    cur.subSequence(splitAt, cur.length()));
            cur.delete(splitAt, cur.length());
            chunks.add(tail);
        }
    }

    private static int findSplitPoint(SpannableStringBuilder sb, int maxChars) {
        int limit = Math.min(maxChars, sb.length());
        int searchStart = Math.max(0, limit - 1024);
        for (int i = limit - 1; i >= searchStart; i--) {
            if (sb.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return limit;
    }

    private void trimSession() {
        if (length() <= MAX_SESSION_CHARS) return;

        int excess = length() - MAX_SESSION_CHARS + CHUNK_TARGET_CHARS;
        while (excess > 0 && !chunks.isEmpty()) {
            SpannableStringBuilder first = chunks.get(0);
            if (first.length() <= excess) {
                excess -= first.length();
                chunks.remove(0);
            } else {
                first.delete(0, excess);
                excess = 0;
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(new SpannableStringBuilder());
        }
        prependTruncationNotice();
    }

    private void prependTruncationNotice() {
        SpannableStringBuilder first = chunks.get(0);
        String notice = "… [historique tronqué]\n";
        if (!first.toString().startsWith(notice)) {
            SpannableStringBuilder merged = new SpannableStringBuilder(notice);
            merged.setSpan(new ForegroundColorSpan(0x88FFFFFF), 0, notice.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            merged.append(first);
            chunks.set(0, merged);
        }
    }
}
