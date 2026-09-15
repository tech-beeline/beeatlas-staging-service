/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class PipelineRunUnresolvedPartsException extends RuntimeException {

    private final List<String> unresolvedParts;

    public PipelineRunUnresolvedPartsException(String message, List<String> unresolvedParts) {
        super(message);
        this.unresolvedParts = unresolvedParts;
    }
}
