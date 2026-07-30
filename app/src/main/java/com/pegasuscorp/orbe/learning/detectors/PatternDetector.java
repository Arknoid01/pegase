package com.pegasuscorp.orbe.learning.detectors;

import android.content.Context;

import com.pegasuscorp.orbe.learning.LearningCandidate;
import com.pegasuscorp.orbe.learning.Observation;

import java.util.List;

/**
 * Détecteur de motifs à partir d'observations locales.
 */
public interface PatternDetector {

    /** Peut retourner null si aucune hypothèse. */
    LearningCandidate detect(Context ctx, List<Observation> observations);
}
