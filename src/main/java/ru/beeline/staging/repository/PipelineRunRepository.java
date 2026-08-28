/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.PipelineRun;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {

    List<PipelineRun> findByArtifactUidOrderByStartedAtDesc(String artifactUid);

    Optional<PipelineRun> findByCamundaPid(String camundaPid);

    Optional<PipelineRun> findTopByConfigurationIdAndArtifactUidIsNullAndStatusNotInOrderByStartedAtDesc(
            Long configurationId, List<String> statuses);

    Optional<PipelineRun> findTopByConfigurationIdAndArtifactUidIsNullAndStatusInOrderByCompletedAtDesc(
            Long configurationId, List<String> statuses);

    List<PipelineRun> findByConfigurationIdAndArtifactUidIsNullOrderByStartedAtDesc(
            Long configurationId, Pageable pageable);

    // One query for all configs' active scans instead of one findTop...NotIn call per config —
    // PipelineTickScheduler.tick() builds a Map<configurationId, PipelineRun> from this once per tick.
    @Query("SELECT r FROM PipelineRun r WHERE r.artifactUid IS NULL AND r.status <> 'completed' AND r.status <> 'failed'")
    List<PipelineRun> findAllActiveScans();

    // Same idea for "when did each config last finish a scan" — DISTINCT ON needs a native query,
    // backed by idx_pipeline_runs_configuration_id_scan (V0013).
    @Query(value = "SELECT DISTINCT ON (configuration_id) configuration_id AS configId, completed_at AS completedAt " +
                   "FROM staging.pipeline_runs WHERE artifact_uid IS NULL AND status IN ('completed', 'failed') " +
                   "ORDER BY configuration_id, completed_at DESC", nativeQuery = true)
    List<ConfigLastCompletion> findLastScanCompletionPerConfig();

    interface ConfigLastCompletion {
        Long getConfigId();
        LocalDateTime getCompletedAt();
    }

    // Non-authoritative: just candidates. #claim is the actual gate against two instances grabbing the same run.
    @Query("SELECT r FROM PipelineRun r WHERE r.status <> 'completed' AND r.status <> 'failed' " +
           "AND (r.ownerId IS NULL OR r.leaseExpiresAt < :now) ORDER BY r.startedAt ASC")
    List<PipelineRun> findResumeCandidates(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT r FROM PipelineRun r WHERE r.status = 'failed' AND r.retryCount < :maxRetries")
    List<PipelineRun> findFailedRetryable(@Param("maxRetries") int maxRetries, Pageable pageable);

    // @Transactional here (not just @Modifying) because claim() is called directly from
    // PipelineExecutionService — a plain, non-transactional service running on a pool thread —
    // unlike the other @Modifying methods below, which only ever run inside a PipelineRunService
    // @Transactional method. Without this, Hibernate throws TransactionRequiredException.
    // Returns 1 if this call won the race, 0 otherwise — safe under concurrent callers since
    // Postgres serializes UPDATEs on the same row.
    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.ownerId = :ownerId, r.leaseExpiresAt = :until " +
           "WHERE r.id = :runId AND r.status <> 'completed' AND r.status <> 'failed' " +
           "AND (r.ownerId IS NULL OR r.leaseExpiresAt < :now)")
    int claim(@Param("runId") Long runId, @Param("ownerId") String ownerId,
              @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.retryCount = r.retryCount + 1 WHERE r.id = :id")
    void incrementRetryCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, r.completedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void markCompleted(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'failed', r.completedAt = CURRENT_TIMESTAMP, r.failureReason = :reason, r.failedStage = :stage WHERE r.id = :id")
    void markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("stage") String stage);

    // Also clears the lease, otherwise a retry inside the previous attempt's lease window would fail claim().
    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'pending', r.failureReason = NULL, r.failedStage = NULL, " +
           "r.completedAt = NULL, r.ownerId = NULL, r.leaseExpiresAt = NULL WHERE r.id = :id")
    void markRetrying(@Param("id") Long id);
}
