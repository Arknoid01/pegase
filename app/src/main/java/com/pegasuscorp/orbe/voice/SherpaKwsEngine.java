package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;

/**
 * Keyword spotting local (Sherpa) — micro 16 kHz, process {@code :voice} uniquement.
 * Route audio (Bluetooth SCO, casque filaire, micro téléphone) via {@link KwsAudioRouteManager}.
 */
public final class SherpaKwsEngine {

    public interface Listener {
        /** Mot détecté (souvent tokenisé) — la commande suit côté conversation STT. */
        void onKeywordDetected(String keyword);

        /** Casque BT / filaire branché ou débranché pendant l'écoute KWS. */
        default void onAudioRouteChanged() {}
    }

    private static final String TAG = "SherpaKws";
    private static final int SAMPLE_RATE = 16_000;
    /** Débit natif d'un lien HFP bande étroite (CVSD). */
    private static final int SCO_NATIVE_RATE = 8_000;
    private static final float INTERVAL_SEC = 0.1f;
    /** Seuil Sherpa global (les lignes keywords.txt peuvent overrider). */
    private static final float KEYWORDS_THRESHOLD = 0.04f;
    private static final float KEYWORDS_SCORE = 5.0f;
    /**
     * Rejette un HIT trop faible (bruit poche / frottement).
     * Utilise le pic RMS sur ~1,5 s (pas le frame de fin d'énoncé, souvent trop bas).
     * Vrai PEGASE rejeté à −61 sur frame fin alors que la parole était ~−25…−38.
     */
    private static final float MIN_HIT_RMS_DB = -55f;
    /** Fenêtre pic RMS : 15 × 100 ms. */
    private static final int PEAK_RMS_WINDOW = 15;
    /** ~2 s entre deux logs probe (100 ms × 20). */
    private static final int PROBE_EVERY_READS = 20;
    /**
     * Après parole, recréer le OnlineStream (pas reset mid-mot).
     * Évite les HIT retardés type HEY_PEGASE collés sur une conversation.
     * 1,5 s ≫ trailing blanks du spotter (~200–400 ms).
     */
    private static final int SILENCE_RECREATE_FRAMES = 15;
    private static final float SPEECH_RMS_DB = -45f;
    private static final float QUIET_RMS_DB = -58f;
    /** Composés (Hey/Ok…) : exigent un pic plus fort que les alias courts. */
    private static final float MIN_COMPOUND_HIT_RMS_DB = -42f;

    /** Salves de parole sans le moindre token avant de capturer un extrait audio. */
    private static final int DEAF_STREAK_FOR_DUMP = 6;
    /** Un extrait au plus toutes les 2 min (diagnostic, pas enregistrement continu). */
    private static final long DEAF_DUMP_MIN_INTERVAL_MS = 120_000L;
    private static final int DEAF_DUMP_SECONDS = 6;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    /** Anneau des dernières secondes — alimenté seulement si le diagnostic est armé. */
    private final RollingAudioBuffer deafBuffer = new RollingAudioBuffer(DEAF_DUMP_SECONDS);
    private int deafStreak;
    private long lastDeafDumpMs;
    /** Fréquence de capture réelle : 8 kHz sur SCO, {@link #SAMPLE_RATE} sinon. */
    private volatile int captureRate = SAMPLE_RATE;
    /** Dernier échantillon du bloc précédent — continuité de l'interpolation. */
    private float lastSample;

    /**
     * Blocs audio en attente d'inférence. Borné : si le décodage prend du retard on
     * jette les plus anciens plutôt que de bloquer la lecture — perdre une fenêtre
     * ancienne coûte moins cher que trouer le flux en cours.
     */
    private static final int BLOCK_QUEUE_CAPACITY = 12;
    private final java.util.concurrent.BlockingQueue<short[]> blocks =
            new java.util.concurrent.ArrayBlockingQueue<>(BLOCK_QUEUE_CAPACITY);
    private Thread readerThread;
    private volatile boolean readerFailed;
    private volatile int droppedBlocks;
    /** ~2 s hors route (2 sondes) avant de rouvrir le micro. */
    private static final int OFF_ROUTE_PROBES_BEFORE_RESTART = 2;
    /** La session a ouvert le micro sur le casque : toute dérive est une anomalie. */
    private volatile boolean sessionWantsSco;
    private int offRouteProbes;

