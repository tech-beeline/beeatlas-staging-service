/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.exec;

public interface ArtifactPipelineStage {

    String stageName();

    void execute(Long runId) throws Exception;
}
