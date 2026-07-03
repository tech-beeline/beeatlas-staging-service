package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "artifact_notices", schema = "staging")
public class ArtifactNoticeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notice_type_id", nullable = false)
    private Long noticeTypeId;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "context")
    private String context;

    @Column(name = "details")
    private String details;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