    /** True si l'{@link AudioRecord} lit réellement l'entrée Bluetooth. */
    private boolean capturingOnSco() {
        AudioRecord rec;
        synchronized (captureLock) {
            rec = audioRecord;
        }
        if (rec == null) return false;
        try {
            AudioDeviceInfo d = rec.getRoutedDevice();
            return d != null && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        } catch (Exception e) {
            return true; // information indisponible : ne pas déclencher de réouverture
        }
    }

    private KwsAudioRouteManager routeManager;
    private KeywordSpotter kws;
    private OnlineStream stream;
    private AudioRecord audioRecord;
    private Thread thread;
    private volatile boolean wantRun;
    private volatile boolean running;
    private volatile boolean nativeBroken;
    private volatile boolean routeChanged;
    /** prepareCapture / releaseCapture ne doivent s'exécuter que depuis le thread KWS. */
    private final Object captureLock = new Object();
    private int readCount;

    public SherpaKwsEngine(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    /** À appeler avant {@link #start()} (typiquement depuis {@code VoiceService.onCreate}). */
    public void setRouteManager(KwsAudioRouteManager routeManager) {
        this.routeManager = routeManager;
        if (routeManager != null) {
            routeManager.setRouteChangeListener(this::onExternalRouteChange);
        }
    }

    public boolean isReady() {
        return !nativeBroken && KwsModelStore.isModelReady(app) && ensureLoaded();
    }

    public boolean isNativeBroken() {
        return nativeBroken;
    }

    public boolean isRunning() {
        return running;
    }

    /** Charge le modèle si présent. */
    public synchronized boolean ensureLoaded() {
        if (nativeBroken) return false;
        if (kws != null) return true;
        KwsModelStore.ensureKeywords(app);
        KwsModelStore.logModelIdentity(app);
        if (!KwsModelStore.isModelReady(app)) {
            Log.w(TAG, "init skipped — model files not ready");
            return false;
        }
        File enc = KwsModelStore.encoderFile(app);
        File dec = KwsModelStore.decoderFile(app);
        File join = KwsModelStore.joinerFile(app);
        File tok = KwsModelStore.tokensFile(app);
        File kw = KwsModelStore.keywordsFile(app);
        try {
            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(enc.getAbsolutePath())
                    .setDecoder(dec.getAbsolutePath())
                    .setJoiner(join.getAbsolutePath())
                    .build();
            OnlineModelConfig model = OnlineModelConfig.builder()
                    .setTransducer(transducer)
                    .setTokens(tok.getAbsolutePath())
                    .setNumThreads(1)
                    .setDebug(false)
                    .setProvider("cpu")
                    .setModelType("zipformer2")
                    .build();
            FeatureConfig feat = FeatureConfig.builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setFeatureDim(80)
                    .setDither(0f)
                    .build();
            KeywordSpotterConfig config = KeywordSpotterConfig.builder()
                    .setFeatureConfig(feat)
                    .setOnlineModelConfig(model)
                    .setKeywordsFile(kw.getAbsolutePath())
                    .setKeywordsScore(KEYWORDS_SCORE)
                    .setKeywordsThreshold(KEYWORDS_THRESHOLD)
                    .setMaxActivePaths(16)
                    .setNumTrailingBlanks(2)
                    .build();
            Log.i(TAG, "creating KeywordSpotter enc=" + enc.getName()
                    + " size=" + enc.length()
                    + " threshold=" + KEYWORDS_THRESHOLD
                    + " score=" + KEYWORDS_SCORE);
            kws = new KeywordSpotter(config);
            Log.i(TAG, "KeywordSpotter ready (zipformer2) threshold=" + KEYWORDS_THRESHOLD);
            return true;
        } catch (UnsatisfiedLinkError e) {
            nativeBroken = true;
            Log.e(TAG, "native broken: " + e.getMessage(), e);
            return false;
        } catch (Throwable e) {
            String msg = e.getMessage();
            Throwable cause = e.getCause();
            Log.e(TAG, "init failed: " + e.getClass().getSimpleName()
                    + " msg=" + msg
                    + (cause != null ? " cause=" + cause.getClass().getSimpleName()
                    + ":" + cause.getMessage() : ""), e);
            if (e instanceof Error) nativeBroken = true;
            releaseSpotter();
            return false;
        }
    }

    public void start() {
        wantRun = true;
        routeChanged = false;
        if (running) return;
        if (!ensureLoaded()) return;
        if (ActivityCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PocketWakeGuard.start(app);
        thread = new Thread(this::loop, "sherpa-kws");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        KwsCrashGuard.onKwsStarting(app);
        thread.start();
    }

    public void stop() {
        wantRun = false;
        if (!routeChanged) {
            routeChanged = true;
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        running = false;
        PocketWakeGuard.stop();
    }

    public void release() {
        stop();
        releaseSpotter();
    }

    private void onExternalRouteChange() {
        Log.i(TAG, "audio route changed — stopping capture for restart");
        routeChanged = true;
        main.post(() -> {
            if (listener != null) {
                listener.onAudioRouteChanged();
            }
        });
    }

    private void releaseSpotter() {
        if (stream != null) {
            try { stream.release(); } catch (Exception ignored) {}
            stream = null;
        }
        if (kws != null) {
            try { kws.release(); } catch (Exception ignored) {}
            kws = null;
        }
    }

    private String routeDescription() {
        String base = routeManager != null
                ? routeManager.describeRoute()
                : "PHONE_BUILTIN source=VOICE_RECOGNITION";
        // describeRoute() interroge le système : il dit quel micro *devrait* servir.
        // getRoutedDevice() dit lequel alimente réellement la capture — les deux
        // divergent quand le device préféré disparaît sous un setPreferredDevice
        // déjà posé (Android bascule alors en silence sur l'entrée par défaut).
        return base + " " + describeRoutedDevice();
    }

    /**
     * Après plusieurs salves de parole sans le moindre token, écrit les dernières
     * secondes de capture en WAV pour qu'on puisse *écouter* ce que le moteur reçoit.
     * Diagnostic ponctuel : rien n'est écrit tant que le wake fonctionne.
     */
    private void maybeDumpDeafAudio() {
        if (deafStreak < DEAF_STREAK_FOR_DUMP) return;
        long now = System.currentTimeMillis();
        if (now - lastDeafDumpMs < DEAF_DUMP_MIN_INTERVAL_MS) return;
        lastDeafDumpMs = now;
        deafStreak = 0;
        float[] snap = deafBuffer.snapshotSeconds(DEAF_DUMP_SECONDS);
        if (snap.length == 0) return;
        short[] pcm = new short[snap.length];
        for (int i = 0; i < snap.length; i++) {
            float v = snap[i] * 32768f;
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
        try {
            File dir = new File(app.getFilesDir(), "diag");
            if (!dir.exists() && !dir.mkdirs()) return;
            File dest = new File(dir, "kws_deaf_" + now + ".wav");
            // Écrit à la fréquence de capture réelle, sinon le 8 kHz SCO se relit
            // deux fois trop vite et on croit à tort à une voix aiguë.
            AudioCapture.writeWav(dest, pcm, captureRate);
            Log.w(TAG, "deaf dump → " + dest.getAbsolutePath());
            org.json.JSONObject f = new org.json.JSONObject();
            f.put("file", dest.getName());
            f.put("seconds", DEAF_DUMP_SECONDS);
            f.put("route", routeDescription());
            com.pegasuscorp.orbe.diag.PegaseDiagLog.kws(app, "kws_deaf_dump", f);
        } catch (Exception e) {
            Log.w(TAG, "deaf dump failed", e);
        }
    }

    /**
     * Android rebascule l'entrée d'un {@link AudioRecord} déjà ouvert, sans changement de
     * route visible : mesuré sur une session sans le moindre {@code audio_route_changed},
     * un tiers des relevés capturaient le micro intégré alors que le casque était épinglé.
     * {@code setPreferredDevice} est une préférence, pas un verrou — on la réaffirme dès
     * qu'elle est perdue. Purement événementiel, aucun sondage.
     */
    private void watchRouting(AudioRecord record) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            record.addOnRoutingChangedListener(router -> {
                AudioRecord rec;
                synchronized (captureLock) {
                    rec = audioRecord;
                }
                if (rec == null || rec != router) return;
                AudioDeviceInfo now = rec.getRoutedDevice();
                if (now != null && now.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return;
                KwsAudioRouteManager rm = routeManager;
                if (rm == null || rm.getActiveKind() != KwsAudioRouteManager.RouteKind.BLUETOOTH_SCO) {
                    return;
                }
                Log.w(TAG, "routage perdu ("
                        + (now == null ? "null" : routedTypeLabel(now.getType()))
                        + ") — réaffirmation du casque");
                rm.applyPreferredDevice(rec);
                KwsDiagnostics.logStreamReset(routeDescription(), "routing_reasserted");
            }, main);
        } catch (Exception e) {
            Log.w(TAG, "addOnRoutingChangedListener", e);
        }
    }

    /** Périphérique réellement routé par l'{@link AudioRecord} en cours. */
    private String describeRoutedDevice() {
        AudioRecord record;
        synchronized (captureLock) {
            record = audioRecord;
        }
        if (record == null) return "routed=none";
        try {
            // Fréquence réellement accordée : si le HAL SCO livre du 8 kHz sans rééchantillonner,
            // Sherpa reçoit du 16 kHz étiqueté mais deux fois trop rapide → voix aiguë, 0 token.
            String rate = "rate=" + record.getSampleRate();
            AudioDeviceInfo dev = record.getRoutedDevice();
            if (dev == null) return "routed=null " + rate;
            return "routed=id=" + dev.getId() + ",type=" + routedTypeLabel(dev.getType())
                    + " " + rate;
        } catch (Exception e) {
            return "routed=err";
        }
    }

    private static String routedTypeLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BT_SCO";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return "BLE";
                }
                return "type" + type;
        }
    }

