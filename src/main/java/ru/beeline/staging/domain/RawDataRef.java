package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "raw_data_refs", schema = "staging")
public class RawDataRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_uid")
    private String artifactUid;

    @Column(name = "artifact_type")
    private String artifactType;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "s3_bucket")
    private String s3Bucket;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "loaded_at")
    private LocalDateTime loadedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
