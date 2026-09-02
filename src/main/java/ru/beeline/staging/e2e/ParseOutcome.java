/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.List;

/**
 * Result of {@link PlantUmlDiagramParser#parse(String)}: either a structurally parsed Sequence
 * Diagram, or a list of blocking findings (invalid syntax / not a Sequence Diagram).
 */
public record ParseOutcome(ParsedDiagram diagram, List<Finding> findings) {

    public static ParseOutcome ok(ParsedDiagram diagram) {
        return new ParseOutcome(diagram, List.of());
    }

    public static ParseOutcome failed(List<Finding> findings) {
        return new ParseOutcome(null, findings);
    }

    public boolean isParsed() {
        return diagram != null;
    }
}
