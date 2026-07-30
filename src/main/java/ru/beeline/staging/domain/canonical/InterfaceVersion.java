package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "interface_versions", schema = "staging")
public class InterfaceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interface_id")
    private Long interfaceId;

    @Column(name = "ext_uid")
    private String extUid;

    @Column(name = "protocol")
    private String protocol;

    @Column(name = "name")
    private String name;

    @Column(name = "spec_link")
    private String specLink;

    @Column(name = "version")
    private String version;

    @Column(name = "description")
    private String description;

    @Column(name = "source_metric")
    private String sourceMetric;

    @Column(name = "container_version_id")
    private Long containerVersionId;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
