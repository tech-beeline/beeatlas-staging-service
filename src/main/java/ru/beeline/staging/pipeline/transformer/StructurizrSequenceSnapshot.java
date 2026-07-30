package ru.beeline.staging.pipeline.transformer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical snapshot produced from one Structurizr workspace.json, following the extraction order
 * from structurizr-sequence-transform-rules.md: product -> containers -> tech capabilities ->
 * interfaces -> operations -> sequences -> sequence_relations -> operation_relations. Cross-references
 * between drafts are plain uid strings; StructurizrSequenceCanonicalSaver resolves them to ids as it
 * persists each layer in order.
 */
@Data
public class StructurizrSequenceSnapshot {

    private ProductDraft                 product;
    private List<ContainerDraft>         containers         = new ArrayList<>();
    private List<TechCapabilityDraft>    techCapabilities   = new ArrayList<>();
    private List<InterfaceDraft>         interfaces         = new ArrayList<>();
    private List<OperationDraft>         operations         = new ArrayList<>();
    private List<SequenceDraft>          sequences          = new ArrayList<>();
    private List<SequenceRelationDraft>  sequenceRelations  = new ArrayList<>();
    private List<OperationRelationDraft> operationRelations = new ArrayList<>();

    @Data
    public static class ProductDraft {
        private String uid;      // model.properties.workspace_cmdb
        private String extUid;
        private String name;
        private String description;
        private String author;
        private String context;
    }

    @Data
    public static class ContainerDraft {
        private String uid;      // properties.external_name
        private String extUid;
        private String name;
        private String version;
        private String description;
        private String technology;
        private String context;
    }

    @Data
    public static class TechCapabilityDraft {
        private String uid;      // {cmdb}.{properties.code}
        private String extUid;
        private String name;
        private String description;
        private String context;
    }

    @Data
    public static class InterfaceDraft {
        private String uid;      // properties.external_name
        private String extUid;
        private String protocol;
        private String name;
        private String specLink;
        private String version;
        private String description;
        private String containerUid;
        private String context;
    }

    @Data
    public static class OperationDraft {
        private String uid;      // {interface_external_name}_{operation_name_normalized}
        private String extUid;
        private String name;
        private String type;
        private Double rps;
        private Double latency;
        private Double errorRate;
        private String interfaceUid;
        private String techCapabilityUid;
        private String context;
    }

    @Data
    public static class SequenceDraft {
        private String uid;      // dynamicView.key
        private String extUid;
        private String name;
        private String description;
        private String techCapabilityUid;
        private String context;
    }

    @Data
    public static class SequenceRelationDraft {
        private String  sequenceUid;
        private String  operationUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
    }

    @Data
    public static class OperationRelationDraft {
        private String  operationUid;
        private String  relatedOperationUid;
        private Integer callOrder;
        private String  stereotype;
        private String  context;
    }
}
