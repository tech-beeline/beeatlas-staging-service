/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.RawDataRef;

import java.util.Optional;

public interface RawDataRefRepository extends JpaRepository<RawDataRef, Long> {

    Optional<RawDataRef> findTopByArtifactUidOrderByLoadedAtDesc(String artifactUid);

    @Query(value = """
            INSERT INTO staging.raw_data_refs
                (artifact_uid, artifact_type, source_id, format, raw_content, content_hash, size_bytes, loaded_at, updated_at)
            VALUES (:artifactUid, :artifactType, :sourceId, :format, :rawContent, :contentHash, :sizeBytes, now(), now())
            ON CONFLICT (artifact_uid, artifact_type, content_hash)
            DO UPDATE SET updated_at = now()
            RETURNING id, (xmax = 0) AS inserted
            """, nativeQuery = true)
    UpsertResult upsertByContentHash(@Param("artifactUid") String artifactUid,
                                      @Param("artifactType") String artifactType,
                                      @Param("sourceId") String sourceId,
                                      @Param("format") String format,
                                      @Param("rawContent") byte[] rawContent,
                                      @Param("contentHash") String contentHash,
                                      @Param("sizeBytes") long sizeBytes);

    interface UpsertResult {
        Long getId();
        Boolean getInserted();
    }
}
