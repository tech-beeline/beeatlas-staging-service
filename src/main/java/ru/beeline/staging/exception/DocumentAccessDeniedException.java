/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.exception;

/** document-service отказал в доступе к документу (403) — не путать с недоступностью сервиса. */
public class DocumentAccessDeniedException extends RuntimeException {
    public DocumentAccessDeniedException(long docId) {
        super("Access to document denied: docId=" + docId);
    }
}
