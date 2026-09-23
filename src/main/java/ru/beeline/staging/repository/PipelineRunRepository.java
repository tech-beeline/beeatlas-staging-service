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
    Optional<PipelineRun> findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
            String artifactUid, String artifactType, List<String> statuses);
    @Query("SELECT r FROM PipelineRun r WHERE r.artifactUid IS NULL AND r.status <> 'completed' AND r.status <> 'failed' AND r.status <> 'cancelled'")
    List<PipelineRun> findAllActiveScans();
    @Query(value = "SELECT DISTINCT ON (configuration_id) configuration_id AS configId, completed_at AS completedAt " +
                   "FROM staging.pipeline_runs WHERE artifact_uid IS NULL AND status IN ('completed', 'failed') " +
                   "ORDER BY configuration_id, completed_at DESC", nativeQuery = true)
    List<ConfigLastCompletion> findLastScanCompletionPerConfig();

    interface ConfigLastCompletion {
        Long getConfigId();
        LocalDateTime getCompletedAt();
    }
    @Query(value = "SELECT configuration_id AS configId, count(*) AS activeCount, min(started_at) AS oldestStartedAt " +
                   "FROM staging.pipeline_runs " +
                   "WHERE artifact_uid IS NOT NULL AND status NOT IN ('completed', 'failed', 'cancelled') " +
                   "GROUP BY configuration_id", nativeQuery = true)
    List<ConfigActiveArtifactRuns> findActiveArtifactRunCountsPerConfig();

    interface ConfigActiveArtifactRuns {
        Long getConfigId();
        Long getActiveCount();
        LocalDateTime getOldestStartedAt();
    }

    @Query("SELECT r FROM PipelineRun r WHERE r.status <> 'completed' AND r.status <> 'failed' AND r.status <> 'cancelled' " +
           "AND r.status <> 'awaiting_review' AND r.status <> 'reviewing' AND r.status <> 'applying' " +
           "AND r.status <> 'completed_without_publish' " +
           "AND (r.ownerId IS NULL OR r.leaseExpiresAt < :now) ORDER BY r.startedAt ASC")
    List<PipelineRun> findResumeCandidates(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT r FROM PipelineRun r WHERE r.status = 'failed' AND r.retryCount < :maxRetries")
    List<PipelineRun> findFailedRetryable(@Param("maxRetries") int maxRetries, Pageable pageable);

    @Query("SELECT r FROM PipelineRun r WHERE r.status <> 'completed' AND r.status <> 'failed' AND r.status <> 'cancelled' " +
           "AND r.status <> 'awaiting_review' AND r.status <> 'reviewing' AND r.status <> 'applying' " +
           "AND r.status <> 'completed_without_publish' " +
           "AND r.resumeCount >= :maxResumeAttempts " +
           "AND (r.leaseExpiresAt IS NULL OR r.leaseExpiresAt < :now) ORDER BY r.startedAt ASC")
    List<PipelineRun> findStalled(@Param("maxResumeAttempts") int maxResumeAttempts,
                                  @Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT r FROM PipelineRun r WHERE r.artifactUid IS NOT NULL AND r.status = 'failed' " +
           "AND r.retryCount >= :maxRetries AND (:artifactType IS NULL OR r.artifactType = :artifactType) " +
           "ORDER BY r.startedAt ASC")
    List<PipelineRun> findBlocked(@Param("artifactType") String artifactType,
                                  @Param("maxRetries") int maxRetries, Pageable pageable);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.ownerId = :ownerId, r.leaseExpiresAt = :until, " +
           "r.resumeCount = r.resumeCount + 1 " +
           "WHERE r.id = :runId AND r.status <> 'completed' AND r.status <> 'failed' AND r.status <> 'cancelled' " +
           "AND (r.ownerId IS NULL OR r.leaseExpiresAt < :now)")
    int claim(@Param("runId") Long runId, @Param("ownerId") String ownerId,
              @Param("until") LocalDateTime until, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.retryCount = r.retryCount + 1 WHERE r.id = :id")
    void incrementRetryCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.resumeCount = 0 WHERE r.id = :id AND r.resumeCount <> 0")
    void resetResumeCount(@Param("id") Long id);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PipelineRun r SET r.status = 'failed', r.completedAt = CURRENT_TIMESTAMP, " +
           "r.failureReason = :reason, r.failedStage = :stage, r.retryCount = :retryCount, " +
           "r.ownerId = NULL, r.leaseExpiresAt = NULL, " +
           "r.blockedAt = COALESCE(r.blockedAt, CURRENT_TIMESTAMP) " +
           "WHERE r.id = :id AND r.status <> 'completed' AND r.status <> 'failed' AND r.status <> 'cancelled'")
    int markStalled(@Param("id") Long id, @Param("reason") String reason,
                    @Param("stage") String stage, @Param("retryCount") int retryCount);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, r.completedAt = CURRENT_TIMESTAMP " +
           "WHERE r.id = :id AND r.status <> 'cancelled'")
    void markCompleted(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'failed', r.completedAt = CURRENT_TIMESTAMP, r.failureReason = :reason, r.failedStage = :stage " +
           "WHERE r.id = :id AND r.status <> 'cancelled'")
    void markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("stage") String stage);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PipelineRun r SET r.status = 'cancelled', r.completedAt = CURRENT_TIMESTAMP, r.failureReason = :reason, " +
           "r.ownerId = NULL, r.leaseExpiresAt = NULL " +
           "WHERE r.id = :id AND r.status NOT IN ('completed', 'failed', 'cancelled', 'saving', 'publishing', 'applying')")
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.draftJson = :draftJson WHERE r.id = :id")
    int saveDraftJson(@Param("id") Long id, @Param("draftJson") String draftJson);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, " +
           "r.executionStartedAt = COALESCE(r.executionStartedAt, CURRENT_TIMESTAMP) " +
           "WHERE r.id = :id AND r.status <> 'cancelled'")
    int advanceStage(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, " +
           "r.ownerId = NULL, r.leaseExpiresAt = NULL " +
           "WHERE r.id = :id AND r.status <> 'cancelled'")
    int pause(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'reviewing' " +
           "WHERE r.id = :id AND r.status IN ('awaiting_review', 'reviewing')")
    int markReviewing(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'applying' " +
           "WHERE r.id = :id AND r.status IN ('awaiting_review', 'reviewing')")
    int markApplying(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'completed_without_publish', r.completedAt = CURRENT_TIMESTAMP " +
           "WHERE r.id = :id AND r.status IN ('awaiting_review', 'reviewing')")
    int markDeclined(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.rawDataRefId = :rawDataRefId WHERE r.id = :id")
    void updateRawDataRefId(@Param("id") Long id, @Param("rawDataRefId") Long rawDataRefId);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE staging.pipeline_runs
               SET child_run_ids = COALESCE((SELECT jsonb_agg(e)
                                             FROM jsonb_array_elements(child_run_ids) e
                                             WHERE CAST(CAST(e AS text) AS bigint) <> :childId),
                                            CAST('[]' AS jsonb))
             WHERE id = :scanRunId AND child_run_ids IS NOT NULL
            """, nativeQuery = true)
    void removeChildFromSnapshot(@Param("scanRunId") Long scanRunId, @Param("childId") Long childId);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.parentRunId = :parentRunId WHERE r.id = :id")
    void updateParentRunId(@Param("id") Long id, @Param("parentRunId") Long parentRunId);

    @Transactional
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'pending', r.failureReason = NULL, r.failedStage = NULL, " +
           "r.completedAt = NULL, r.ownerId = NULL, r.leaseExpiresAt = NULL, r.blockedAt = NULL, " +
           "r.resumeCount = 0 WHERE r.id = :id")
    void markRetrying(@Param("id") Long id);
}
