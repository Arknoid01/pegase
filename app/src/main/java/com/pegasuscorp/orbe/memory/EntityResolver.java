package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Résout localement noms et alias → fiches de l'atlas. */
public final class EntityResolver {

    public static final double INJECT_THRESHOLD = 0.55;
    public static final double AMBIGUOUS_GAP = 0.12;

    public static final class EntityMatch {
        public final Entity entity;
        public final double score;
        public final String matchedTerm;

        EntityMatch(Entity entity, double score, String matchedTerm) {
            this.entity = entity;
            this.score = score;
            this.matchedTerm = matchedTerm;
        }
    }

    public static final class Resolution {
        public final List<EntityMatch> matches;
        public final List<Entity> ambiguous;

        Resolution(List<EntityMatch> matches, List<Entity> ambiguous) {
            this.matches = matches;
            this.ambiguous = ambiguous;
        }

        public List<EntityMatch> forInjection(int max) {
            List<EntityMatch> out = new ArrayList<>();
            for (EntityMatch m : matches) {
                if (m.score >= INJECT_THRESHOLD) out.add(m);
                if (out.size() >= max) break;
            }
            return out;
        }
    }

    private EntityResolver() {}

    public static Resolution resolve(Context context, String message) {
        if (context == null || message == null || message.trim().isEmpty()) {
            return new Resolution(Collections.emptyList(), Collections.emptyList());
        }
        String fold = SpeechInputNormalizer.fold(message).replace('\'', ' ');
        List<EntityMatch> raw = new ArrayList<>();
        for (Entity entity : EntityStore.getInstance(context).getAll()) {
            EntityMatch best = bestMatch(entity, fold);
            if (best != null) raw.add(best);
        }
        raw.sort((a, b) -> Double.compare(b.score, a.score));
        List<Entity> ambiguous = findAmbiguous(raw);
        return new Resolution(raw, ambiguous);
    }

    private static EntityMatch bestMatch(Entity entity, String fold) {
        EntityMatch best = null;
        for (String term : entity.allMatchTerms()) {
            double score = scoreTerm(fold, Entity.foldTerm(term));
            if (score <= 0) continue;
            if (best == null || score > best.score) {
                best = new EntityMatch(entity, score, term);
            }
        }
        return best;
    }

    private static double scoreTerm(String fold, String termFold) {
        if (termFold.isEmpty()) return 0;
        if (fold.equals(termFold)) return 1.0;
        if (fold.contains(" " + termFold + " ") || fold.startsWith(termFold + " ")
                || fold.endsWith(" " + termFold)) {
            return termFold.length() >= 6 ? 0.92 : 0.78;
        }
        if (termFold.length() >= 4 && fold.contains(termFold)) {
            return 0.65 + Math.min(0.2, termFold.length() * 0.02);
        }
        if (termFold.length() >= 3 && containsWord(fold, termFold)) {
            return 0.58;
        }
        return 0;
    }

    private static boolean containsWord(String fold, String word) {
        int idx = fold.indexOf(word);
        while (idx >= 0) {
            boolean left = idx == 0 || !Character.isLetterOrDigit(fold.charAt(idx - 1));
            int end = idx + word.length();
            boolean right = end >= fold.length() || !Character.isLetterOrDigit(fold.charAt(end));
            if (left && right) return true;
            idx = fold.indexOf(word, idx + 1);
        }
        return false;
    }

    private static List<Entity> findAmbiguous(List<EntityMatch> sorted) {
        if (sorted.size() < 2) return Collections.emptyList();
        EntityMatch first = sorted.get(0);
        EntityMatch second = sorted.get(1);
        if (first.score < INJECT_THRESHOLD || second.score < INJECT_THRESHOLD) {
            return Collections.emptyList();
        }
        if (first.entity.type.equals(second.entity.type)
                && first.score - second.score <= AMBIGUOUS_GAP) {
            List<Entity> out = new ArrayList<>();
            out.add(first.entity);
            out.add(second.entity);
            return out;
        }
        return Collections.emptyList();
    }

    public static List<String> termsForScoring(Resolution resolution) {
        List<String> terms = new ArrayList<>();
        if (resolution == null) return terms;
        for (EntityMatch m : resolution.matches) {
            if (m.score < INJECT_THRESHOLD) continue;
            terms.add(m.entity.name.toLowerCase(Locale.ROOT));
            for (String alias : m.entity.aliases) {
                terms.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        return terms;
    }
}
