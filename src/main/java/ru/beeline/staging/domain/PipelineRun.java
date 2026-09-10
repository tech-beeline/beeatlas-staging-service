/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

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

    // Set once, the first time startStage() runs for this run (see PipelineRunService#startStage).
    // Deliberately separate from startedAt, which stays at row-creation time — startedAt is
    // load-bearing for stuck-run/backlog detection (PipelineTickScheduler) and resume-candidate
    // ordering (PipelineResumeScheduler), both of which need "how long has this existed", not "how
    // long has it been executing".
    @Column(name = "execution_started_at")
    private LocalDateTime executionStartedAt;

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

    @Column(name = "payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "branch")
    private String branch;

    @Column(name = "supersedes_run_id")
    private Long supersedesRunId;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    // How many times this run has been claimed without completing a single stage. Incremented by
    // PipelineRunRepository#claim, reset to 0 on real progress (PipelineRunService#completeStage,
    // #finishScanWithChildren) and on a manual retry. Distinct from retryCount, which counts
    // *failures* and so never moves for a run that hangs instead of failing — the exact hole that
    // let FUNC runs 1571951/1595194 be resumed forever (see V0020).
    @Column(name = "resume_count", nullable = false)
    private Integer resumeCount = 0;

    // When a scan first found this run failed and out of auto-retries — i.e. when this artifact
    // stopped being processed. Cleared by markRetrying, so it is only ever set on a run that is
    // blocked right now. staging.recovery.blocked-recheck-interval is measured from here, not from
    // completedAt: the point is how long the artifact has been stuck, and a run can fail its last
    // attempt long before any scan re-finds the artifact (see PipelineRunService#finishScanWithChildren).
    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    // Snapshot of this scan's own children, written once at fan-out time (see
    // PipelineRunService#snapshotChildRunIds). NULL for child runs and for scans predating V0014.
    // @JdbcTypeCode required — columnDefinition alone is DDL-only (ddl-auto: none, so it's never even
    // read) and does nothing for the runtime JDBC binding. Without it Hibernate doesn't serialize
    // List<Long> as jsonb, so every write here was silently going in wrong — this is why
    // childStatsSnapshot came back empty on dev even for freshly-run scans.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "child_run_ids", columnDefinition = "jsonb")
    private List<Long> childRunIds;
}
