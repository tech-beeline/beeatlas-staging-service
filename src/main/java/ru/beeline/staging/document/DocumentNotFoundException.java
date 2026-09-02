/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.document;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(long docId) {
        super("Document not found: docId=" + docId);
    }
}
