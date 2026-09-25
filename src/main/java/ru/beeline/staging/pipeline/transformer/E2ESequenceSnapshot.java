/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class E2ESequenceSnapshot {

    private E2eScenarioDraft             e2eScenario;
    private List<ProductDraft>           products           = new ArrayList<>();
    private List<ContainerDraft>         containers         = new ArrayList<>();
    private List<InterfaceDraft>         interfaces         = new ArrayList<>();
    private List<OperationDraft>         operations         = new ArrayList<>();
    private List<BiStepDraft>            biSteps            = new ArrayList<>();
    private List<OperationRelationDraft> operationRelations = new ArrayList<>();

    @Data
    public static class ProductDraft {
        private String uid;
        private String extUid;
        private String name;
        private String context;
    }

    @Data
    public static class ContainerDraft {
        private String uid;
        private String extUid;
        private String name;
        private String productUid;
        private String context;
    }

    @Data
    public static class E2eScenarioDraft {
        private String uid;
        private String extUid;
        private String name;
        private String description;
        private String biStepUid;

        private String context;
    }

    @Data
    public static class InterfaceDraft {
        private String uid;
        private String extUid;
        private String protocol;
        private String name;
        private String source;
        private String containerUid;
        private String context;
    }

    @Data
    public static class OperationDraft {
        private String extUid;
        private String interfaceUid;
        private String name;
        private String type;
        private Double rps;
        private Double latency;
        private Double errorRate;
        private String c4MethodUid;
        private Integer c4MethodInterfaceId;
        private Integer connectionOperationId;
        private Integer connectionInterfaceId;
        private Map<String, Object> matchedOperation;

        private String context;
    }

    @Data
    public static class BiStepDraft {
        private String uid;
        private String name;
        private Double rps;
        private Double latency;
        private Double errorRate;

        private String context;

        private String extUid;

        private String sourceId;
    }

    @Data
    public static class OperationRelationDraft {
        private String  callerOperationExtUid;
        private String  calleeOperationExtUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
    }
}
