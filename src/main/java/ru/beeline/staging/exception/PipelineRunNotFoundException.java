/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class PipelineRunNotFoundException extends RuntimeException {

    public PipelineRunNotFoundException(String message) {
        super(message);
    }
}
