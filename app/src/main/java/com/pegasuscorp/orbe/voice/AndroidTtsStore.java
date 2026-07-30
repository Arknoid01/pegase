package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Préférences TTS système Android (Google Speech Services) — nom de voix stable, pas d'index.
 */
public final class AndroidTtsStore {

    /** Voix FR active sur l'appareil de l'utilisateur (après setLanguage). */
    public static final String DEFAULT_VOICE_NAME = "fr-FR-language";

    public static final class VoiceInfo {
        public final String name;
        public final String localeTag;
        public final boolean networkRequired;
        public final int quality;

        public VoiceInfo(String name, String localeTag, boolean networkRequired, int quality) {
            this.name = name;
            this.localeTag = localeTag;
            this.networkRequired = networkRequired;
            this.quality = quality;
        }

        public String displayLabel() {
            String where = networkRequired ? "réseau" : "local";
            return name + " · " + localeTag + " · " + where;
        }
    }

    private static final String PREFS = "orbe_android_tts";
    private static final String KEY_VOICE_NAME = "voice_name";
    private static final String KEY_LOCAL_ONLY = "local_only";

    private static final CopyOnWriteArrayList<VoiceInfo> CACHED = new CopyOnWriteArrayList<>();

    private AndroidTtsStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getVoiceName(Context context) {
        return prefs(context).getString(KEY_VOICE_NAME, DEFAULT_VOICE_NAME);
    }

    public static void setVoiceName(Context context, String name) {
        if (name == null || name.trim().isEmpty()) name = DEFAULT_VOICE_NAME;
        prefs(context).edit().putString(KEY_VOICE_NAME, name.trim()).apply();
    }

    /** Si true, on refuse les voix {@code isNetworkConnectionRequired()}. */
    public static boolean preferLocalOnly(Context context) {
        return prefs(context).getBoolean(KEY_LOCAL_ONLY, true);
    }

    public static void setPreferLocalOnly(Context context, boolean localOnly) {
        prefs(context).edit().putBoolean(KEY_LOCAL_ONLY, localOnly).apply();
    }

    public static void updateCachedVoices(List<VoiceInfo> voices) {
        CACHED.clear();
        if (voices != null) CACHED.addAll(voices);
    }

    public static List<VoiceInfo> getCachedVoices() {
        return Collections.unmodifiableList(new ArrayList<>(CACHED));
    }

    public static List<VoiceInfo> listFrenchVoices(TextToSpeech tts, boolean localOnly) {
        List<VoiceInfo> out = new ArrayList<>();
        if (tts == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return out;
        Set<Voice> voices;
        try {
            voices = tts.getVoices();
        } catch (Exception e) {
            return out;
        }
        if (voices == null) return out;
        for (Voice voice : voices) {
            if (voice == null) continue;
            Locale loc = voice.getLocale();
            String lang = loc != null ? loc.getLanguage() : "";
            if (lang == null || !lang.toLowerCase(Locale.ROOT).startsWith("fr")) continue;
            if (localOnly && voice.isNetworkConnectionRequired()) continue;
            String tag = loc != null ? loc.toString() : "?";
            out.add(new VoiceInfo(voice.getName(), tag,
                    voice.isNetworkConnectionRequired(), voice.getQuality()));
        }
        Collections.sort(out, Comparator
                .comparing((VoiceInfo v) -> v.networkRequired)
                .thenComparing(v -> v.localeTag)
                .thenComparing(v -> v.name));
        return out;
    }

    /**
     * Applique la voix préférée. Retourne {@code false} si absente / filtrée réseau
     * (le caller peut basculer sur Piper).
     */
    public static boolean applyPreferredVoice(Context context, TextToSpeech tts) {
        if (tts == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        String wanted = getVoiceName(context);
        boolean localOnly = preferLocalOnly(context);
        Set<Voice> voices;
        try {
            voices = tts.getVoices();
        } catch (Exception e) {
            return false;
        }
        if (voices == null) return false;
        Voice match = null;
        Voice fallbackLocalFrFr = null;
        for (Voice voice : voices) {
            if (voice == null) continue;
            Locale loc = voice.getLocale();
            String lang = loc != null ? loc.getLanguage() : "";
            if (lang == null || !lang.toLowerCase(Locale.ROOT).startsWith("fr")) continue;
            boolean net = voice.isNetworkConnectionRequired();
            if (wanted.equals(voice.getName())) {
                if (localOnly && net) return false;
                match = voice;
                break;
            }
            if (!net && fallbackLocalFrFr == null && loc != null
                    && "FR".equalsIgnoreCase(loc.getCountry())) {
                fallbackLocalFrFr = voice;
            }
        }
        if (match == null) {
            // Nom inconnu sur cet appareil → échec (Piper en repli), sauf défaut système FR local.
            if (DEFAULT_VOICE_NAME.equals(wanted) && fallbackLocalFrFr != null) {
                match = fallbackLocalFrFr;
            } else {
                return false;
            }
        }
        try {
            int r = tts.setVoice(match);
            return r == TextToSpeech.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public static String statusLabel(Context context) {
        boolean piper = PiperModelStore.usePiper(context);
        if (piper) {
            return "Moteur : Piper · système en secours";
        }
        String name = getVoiceName(context);
        String mode = preferLocalOnly(context) ? "local only" : "local+réseau";
        return "Moteur : Système · " + name + " · " + mode;
    }
}
