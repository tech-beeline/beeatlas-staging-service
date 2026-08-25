/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

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
@Table(name = "operation_versions", schema = "staging")
public class OperationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id")
    private Long operationId;

    @Column(name = "interface_version_id")
    private Long interfaceVersionId;

    @Column(name = "ext_uid")
    private String extUid;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Неосновные атрибуты операции в JSONB-колонке json_data (BLG-004/ADR-011, V0008).
     * Ключи snake_case: type, rps, latency, error_rate, description, return_type.
     * NULL/{} семантически равны пустому набору (FR-003-22, BR-18).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "json_data", columnDefinition = "jsonb")
    private String jsonData;

    @Column(name = "tech_capability_version_id")
    private Long techCapabilityVersionId;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
