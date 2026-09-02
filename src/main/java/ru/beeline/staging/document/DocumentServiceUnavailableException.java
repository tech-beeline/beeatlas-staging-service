/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.document;

/** document-service недоступен или ответил ошибкой при чтении содержимого по docId (EC-09). */
public class DocumentServiceUnavailableException extends RuntimeException {
    public DocumentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
