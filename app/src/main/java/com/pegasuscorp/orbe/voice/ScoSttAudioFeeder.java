package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Alimente {@code SpeechRecognizer} avec notre propre capture, via
 * {@code RecognizerIntent.EXTRA_AUDIO_SOURCE} (API 31+).
 *
 * <p>Raison d'être : sur casque HFP, le rééchantillonnage 8 → 16 kHz fait par le
 * système est cassé sur certains appareils (images spectrales, un échantillon sur
 * deux à zéro — cf. {@link SherpaKwsEngine}). Le wake y a échappé en capturant à la
 * fréquence native ; le recognizer, qui ouvre son propre micro, le subissait encore.
 * On capture donc nous-mêmes à 8 kHz, on double proprement, et on lui pousse le PCM
 * dans un tube.
 *
 * <p>Si le service de reconnaissance ignore l'extra, il ouvrira le micro lui-même :
 * {@link #stop()} libère alors notre capture sans dommage.
 */
final class ScoSttAudioFeeder {

    private static final String TAG = "ScoSttFeeder";
    /** Débit natif d'un lien HFP bande étroite. */
    private static final int CAPTURE_RATE = 8_000;
    /** Ce qu'attendent les moteurs de reconnaissance. */
    static final int OUTPUT_RATE = 16_000;

    private final Context app;
    private final KwsAudioRouteManager routeManager;

    private AudioRecord record;
    private ParcelFileDescriptor readSide;
    private ParcelFileDescriptor writeSide;
    private Thread pump;
    private volatile boolean running;

    ScoSttAudioFeeder(Context context, KwsAudioRouteManager routeManager) {
        this.app = context.getApplicationContext();
        this.routeManager = routeManager;
    }

    /** L'extra n'existe qu'à partir d'Android 12. */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Ouvre la capture et démarre l'alimentation du tube.
     *
     * @return l'extrémité à passer au recognizer, ou {@code null} si l'ouverture
     *         a échoué (l'appelant retombe alors sur le chemin normal)
     */
    ParcelFileDescriptor start() {
        if (!isSupported()) return null;
        try {
            int min = AudioRecord.getMinBufferSize(
                    CAPTURE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    CAPTURE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, CAPTURE_RATE / 5));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord non initialisé");
                release();
                return null;
            }
            if (routeManager != null) {
                routeManager.applyPreferredDevice(record);
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readSide = pipe[0];
            writeSide = pipe[1];
            record.startRecording();
            running = true;
            pump = new Thread(this::pump, "sco-stt-feeder");
            pump.setPriority(Thread.MAX_PRIORITY - 1);
            pump.start();
            Log.i(TAG, "feeder démarré " + CAPTURE_RATE + " → " + OUTPUT_RATE + " Hz");
            return readSide;
        } catch (Exception e) {
            Log.w(TAG, "start", e);
            release();
            return null;
        }
    }

    private void pump() {
        short[] buffer = new short[CAPTURE_RATE / 10];
        byte[] out = new byte[buffer.length * 4]; // ×2 échantillons, ×2 octets
        float lastSample = 0f;
        try (OutputStream sink = new FileOutputStream(writeSide.getFileDescriptor())) {
            while (running) {
                int ret = record.read(buffer, 0, buffer.length);
                if (ret <= 0) {
                    if (ret == AudioRecord.ERROR_DEAD_OBJECT
                            || ret == AudioRecord.ERROR_INVALID_OPERATION) {
                        break;
                    }
                    continue;
                }
                // 8 → 16 kHz par interpolation linéaire : un doublement en escalier
                // recréerait l'image spectrale à 4 kHz qu'on cherche à éviter.
                int o = 0;
                float prev = lastSample;
                for (int i = 0; i < ret; i++) {
                    float cur = buffer[i];
                    short mid = (short) ((prev + cur) * 0.5f);
                    out[o++] = (byte) (mid & 0xFF);
                    out[o++] = (byte) ((mid >> 8) & 0xFF);
                    short s = (short) cur;
                    out[o++] = (byte) (s & 0xFF);
                    out[o++] = (byte) ((s >> 8) & 0xFF);
                    prev = cur;
                }
                lastSample = prev;
                sink.write(out, 0, o);
            }
        } catch (Exception e) {
            // Tube fermé par le recognizer en fin de session : cas nominal.
            Log.d(TAG, "pump terminé: " + e.getMessage());
        }
    }

    /** Arrête la capture et ferme le tube (idempotent). */
    void stop() {
        running = false;
        Thread t = pump;
        pump = null;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        release();
    }

    private void release() {
        if (record != null) {
            try {
                if (record.getState() == AudioRecord.STATE_INITIALIZED) record.stop();
            } catch (Exception ignored) {}
            try {
                record.release();
            } catch (Exception ignored) {}
            record = null;
        }
        closeQuiet(writeSide);
        writeSide = null;
        closeQuiet(readSide);
        readSide = null;
    }

    private static void closeQuiet(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Exception ignored) {}
    }
}
