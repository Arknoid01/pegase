package com.pegasuscorp.orbe.intentions.rules;

import com.pegasuscorp.orbe.intentions.ContextSnapshot;
import com.pegasuscorp.orbe.intentions.IntentionCandidate;

public interface IntentionRule {
    IntentionCandidate evaluate(ContextSnapshot context);
}
