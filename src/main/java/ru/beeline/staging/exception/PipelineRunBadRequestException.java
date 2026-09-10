/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class PipelineRunBadRequestException extends RuntimeException {

    public PipelineRunBadRequestException(String message) {
        super(message);
    }
}
