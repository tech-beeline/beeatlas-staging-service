/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.usecase;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UseCaseDraft(
        Header usecase,
        String branch,
        List<MappedPart> mapped,
        List<UnmappedPart> unmapped) {

    public static final String CALL_STATUS_CONFIRMED = "confirmed";

    public List<MappedPart> mappedOrEmpty() {
        return mapped == null ? List.of() : mapped;
    }

    public List<UnmappedPart> unmappedOrEmpty() {
        return unmapped == null ? List.of() : unmapped;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Header(String code, String name, String biStepCode, String projectCode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Side(
            String system,
            String container,
            @JsonProperty("interface") String interfaceCode,
            String operation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MappedPart(
            String partId,
            String type,
            Integer seq,
            String scenarioType,
            String stepType,
            String name,
            String system,
            String container,
            @JsonProperty("interface") String interfaceCode,
            String operation,
            Side caller,
            String tcCode,
            String sequenceCode,
            String dynamicDiagramUrl,
            String callStatus) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnmappedPart(
            String partId,
            String type,
            Integer seq,
            String scenarioType,
            String stepType,
            String name,
            String side,
            List<String> participants,
            String reason,
            String suggestion) {
    }
}
