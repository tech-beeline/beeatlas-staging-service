package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "container_versions", schema = "staging")
public class ContainerVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "container_id")
    private Long containerId;

    @Column(name = "product_version_id")
    private Long productVersionId;

    @Column(name = "ext_uid")
    private String extUid;

    @Column(name = "name")
    private String name;

    @Column(name = "version")
    private String version;

    @Column(name = "description")
    private String description;

    @Column(name = "technology")
    private String technology;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
