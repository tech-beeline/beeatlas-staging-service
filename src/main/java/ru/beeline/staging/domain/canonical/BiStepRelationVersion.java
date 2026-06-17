package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "bi_step_relation_versions", schema = "staging")
public class BiStepRelationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bi_step_version_id")
    private Long biStepVersionId;

    @Column(name = "operation_version_id")
    private Long operationVersionId;

    @Column(name = "call_order")
    private Integer callOrder;

    @Column(name = "stereotype")
    private String stereotype;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "context")
    private String context;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
