/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.List;

/**
 * Structural content of a PlantUML Sequence Diagram, extracted by {@link PlantUmlDiagramParser}.
 */
public record ParsedDiagram(List<Participant> participants, List<Message> messages) {

    /**
     * {@code declaredKind} is the PlantUML keyword used (participant/actor/database/...), not a CMDB kind.
     * {@code name} is the display name — for {@code participant Name as alias} that's {@code Name}, where
     * process owners write the CMDB mnemonic; {@code alias} is what messages reference it by.
     */
    public record Participant(String alias, String name, String declaredKind, int line) {}

    public record Message(String fromAlias, String toAlias, String label, int line) {}
}
