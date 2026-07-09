package ru.beeline.staging.pipeline.transformer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StructurizrSequenceSnapshot {

    private TcDraft                    tc;
    private List<SequenceDraft>        sequences         = new ArrayList<>();
    private List<InterfaceDraft>       interfaces        = new ArrayList<>();
    private List<OperationDraft>       operations        = new ArrayList<>();
    private List<SequenceRelationDraft> sequenceRelations = new ArrayList<>();

    @Data
    public static class TcDraft {
        private String  tcCode;
        private String  name;
        private String  description;
        private Integer productId;
        private String  context;
    }

    @Data
    public static class SequenceDraft {
        private String key;
        private String tcCode;
        private String name;
        private String description;
        private String context;
    }

    @Data
    public static class InterfaceDraft {
        private String uid;
        private String extUid;
        private String protocol;
        private String source;
        private String context;
    }

    @Data
    public static class OperationDraft {
        private String extUid;
        private String interfaceUid;
        private String name;
        private String type;
        private String context;
    }

    @Data
    public static class SequenceRelationDraft {
        private String  sequenceKey;
        private String  callerOperationExtUid;
        private String  calleeOperationExtUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
    }
}
