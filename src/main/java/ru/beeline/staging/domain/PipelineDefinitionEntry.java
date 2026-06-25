package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One row per (artifactType, stage) pair, rebuilt from scratch on every startup by
 * ModuleCatalogPublisher from the code-defined PipelineDefinition beans — lets you see
 * which modules will run for an entity before any pipeline run ever starts. Never edited
 * directly; the code (PipelineDefinition) is the source of truth.
 */
@Getter
@Setter
@Entity
@Table(name = "pipeline_definitions", schema = "staging")
public class PipelineDefinitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "stage", nullable = false)
    private String stage;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
