/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class PipelineRunConflictException extends RuntimeException {

    public PipelineRunConflictException(String message) {
        super(message);
    }
}
