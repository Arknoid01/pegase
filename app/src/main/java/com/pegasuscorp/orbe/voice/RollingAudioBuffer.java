package com.pegasuscorp.orbe.voice;

/**
 * Tampon circulaire PCM 16 kHz pour conserver les dernières secondes d'audio
 * (vérification du locuteur au moment du wake).
 */
public final class RollingAudioBuffer {

    private final float[] ring;
    private int writePos;
    private int filled;

    public RollingAudioBuffer(int seconds) {
        int capacity = Math.max(1, seconds) * AudioCapture.SAMPLE_RATE;
        ring = new float[capacity];
    }

    public synchronized void write(short[] pcm, int count) {
        if (pcm == null || count <= 0) return;
        for (int i = 0; i < count; i++) {
            ring[writePos] = pcm[i] / 32768f;
            writePos = (writePos + 1) % ring.length;
            if (filled < ring.length) filled++;
        }
    }

    /** Dernières {@code seconds} secondes (ou moins si le buffer n'est pas plein). */
    public synchronized float[] snapshotSeconds(int seconds) {
        int want = Math.min(ring.length, Math.max(1, seconds) * AudioCapture.SAMPLE_RATE);
        int available = Math.min(filled, want);
        if (available <= 0) return new float[0];
        float[] out = new float[available];
        int start = (writePos - available + ring.length) % ring.length;
        if (start + available <= ring.length) {
            System.arraycopy(ring, start, out, 0, available);
        } else {
            int first = ring.length - start;
            System.arraycopy(ring, start, out, 0, first);
            System.arraycopy(ring, 0, out, first, available - first);
        }
        return out;
    }

    public synchronized void clear() {
        writePos = 0;
        filled = 0;
    }
}
