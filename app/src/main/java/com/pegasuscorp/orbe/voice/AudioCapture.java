package com.pegasuscorp.orbe.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Capture PCM 16 kHz mono pour l'empreinte vocale.
 */
public final class AudioCapture {

    public static final int SAMPLE_RATE = 16_000;

    private AudioCapture() {}

    public static float[] recordSeconds(int seconds) {
        if (seconds <= 0) return new float[0];
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return new float[0];

        int totalSamples = SAMPLE_RATE * seconds;
        short[] buffer = new short[Math.max(minBuf, 2048)];
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            return new float[0];
        }

        float[] out = new float[totalSamples];
        int written = 0;
        try {
            recorder.startRecording();
            while (written < totalSamples) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) break;
                for (int i = 0; i < read && written < totalSamples; i++) {
                    out[written++] = buffer[i] / 32768f;
                }
            }
        } finally {
            try {
                recorder.stop();
            } catch (Exception ignored) {}
            recorder.release();
        }

        if (written == 0) return new float[0];
        if (written == out.length) return out;
        float[] trimmed = new float[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }
}
