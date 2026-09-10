/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.e2e;

import java.util.List;

public record ParsedDiagram(List<Participant> participants, List<Message> messages) {

    public record Participant(String alias, String name, String declaredKind, int line) {}

    public record Message(String fromAlias, String toAlias, String label, int line) {}
}
