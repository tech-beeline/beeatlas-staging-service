/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UseCaseSnapshot {

    private Header             usecase;
    private String             branch;
    private E2ESequenceSnapshot entities = new E2ESequenceSnapshot();
    private List<Step>         steps    = new ArrayList<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Header {
        private String code;
        private String name;
        private String biStepCode;
        private String projectCode;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Step {
        private String  partId;
        private Integer seq;
        private String  scenarioType;
        private String  stepType;
        private String  name;
        private String  calleeOperationExtUid;
        private String  callerOperationExtUid;
        private String  productAlias;
        private String  interfaceCode;
        private String  operationType;
        private String  operationName;
        private String  reason;
    }
}