    private void loop() {
        running = true;
        try {
            // Fichiers d'évaluation déposés dans files/diag : joués une fois, hors micro.
            // Sans effet quand il n'y en a pas.
            KwsFileEvaluator.runPending(app, kws, SAMPLE_RATE);
            if (!openMic()) {
                Log.w(TAG, "mic open failed route=" + routeDescription());
                return;
            }
            readCount = 0;
            KwsDiagnostics.logSessionStart(routeDescription());
            stream = kws.createStream();
            if (stream == null || stream.getPtr() == 0L) {
                Log.e(TAG, "createStream failed");
                return;
            }
            audioRecord.startRecording();
            int bufferSize = Math.max(1, (int) (INTERVAL_SEC * SAMPLE_RATE));
            startReader(bufferSize);
            short[] buffer;
            int emptyStreak = 0;
            float[] recentRms = new float[PEAK_RMS_WINDOW];
            java.util.Arrays.fill(recentRms, -96f);
            int recentRmsIdx = 0;
            boolean hadSpeech = false;
            int quietAfterSpeech = 0;
            boolean sawTokensInBurst = false;
            while (wantRun && !routeChanged) {
                // Ne plus skipper les frames si média : sinon « Pégase » n'est jamais scorée.
                if (MediaPlaybackGuard.isOtherAudioPlaying(app)) {
                    KwsDiagnostics.maybeLogMediaActive(routeDescription());
                }
                // Le micro est lu par un thread dédié : l'inférence qui suit ne doit
                // jamais retarder la lecture, sinon le tampon de capture déborde et
                // l'audio est perdu (mesuré : jusqu'à 60 % du signal en trous).
                buffer = takeBlock();
                if (buffer == null) {
                    if (readerFailed) break;
                    emptyStreak++;
                    if (emptyStreak > 200) {
                        Log.w(TAG, "aucun bloc audio — sortie pour redémarrage route="
                                + routeDescription());
                        break;
                    }
                    continue;
                }
                int ret = buffer.length;
                emptyStreak = 0;
                float rmsDb = KwsDiagnostics.computeRmsDb(buffer, ret);
                recentRms[recentRmsIdx % PEAK_RMS_WINDOW] = rmsDb;
                recentRmsIdx++;
                float peakRmsDb = rmsDb;
                for (float r : recentRms) {
                    if (r > peakRmsDb) peakRmsDb = r;
                }
                readCount++;
                if (readCount % PROBE_EVERY_READS == 0) {
                    KwsDiagnostics.maybeLogProbe(routeDescription(), rmsDb, ret);
                    // Le micro peut glisser vers l'entrée intégrée en cours de session et
                    // ne jamais revenir (mesuré : 328 relevés d'affilée sur BUILTIN_MIC
                    // alors que la session visait le casque). Réaffirmer la préférence ne
                    // suffit pas une fois la route perdue : on force une réouverture.
                    if (sessionWantsSco && !capturingOnSco()) {
                        offRouteProbes++;
                        if (offRouteProbes >= OFF_ROUTE_PROBES_BEFORE_RESTART) {
                            Log.w(TAG, "capture hors casque depuis "
                                    + offRouteProbes + " sondes — réouverture du micro");
                            KwsDiagnostics.logStreamReset(routeDescription(), "off_route_restart");
                            routeChanged = true;
                            break;
                        }
                    } else {
                        offRouteProbes = 0;
                    }
                }
                deafBuffer.write(buffer, ret);
                float[] samples;
                if (captureRate == SAMPLE_RATE) {
                    samples = new float[ret];
                    for (int i = 0; i < ret; i++) {
                        samples[i] = buffer[i] / 32768.0f;
                    }
                } else {
                    // 8 kHz → 16 kHz : interpolation linéaire (et non un simple doublement
                    // en escalier, qui recrée exactement l'image spectrale à 4 kHz qu'on
                    // cherche à éliminer). Continuité assurée par lastSample entre blocs.
                    samples = new float[ret * 2];
                    float prev = lastSample;
                    for (int i = 0; i < ret; i++) {
                        float cur = buffer[i] / 32768.0f;
                        samples[2 * i] = (prev + cur) * 0.5f;
                        samples[2 * i + 1] = cur;
                        prev = cur;
                    }
                    lastSample = prev;
                }

                if (rmsDb > SPEECH_RMS_DB) {
                    hadSpeech = true;
                    quietAfterSpeech = 0;
                } else if (hadSpeech && rmsDb < QUIET_RMS_DB) {
                    quietAfterSpeech++;
                    if (quietAfterSpeech >= SILENCE_RECREATE_FRAMES) {
                        OnlineStream fresh = recreateStream(stream);
                        if (fresh != null) {
                            stream = fresh;
                            KwsDiagnostics.logStreamReset(routeDescription(), "recreate_after_silence");
                        }
                        // Fin d'une salve de parole : a-t-elle produit le moindre token ?
                        if (sawTokensInBurst) {
                            deafStreak = 0;
                        } else {
                            deafStreak++;
                            maybeDumpDeafAudio();
                        }
                        sawTokensInBurst = false;
                        hadSpeech = false;
                        quietAfterSpeech = 0;
                    }
                }

                stream.acceptWaveform(samples, SAMPLE_RATE);
                boolean decoded = false;
                while (wantRun && !routeChanged && kws.isReady(stream)) {
                    decoded = true;
                    kws.decode(stream);
                    KeywordSpotterResult result = kws.getResult(stream);
                    String kw = result != null ? result.getKeyword() : null;
                    String[] tokens = result != null ? result.getTokens() : null;
                    float[] timestamps = result != null ? result.getTimestamps() : null;
                    if (tokens != null && tokens.length > 0) sawTokensInBurst = true;
                    if (kw != null && !kw.trim().isEmpty()) {
                        kws.reset(stream);
                        deafStreak = 0;
                        sawTokensInBurst = false;
                        hadSpeech = false;
                        quietAfterSpeech = 0;
                        final String detected = kw.trim();
                        // Alias courts / composés ambigus : gate RMS.
                        // PEGASE plein saute le gate (HIT souvent sur queue soft).
                        if (isCompoundWakeKeyword(detected) && peakRmsDb < MIN_COMPOUND_HIT_RMS_DB) {
                            KwsDiagnostics.logHitRejectedRms(
                                    routeDescription(), peakRmsDb, detected, tokens,
                                    MIN_COMPOUND_HIT_RMS_DB);
                            continue;
                        }
                        if (!isStrongWakeKeyword(detected) && peakRmsDb < MIN_HIT_RMS_DB) {
                            KwsDiagnostics.logHitRejectedRms(
                                    routeDescription(), peakRmsDb, detected, tokens, MIN_HIT_RMS_DB);
                            continue;
                        }
                        if (PocketWakeGuard.shouldSuppressWake(app)) {
                            KwsDiagnostics.logHitRejectedPocket(
                                    routeDescription(), peakRmsDb, detected, tokens);
                            continue;
                        }
                        KwsDiagnostics.logHit(routeDescription(), peakRmsDb, detected, tokens, timestamps);
                        wantRun = false;
                        main.post(() -> {
                            if (listener != null) listener.onKeywordDetected(detected);
                        });
                        return;
                    }
                    KwsDiagnostics.logDecodeReadyNoHit(routeDescription(), rmsDb, tokens, timestamps);
                }
                if (!decoded) {
                    KwsDiagnostics.maybeLogSpeechNoTokens(routeDescription(), rmsDb);
                }
            }
            if (routeChanged && wantRun) {
                Log.i(TAG, "loop exit for route change — watchdog will restart");
            }
        } catch (UnsatisfiedLinkError e) {
            nativeBroken = true;
            Log.e(TAG, "native in loop", e);
            KwsDiagnostics.logLoopError(routeDescription(), "native", e.getMessage());
        } catch (Throwable e) {
            Log.e(TAG, "loop error", e);
            KwsDiagnostics.logLoopError(routeDescription(), e.getClass().getSimpleName(),
                    e.getMessage());
        } finally {
            // Arrêter le lecteur avant de fermer le micro : il lit dessus.
            stopReader();
            closeMic();
            if (stream != null) {
                try { stream.release(); } catch (Exception ignored) {}
                stream = null;
            }
            running = false;
            PocketWakeGuard.stop();
        }
    }

