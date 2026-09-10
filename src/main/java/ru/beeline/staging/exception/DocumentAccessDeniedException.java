/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class DocumentAccessDeniedException extends RuntimeException {
    public DocumentAccessDeniedException(long docId) {
        super("Access to document denied: docId=" + docId);
    }
}
