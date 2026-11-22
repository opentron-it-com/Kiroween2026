package org.opentron.backend.security.confidential.gateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class CompositePiiDetector implements PiiDetector {
    private final PiiDetector rules;
    private final PiiDetector model;

    public CompositePiiDetector(PiiDetector rules, PiiDetector model) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public String version() {
        return rules.version() + "+" + model.version();
    }

    @Override
    public List<DetectedEntity> detect(CharSequence text) {
        List<DetectedEntity> ruleEntities = rules.detect(text);
        List<DetectedEntity> combined = new ArrayList<>(ruleEntities);
        for (DetectedEntity candidate : model.detect(text)) {
            boolean overlapsRule = ruleEntities.stream().anyMatch(rule ->
                    rule.start() < candidate.end() && rule.end() > candidate.start());
            if (!overlapsRule) {
                combined.add(candidate);
            }
        }
        combined.sort(Comparator.comparingInt(DetectedEntity::start)
                .thenComparingInt(DetectedEntity::end));
        return List.copyOf(combined);
    }
}
