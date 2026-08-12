package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * Неосновные атрибуты контейнера в JSONB-колонке json_data (BLG-004/ADR-011, V0008).
     * Ключи snake_case: version, description, technology. NULL/{} семантически равны
     * пустому набору (FR-003-22, BR-18).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "json_data", columnDefinition = "jsonb")
    private String jsonData;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
