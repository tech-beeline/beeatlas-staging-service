/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(long docId) {
        super("Document not found: docId=" + docId);
    }
}
