package ru.beeline.staging.pipeline.transformer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity-graph shape produced by {@link E2ESequenceTransformer}, consumed by
 * ru.beeline.staging.pipeline.saver.E2ECanonicalSaver. This is a private agreement
 * between this transformer/saver pair only — ArtifactTransformer/ArtifactSaver carry it
 * as plain JSON text, with no shared type at the interface level. A different entity type
 * defines its own snapshot shape next to its own transformer/saver, however it likes.
 */
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
        /** Where this operation was first learned about — JSON Pointer into raw_content. */
        private String context;
    }

    @Data
    public static class BiStepDraft {
        private String uid;
        private String name;
        private Double rps;
        private Double latency;
        private Double errorRate;
        /** JSON Pointer into raw_content for this step's node. */
        private String context;
        /** The e2e scenario's own GUID in Sparx (artifactUid). */
        private String externalGuid;
        /** GUID of the underlying Sparx diagram element this step's call represents. */
        private String sourceId;
    }

    /** Edge: a BI step invokes a given operation. */
    @Data
    public static class BiStepRelationDraft {
        private String  biStepUid;
        private String  operationExtUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
    }

    /** Edge: one operation calls another (the recursive call chain inside the sequence). */
    @Data
    public static class OperationRelationDraft {
        private String  callerOperationExtUid;
        private String  calleeOperationExtUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
    }
}
