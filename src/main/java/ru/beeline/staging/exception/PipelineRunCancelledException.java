/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class PipelineRunCancelledException extends RuntimeException {

    public PipelineRunCancelledException(Long runId, String stageName) {
        super("PipelineRun " + runId + " is cancelled, stage " + stageName + " is not started");
    }
}
