package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "pipeline_runs", schema = "staging")
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_uid")
    private String artifactUid;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "configuration_id")
    private Long configurationId;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "batch_id")
    private String batchId;

    @Column(name = "status", nullable = false)
    private String status = "pending";

    @Column(name = "camunda_pid")
    private String camundaPid;

    @Column(name = "execution_id")
    private String executionId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "failed_stage")
    private String failedStage;

    @Column(name = "pipeline_definition_id")
    private Long pipelineDefinitionId;

    @Column(name = "parent_run_id")
    private Long parentRunId;
}
