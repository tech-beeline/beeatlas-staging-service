package ru.beeline.staging.pipeline.transformer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
        private String uid;    // systems[].code
        private String extUid; // systems[].code
        private String name;   // systems[].name
        private String context;
    }

    @Data
    public static class ContainerDraft {
        private String uid;        // containers[].code
        private String extUid;     // containers[].id
        private String name;       // containers[].name
        private String productUid; // resolved systems[].code owning this container, via containers[].system_id
        private String context;
    }

    @Data
    public static class E2eScenarioDraft {
        private String uid;         // entrance_diagram_uid
        private String extUid;      // entrance_diagram_uid
        private String name;        // root diagram name
        private String description; // "step_id={bi_step ext_uid}", null if step_id is missing
        private String biStepUid;   // links to BiStepDraft.uid (step_id), null if step_id is missing

        private String context;
    }

    @Data
    public static class InterfaceDraft {
        private String uid;
        private String extUid;
        private String protocol;
        private String name;
        private String source;
        private String containerUid; // resolved containers[].code owning this interface, via interfaces[].container_id
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
