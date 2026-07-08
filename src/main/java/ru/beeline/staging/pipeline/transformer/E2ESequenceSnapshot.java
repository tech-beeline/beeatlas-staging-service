package ru.beeline.staging.pipeline.transformer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class E2ESequenceSnapshot {

    private List<InterfaceDraft>         interfaces         = new ArrayList<>();
    private List<OperationDraft>         operations         = new ArrayList<>();
    private List<BiStepDraft>            biSteps            = new ArrayList<>();
    private List<BiStepRelationDraft>    biStepRelations    = new ArrayList<>();
    private List<OperationRelationDraft> operationRelations = new ArrayList<>();

    @Data
    public static class InterfaceDraft {
        private String uid;
        private String protocol;
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

        private String externalGuid;

        private String sourceId;
    }

    @Data
    public static class BiStepRelationDraft {
        private String  biStepUid;
        private String  operationExtUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
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
