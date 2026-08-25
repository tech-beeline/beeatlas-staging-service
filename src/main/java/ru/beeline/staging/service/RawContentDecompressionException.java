package ru.beeline.staging.service;

import lombok.Getter;

@Getter
public class RawContentDecompressionException extends RuntimeException {

    private final Long rawDataRefId;

    public RawContentDecompressionException(Long rawDataRefId, Throwable cause) {
        super("Failed to decompress raw content: rawDataRefId=" + rawDataRefId, cause);
        this.rawDataRefId = rawDataRefId;
    }
}
