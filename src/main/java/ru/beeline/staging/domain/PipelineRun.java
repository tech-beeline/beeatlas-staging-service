package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "pipeline_runs", schema = "staging")
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_uid", nullable = false)
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

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "failed_stage")
    private String failedStage;

    /** Planned moduleCode sequence for this run, snapshotted from PipelineDefinition at creation —
     *  visible before/while the run executes, independent of how many stages actually completed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modules_sequence", columnDefinition = "jsonb")
    private List<String> modulesSequence;
}
