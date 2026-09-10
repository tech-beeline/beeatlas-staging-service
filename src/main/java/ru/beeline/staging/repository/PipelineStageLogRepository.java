/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.PipelineStageLog;

import java.util.List;

public interface PipelineStageLogRepository extends JpaRepository<PipelineStageLog, Long> {

    List<PipelineStageLog> findByRunIdOrderByStartedAt(Long runId);

    @Modifying
    @Query("UPDATE PipelineStageLog l SET l.status = 'completed', l.completedAt = CURRENT_TIMESTAMP WHERE l.id = :id")
    void markCompleted(@Param("id") Long id);

    @Modifying
    @Query("UPDATE PipelineStageLog l SET l.status = 'failed', l.completedAt = CURRENT_TIMESTAMP, l.failureReason = :reason WHERE l.id = :id")
    void markFailed(@Param("id") Long id, @Param("reason") String reason);

    // Closes every stage a stalled run left open. Each abandoned resume cycle starts a stage that
    // nothing ever finishes, so a stuck run accumulates one dangling "running" row per lease period
    // (FUNC run 1571951 held ~500 of them). Leaving those open makes the run look busy forever in
    // the UI and in any "which stage is it on" query, long after the watchdog buried the run.
    @Modifying
    @Query("UPDATE PipelineStageLog l SET l.status = 'failed', l.completedAt = CURRENT_TIMESTAMP, " +
           "l.failureReason = :reason WHERE l.runId = :runId AND l.status = 'running'")
    int abandonRunningStages(@Param("runId") Long runId, @Param("reason") String reason);

    @Query("SELECT l.stageName FROM PipelineStageLog l WHERE l.runId = :runId AND l.status = 'running' " +
           "ORDER BY l.startedAt DESC")
    List<String> findRunningStageNames(@Param("runId") Long runId);
}
