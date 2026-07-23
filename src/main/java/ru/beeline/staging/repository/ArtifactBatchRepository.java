package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.ArtifactBatch;

import java.util.List;
import java.util.Optional;

public interface ArtifactBatchRepository extends JpaRepository<ArtifactBatch, Long> {

    List<ArtifactBatch> findByArtifactUidAndArtifactTypeOrderByCreatedAtDesc(String uid, String type);

    Optional<ArtifactBatch> findByArtifactUidAndArtifactTypeAndCurrentTrue(String uid, String type);

    @Modifying
    @Query("UPDATE ArtifactBatch b SET b.current = FALSE WHERE b.artifactUid = :uid AND b.artifactType = :type AND b.current = TRUE")
    void clearCurrentFlag(@Param("uid") String uid, @Param("type") String type);
}
