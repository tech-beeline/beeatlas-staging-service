package ru.beeline.staging.pipeline;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Source-agnostic intermediate representation produced by an {@link ArtifactTransformer}.
 * The Saver stage persists this into the canonical model (staging.bi_steps, interfaces,
 * operations and their *_versions tables) without needing to know which source the data
 * came from.
 */
@Data
public class CanonicalSnapshot {

    private List<InterfaceDraft>        interfaces = new ArrayList<>();
    private List<OperationDraft>        operations = new ArrayList<>();
    private List<BiStepDraft>           biSteps    = new ArrayList<>();
    private List<BiStepRelationDraft>   biStepRelations = new ArrayList<>();
    private List<OperationRelationDraft> operationRelations = new ArrayList<>();

    @Data
    public static class InterfaceDraft {
        private String uid;
        private String protocol;
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
    }

    @Data
    public static class BiStepDraft {
        private String uid;
        private String name;
        private Double rps;
        private Double latency;
        private Double errorRate;
        private String context;
    }

    /** Edge: a BI step invokes a given operation. */
    @Data
    public static class BiStepRelationDraft {
        private String  biStepUid;
        private String  operationExtUid;
        private Integer callOrder;
        private String  stereotype;
    }

    /** Edge: one operation calls another (the recursive call chain inside the sequence). */
    @Data
    public static class OperationRelationDraft {
        private String  callerOperationExtUid;
        private String  calleeOperationExtUid;
        private Integer callOrder;
        private String  stereotype;
    }
}
