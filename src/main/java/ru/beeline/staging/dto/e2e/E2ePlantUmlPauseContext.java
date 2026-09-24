/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record E2ePlantUmlPauseContext(E2e e2e, List<Participant> participants, List<Request> requests) {

    public static final String MATCHED = "matched";
    public static final String NOT_FOUND = "not_found";
    public static final String SKIPPED = "skipped";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record E2e(String uid, String name, String biStepCode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Participant(String alias, boolean resolved, String productAlias, String productName, String kind) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(Integer order, String fromAlias, String toAlias, String label, String type, String path,
                          boolean unknown, String productAlias, String interfaceCode, String interfaceName,
                          Match match) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Match(String status, Integer connectionOperationId, String operationName, String operationType,
                        String interfaceCode, String interfaceName, String containerCode, String containerName,
                        String productAlias, String productName) {

        public static Match of(String status) {
            return new Match(status, null, null, null, null, null, null, null, null, null);
        }
    }
}
