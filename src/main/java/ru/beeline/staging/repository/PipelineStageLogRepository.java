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
}
