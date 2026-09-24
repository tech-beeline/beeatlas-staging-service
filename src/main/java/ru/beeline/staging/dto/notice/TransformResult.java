/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.notice;

import java.util.List;

public record TransformResult(Object snapshot, List<ArtifactNotice> notices, Object pauseContext) {

    public static TransformResult of(Object snapshot) {
        return new TransformResult(snapshot, List.of(), null);
    }

    public static TransformResult of(Object snapshot, List<ArtifactNotice> notices) {
        return new TransformResult(snapshot, notices, null);
    }

    public static TransformResult of(Object snapshot, List<ArtifactNotice> notices, Object pauseContext) {
        return new TransformResult(snapshot, notices, pauseContext);
    }
}
