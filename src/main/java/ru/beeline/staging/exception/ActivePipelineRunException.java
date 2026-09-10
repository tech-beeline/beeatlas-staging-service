/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

import lombok.Getter;

@Getter
public class ActivePipelineRunException extends RuntimeException {

    private final Long activeRunId;

    public ActivePipelineRunException(String message, Long activeRunId) {
        super(message);
        this.activeRunId = activeRunId;
    }
}
