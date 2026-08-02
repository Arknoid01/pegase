package com.pegasuscorp.orbe.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Capture PCM 16 kHz mono — empreinte locuteur + échantillons openWakeWord.
 */
public final class AudioCapture {

    public static final int SAMPLE_RATE = 16_000;

    private AudioCapture() {}

    /** Compat locuteur : source VOICE_RECOGNITION, durée entière en secondes. */
    public static float[] recordSeconds(int seconds) {
        short[] pcm = recordPcmMs(seconds * 1000, MediaRecorder.AudioSource.VOICE_RECOGNITION);
        if (pcm.length == 0) return new float[0];
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = pcm[i] / 32768f;
        }
        return out;
    }

    /**
     * Capture pour entraînement wake — même source {@code MIC} que le KWS / OWW.
     *
     * @return PCM 16-bit, ou tableau vide si échec
     */
    public static short[] recordWakeSamplesMs(int durationMs) {
        return recordPcmMs(durationMs, MediaRecorder.AudioSource.MIC);
    }

    public static short[] recordPcmMs(int durationMs, int audioSource) {
        if (durationMs <= 0) return new short[0];
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return new short[0];

        int totalSamples = Math.max(1, (int) (SAMPLE_RATE * (durationMs / 1000.0)));
        short[] buffer = new short[Math.max(minBuf, 2048)];
        AudioRecord recorder = new AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            return new short[0];
        }

        short[] out = new short[totalSamples];
        int written = 0;
        try {
            recorder.startRecording();
            while (written < totalSamples) {
                int toRead = Math.min(buffer.length, totalSamples - written);
                int read = recorder.read(buffer, 0, toRead);
                if (read <= 0) break;
                System.arraycopy(buffer, 0, out, written, read);
                written += read;
            }
        } finally {
            try {
                recorder.stop();
            } catch (Exception ignored) {}
            recorder.release();
        }

        if (written == 0) return new short[0];
        if (written == out.length) return out;
        short[] trimmed = new short[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    /** WAV 16 kHz mono 16-bit PCM (pipeline openWakeWord). */
    public static void writeWav(File dest, short[] pcm) throws IOException {
        if (pcm == null) pcm = new short[0];
        int dataBytes = pcm.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes("US-ASCII"));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes("US-ASCII"));
        header.put("fmt ".getBytes("US-ASCII"));
        header.putInt(16); // PCM chunk
        header.putShort((short) 1); // PCM
        header.putShort((short) 1); // mono
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * 2); // byte rate
        header.putShort((short) 2); // block align
        header.putShort((short) 16); // bits
        header.put("data".getBytes("US-ASCII"));
        header.putInt(dataBytes);

        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(header.array());
            ByteBuffer samples = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : pcm) samples.putShort(s);
            out.write(samples.array());
        }
    }
}
