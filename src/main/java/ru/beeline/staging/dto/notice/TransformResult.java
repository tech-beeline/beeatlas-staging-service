/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.notice;

import java.util.List;

public record TransformResult(Object snapshot, List<ArtifactNotice> notices) {

    public static TransformResult of(Object snapshot) {
        return new TransformResult(snapshot, List.of());
    }

    public static TransformResult of(Object snapshot, List<ArtifactNotice> notices) {
        return new TransformResult(snapshot, notices);
    }
}
