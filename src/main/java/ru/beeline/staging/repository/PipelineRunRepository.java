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

    // Backed by idx_pipeline_runs_artifact_uid_type (V0001) — lets finishScanWithChildren reuse an
    // already-queued run for an artifact instead of creating a duplicate every scan cycle.
    Optional<PipelineRun> findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
            String artifactUid, String artifactType, List<String> statuses);

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

    // One query for all configs' still-unfinished artifact runs. The scan record itself
    // (artifact_uid IS NULL) is marked completed right after fan-out — well before its children
    // finish (PipelineExecutionService#executeScan) — so PipelineTickScheduler also needs this to
    // know a previous scan's artifacts are still being worked through before starting another one.
    @Query(value = "SELECT configuration_id AS configId, count(*) AS activeCount, min(started_at) AS oldestStartedAt " +
                   "FROM staging.pipeline_runs " +
                   "WHERE artifact_uid IS NOT NULL AND status NOT IN ('completed', 'failed') " +
                   "GROUP BY configuration_id", nativeQuery = true)
    List<ConfigActiveArtifactRuns> findActiveArtifactRunCountsPerConfig();

    interface ConfigActiveArtifactRuns {
        Long getConfigId();
        Long getActiveCount();
        LocalDateTime getOldestStartedAt();
    }

    // Non-authoritative: just candidates. #claim is the actual gate against two instances grabbing the same run.
    @Query("SELECT r FROM PipelineRun r WHERE r.status <> 'completed' AND r.status <> 'failed' " +
           "AND (r.ownerId IS NULL OR r.leaseExpiresAt < :now) ORDER BY r.startedAt ASC")
    List<PipelineRun> findResumeCandidates(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT r FROM PipelineRun r WHERE r.status = 'failed' AND r.retryCount < :maxRetries")
    List<PipelineRun> findFailedRetryable(@Param("maxRetries") int maxRetries, Pageable pageable);

    // Runs the resume loop has re-claimed maxResumeAttempts times without a single stage completing
    // in between — resumeCount is reset on every completed stage, so a run only reaches the
    // threshold if it is making no progress at all. The lease check keeps a run that some instance
    // is legitimately working on right now out of the watchdog's reach; only an expired (or never
    // taken) lease qualifies. Backed by idx_pipeline_runs_stalled (V0020).
    @Query("SELECT r FROM PipelineRun r WHERE r.status <> 'completed' AND r.status <> 'failed' " +
           "AND r.resumeCount >= :maxResumeAttempts " +
           "AND (r.leaseExpiresAt IS NULL OR r.leaseExpiresAt < :now) ORDER BY r.startedAt ASC")
    List<PipelineRun> findStalled(@Param("maxResumeAttempts") int maxResumeAttempts,
                                  @Param("now") LocalDateTime now, Pageable pageable);

    // The complement of findFailedRetryable: artifact runs nothing will touch again on its own —
    // the auto-retry sweep is out of attempts and a re-scan no longer adopts them. Surfaced through
    // /admin/pipeline-runs/blocked so they don't sit unnoticed (defect QA-1).
    @Query("SELECT r FROM PipelineRun r WHERE r.artifactUid IS NOT NULL AND r.status = 'failed' " +
           "AND r.retryCount >= :maxRetries AND (:artifactType IS NULL OR r.artifactType = :artifactType) " +
           "ORDER BY r.startedAt ASC")
    List<PipelineRun> findBlocked(@Param("artifactType") String artifactType,
                                  @Param("maxRetries") int maxRetries, Pageable pageable);

    // @Transactional here (not just @Modifying) because claim() is called directly from
    // PipelineExecutionService — a plain, non-transactional service running on a pool thread —
    // unlike the other @Modifying methods below, which only ever run inside a PipelineRunService
    // @Transactional method. Without this, Hibernate throws TransactionRequiredException.
    // Returns 1 if this call won the race, 0 otherwise — safe under concurrent callers since
    // Postgres serializes UPDATEs on the same row.
    // resumeCount is bumped here rather than in the scheduler so that it counts exactly the claims
    // that actually happened — a claim lost to another instance must not consume the budget.
    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.ownerId = :ownerId, r.leaseExpiresAt = :until, " +
           "r.resumeCount = r.resumeCount + 1 " +
           "WHERE r.id = :runId AND r.status <> 'completed' AND r.status <> 'failed' " +
           "AND (r.ownerId IS NULL OR r.leaseExpiresAt < :now)")
    int claim(@Param("runId") Long runId, @Param("ownerId") String ownerId,
              @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.retryCount = r.retryCount + 1 WHERE r.id = :id")
    void incrementRetryCount(@Param("id") Long id);

    // Called when a stage completes: the run demonstrably moved, so its no-progress budget starts
    // over. Without this the watchdog would eventually kill healthy multi-stage chains whose stages
    // outlive the lease.
    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.resumeCount = 0 WHERE r.id = :id AND r.resumeCount <> 0")
    void resetResumeCount(@Param("id") Long id);

    // The watchdog's terminal write. retryCount is pinned at the auto-retry ceiling on purpose:
    // a run that never progressed would otherwise be picked straight back up by
    // PipelineResumeScheduler#autoRetryFailedRuns and re-enter the same loop through the front
    // door. blockedAt is stamped (kept if already set) so the run reads as "stuck since", and the
    // lease is cleared so nothing claims the corpse.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PipelineRun r SET r.status = 'failed', r.completedAt = CURRENT_TIMESTAMP, " +
           "r.failureReason = :reason, r.failedStage = :stage, r.retryCount = :retryCount, " +
           "r.ownerId = NULL, r.leaseExpiresAt = NULL, " +
           "r.blockedAt = COALESCE(r.blockedAt, CURRENT_TIMESTAMP) " +
           "WHERE r.id = :id AND r.status <> 'completed' AND r.status <> 'failed'")
    int markStalled(@Param("id") Long id, @Param("reason") String reason,
                    @Param("stage") String stage, @Param("retryCount") int retryCount);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, r.completedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void markCompleted(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'failed', r.completedAt = CURRENT_TIMESTAMP, r.failureReason = :reason, r.failedStage = :stage WHERE r.id = :id")
    void markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("stage") String stage);

    // Drops one id from a scan's child_run_ids snapshot, used when a re-queued run's ownership moves
    // to a newer scan (PipelineRunService#reassignParent). Rebuilt via jsonb_agg rather than the
    // `jsonb - text` operator: that operator only removes *string* elements, and this array holds
    // numbers. COALESCE keeps an emptied snapshot as [] instead of NULL, which would read as
    // "pre-V0014 scan, no snapshot taken" in ScanRunRepository.
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE staging.pipeline_runs
               SET child_run_ids = COALESCE((SELECT jsonb_agg(e)
                                             FROM jsonb_array_elements(child_run_ids) e
                                             WHERE e::text::bigint <> :childId), '[]'::jsonb)
             WHERE id = :scanRunId AND child_run_ids IS NOT NULL
            """, nativeQuery = true)
    void removeChildFromSnapshot(@Param("scanRunId") Long scanRunId, @Param("childId") Long childId);

    // Also clears the lease, otherwise a retry inside the previous attempt's lease window would fail
    // claim(). blockedAt goes too: the run is moving again, so "blocked since" would be a lie — and
    // if it fails its way back into a block, the next scan stamps a fresh, honest timestamp.
    @Transactional
    @Modifying
    // resumeCount goes back to 0 too: a retry is a fresh attempt and deserves a fresh no-progress
    // budget, otherwise a run retried near the watchdog threshold would be killed on its first
    // lease cycle.
    @Query("UPDATE PipelineRun r SET r.status = 'pending', r.failureReason = NULL, r.failedStage = NULL, " +
           "r.completedAt = NULL, r.ownerId = NULL, r.leaseExpiresAt = NULL, r.blockedAt = NULL, " +
           "r.resumeCount = 0 WHERE r.id = :id")
    void markRetrying(@Param("id") Long id);
}
