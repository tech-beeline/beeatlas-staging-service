package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "sequence_relation_versions", schema = "staging")
public class SequenceRelationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_version_id")
    private Long sequenceVersionId;

    @Column(name = "caller_operation_version_id")
    private Long callerOperationVersionId;

    @Column(name = "callee_operation_version_id")
    private Long calleeOperationVersionId;

    @Column(name = "call_order")
    private Integer callOrder;

    @Column(name = "stereotype")
    private String stereotype;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "context")
    private String context;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
