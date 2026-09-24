/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class DocumentServiceUnavailableException extends RuntimeException {
    public DocumentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
