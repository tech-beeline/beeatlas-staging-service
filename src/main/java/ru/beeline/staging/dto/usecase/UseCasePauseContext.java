/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.usecase;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UseCasePauseContext(
        Header usecase,
        String branch,
        List<Part> mapped,
        List<Part> unmapped) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Header(String code, String name, String biStepCode, String projectCode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(
            String partId,
            Integer seq,
            String scenarioType,
            String stepType,
            String name,
            String callStatus,
            Target target,
            ConnectionOperation connectionOperation,
            String reason,
            String suggestion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(
            Long stepVersionId,
            Long operationVersionId,
            String type,
            String name,
            String productAlias,
            String interfaceCode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectionOperation(
            Integer id,
            String operationType,
            String operationName,
            String interfaceCode,
            String containerCode,
            String productAlias) {

        public static final ConnectionOperation EMPTY = new ConnectionOperation(null, null, null, null, null, null);
    }
}
