package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.PipelineRun;

import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {

    List<PipelineRun> findByArtifactUidOrderByStartedAtDesc(String artifactUid);

    Optional<PipelineRun> findByCamundaPid(String camundaPid);

    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, r.completedAt = NOW() WHERE r.id = :id")
    void markCompleted(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = 'failed', r.completedAt = NOW(), r.failureReason = :reason, r.failedStage = :stage WHERE r.id = :id")
    void markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("stage") String stage);
}