    /**
     * Thread de lecture pure : il ne fait qu'appeler {@code read()} et déposer le bloc.
     * Aucun calcul, aucune E/S, aucun verrou partagé avec l'inférence.
     */
    private void startReader(int blockSize) {
        blocks.clear();
        readerFailed = false;
        droppedBlocks = 0;
        readerThread = new Thread(() -> {
            short[] buf = new short[blockSize];
            int errStreak = 0;
            while (wantRun && !routeChanged) {
                AudioRecord rec;
                synchronized (captureLock) {
                    rec = audioRecord;
                }
                if (rec == null) break;
                int n;
                try {
                    n = rec.read(buf, 0, buf.length);
                } catch (Exception e) {
                    Log.w(TAG, "read", e);
                    readerFailed = true;
                    break;
                }
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error=" + n
                            + (n == AudioRecord.ERROR_DEAD_OBJECT ? " DEAD_OBJECT"
                            : n == AudioRecord.ERROR_INVALID_OPERATION ? " INVALID_OP"
                            : n == AudioRecord.ERROR_BAD_VALUE ? " BAD_VALUE" : ""));
                    if (n == AudioRecord.ERROR_DEAD_OBJECT
                            || n == AudioRecord.ERROR_INVALID_OPERATION) {
                        readerFailed = true;
                        break;
                    }
                    if (++errStreak > 40) {
                        readerFailed = true;
                        break;
                    }
                    continue;
                }
                if (n == 0) {
                    if (++errStreak > 200) {
                        readerFailed = true;
                        break;
                    }
                    continue;
                }
                errStreak = 0;
                short[] block = new short[n];
                System.arraycopy(buf, 0, block, 0, n);
                // Jamais bloquant : on préfère perdre le plus ancien bloc.
                while (!blocks.offer(block)) {
                    if (blocks.poll() != null) droppedBlocks++;
                }
            }
            readerFailed = true; // débloque le consommateur en sortie
        }, "kws-mic-reader");
        readerThread.setPriority(Thread.MAX_PRIORITY);
        readerThread.start();
    }

    /** Bloc suivant, ou {@code null} après une courte attente (boucle de sortie). */
    private short[] takeBlock() {
        try {
            return blocks.poll(120, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void stopReader() {
        Thread t = readerThread;
        readerThread = null;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (droppedBlocks > 0) {
            Log.w(TAG, "blocs audio jetés (inférence en retard) : " + droppedBlocks);
        }
        blocks.clear();
    }

    private boolean openMic() {
        synchronized (captureLock) {
            // Route fixée par VoiceService (forcePhoneBuiltin) — pas de prepare/release SCO ici.
            int source = routeManager != null
                    ? routeManager.getAudioSource()
                    : android.media.MediaRecorder.AudioSource.MIC;
            // Le rééchantillonnage 8 → 16 kHz du système est propre : une appli
            // d'enregistrement tierce obtient 1,4 % de zéros sur le même casque, là où
            // notre capture en produisait 37 à 54 %. On demande donc 16 kHz comme elle,
            // et on ne force plus le périphérique d'entrée (voir plus bas) — c'est le
            // seul écart structurel qui restait avec une capture qui fonctionne.
            // (conservé : sert au commentaire de captureRate)
            boolean sco = routeManager != null
                    && routeManager.getActiveKind() == KwsAudioRouteManager.RouteKind.BLUETOOTH_SCO;
            captureRate = SAMPLE_RATE;
            lastSample = 0f;
            int min = AudioRecord.getMinBufferSize(
                    captureRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return false;
            audioRecord = new AudioRecord(
                    source,
                    captureRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, captureRate / 5));
            // Indispensable sur SCO : sans épinglage, Android rebranche la capture sur le
            // micro intégré en cours de session (mesuré : routed=id=19,type=BUILTIN_MIC
            // alors que la route annonçait BLUETOOTH_SCO, niveaux à -75 dB). Ce n'est pas
            // la cause des corruptions qu'on a chassées — c'était startVoiceRecognition().
            if (routeManager != null) {
                routeManager.applyPreferredDevice(audioRecord);
                watchRouting(audioRecord);
            }
            sessionWantsSco = sco;
            offRouteProbes = 0;
            boolean ok = audioRecord.getState() == AudioRecord.STATE_INITIALIZED;
            if (ok) {
                Log.i(TAG, "mic open " + routeDescription());
            } else {
                Log.e(TAG, "AudioRecord not initialized source=" + source);
            }
            return ok;
        }
    }

    private void closeMic() {
        long t0 = android.os.SystemClock.elapsedRealtime();
        synchronized (captureLock) {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (Exception ignored) {}
                try {
                    audioRecord.release();
                } catch (Exception ignored) {}
                audioRecord = null;
            }
            // Ne pas releaseBluetoothSco : hold service wake reste actif jusqu'à stop/destroy.
        }
        try {
            org.json.JSONObject f = new org.json.JSONObject();
            f.put("backend", "sherpa");
            f.put("close_mic_ms", android.os.SystemClock.elapsedRealtime() - t0);
            f.put("released_sco", false);
            WakeToSttTrace.mark(app, "kws_close_mic_done", f);
        } catch (Exception ignored) {}
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mots-clés complets (sans préfixe Hey/Ok) — fiables, pas de gate RMS. */
    private static boolean isStrongWakeKeyword(String keyword) {
        if (keyword == null) return false;
        String k = keyword.trim().toUpperCase(java.util.Locale.US);
        if (isCompoundWakeKeyword(k)) return false;
        return k.equals("PEGASE")
                || k.equals("PEGASE_CHARS")
                || k.equals("PEGA_SE")
                || k.equals("PEGASUS")
                || k.equals("PEGAZE");
    }

    /** Préfixes conversationnels — plus stricts (faux positifs en discussion). */
    private static boolean isCompoundWakeKeyword(String keyword) {
        if (keyword == null) return false;
        String k = keyword.trim().toUpperCase(java.util.Locale.US);
        return k.startsWith("HEY_")
                || k.startsWith("OK_")
                || k.startsWith("BONJOUR_");
    }

    private OnlineStream recreateStream(OnlineStream old) {
        try {
            if (old != null) {
                try { old.release(); } catch (Exception ignored) {}
            }
            OnlineStream fresh = kws.createStream();
            if (fresh == null || fresh.getPtr() == 0L) {
                Log.e(TAG, "recreateStream failed");
                return null;
            }
            return fresh;
        } catch (Throwable e) {
            Log.e(TAG, "recreateStream error", e);
            return null;
        }
    }
}
