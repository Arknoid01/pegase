package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.pegasuscorp.orbe.llm.PegasePrompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pipeline unique LLM → texte prêt pour Piper / TTS :
 * dictionnaire, normalisation, acronymes, ponctuation, nombres, pauses, anglicismes, découpage.
 */
public final class SpeechFormatter {

    private static final Pattern EMOJI = Pattern.compile(
            "[\\p{So}\\p{Sk}\\uFE0F\\u200D\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+");
    private static final Pattern PERCENT = Pattern.compile("\\b(\\d{1,3})\\s*%");
    private static final int MAX_CHUNK_CHARS = 110;

    private SpeechFormatter() {}

    /** Formate le texte LLM et le découpe en phrases pour la file TTS. */
    public static List<String> formatChunks(Context context, String llmText) {
        SpeechRulesSnapshot rules = SpeechRulesStore.getInstance(context).getSnapshot();
        String text = llmText == null ? "" : llmText;

        text = stripThinking(text);
        text = PegasePrompt.fixFrenchOralSpacing(text);
        if (rules.removeEmoji) {
            text = EMOJI.matcher(text).replaceAll("");
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return List.of();

        text = rules.applyDictionary(text);
        text = rules.applyReplace(text);
        text = rules.applyExpand(text);
        text = expandNumbers(text);
        text = normalizePunctuation(text);
        if (rules.ttsFriendlyMode) {
            text = ttsFriendlyCleanup(text);
        }

        List<String> sentences = splitSentences(text);
        if (rules.splitLongSentences) {
            sentences = splitLongChunks(sentences);
        }
        return sentences;
    }

    /** Une ligne déjà découpée (file TTS) — garde la compatibilité si besoin. */
    public static String formatLine(Context context, String line) {
        List<String> chunks = formatChunks(context, line);
        if (chunks.isEmpty()) return "";
        return chunks.get(0);
    }

    private static String stripThinking(String text) {
        String out = text.replaceAll("(?s)<think>.*?</think>", "");
        out = out.replace("/no_think", "");
        return out;
    }

    private static String expandNumbers(String text) {
        Matcher m = PERCENT.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + " pour cent"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalizePunctuation(String text) {
        String s = text;
        s = s.replace('\u00A0', ' ');
        s = s.replace("…", ", ");
        s = s.replace("...", ", ");
        s = s.replaceAll("\\s*[—–]\\s*", ", ");
        s = s.replaceAll("\\s+-\\s+", ", ");
        s = s.replaceAll("(?<=[\\p{L}])[\\-‑](?=[\\p{L}])", " ");
        s = s.replace(';', ',');
        s = s.replace(':', ',');
        s = s.replaceAll("\\s+,\\s*", ", ");
        s = s.replaceAll(",{2,}", ",");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String ttsFriendlyCleanup(String text) {
        String s = text;
        s = s.replace("\"", " ");
        s = s.replace("«", " ");
        s = s.replace("»", " ");
        s = s.replaceAll("[\\[\\]{}()]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] parts = text.split("(?<=[.!?…])\\s+");
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (out.isEmpty()) out.add(text.trim());
        return out;
    }

    private static List<String> splitLongChunks(List<String> sentences) {
        List<String> out = new ArrayList<>();
        for (String sentence : sentences) {
            if (sentence.length() <= MAX_CHUNK_CHARS) {
                out.add(sentence);
                continue;
            }
            out.addAll(splitOnCommas(sentence));
        }
        return out;
    }

    private static List<String> splitOnCommas(String sentence) {
        List<String> out = new ArrayList<>();
        String[] parts = sentence.split(",\\s+");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String piece = part.trim();
            if (piece.isEmpty()) continue;
            if (current.length() == 0) {
                current.append(piece);
            } else if (current.length() + 2 + piece.length() <= MAX_CHUNK_CHARS) {
                current.append(", ").append(piece);
            } else {
                out.add(current.toString());
                current = new StringBuilder(piece);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        if (out.isEmpty()) out.add(sentence);
        return out;
    }
}
