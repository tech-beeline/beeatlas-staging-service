/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "artifact_batches", schema = "staging")
public class ArtifactBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_uid", nullable = false)
    private String artifactUid;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "bi_steps_count", nullable = false)
    private int biStepsCount;

    @Column(name = "interfaces_count", nullable = false)
    private int interfacesCount;

    @Column(name = "operations_count", nullable = false)
    private int operationsCount;

    @Column(name = "products_count", nullable = false)
    private int productsCount;

    @Column(name = "containers_count", nullable = false)
    private int containersCount;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
