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
@Table(name = "bi_step_relation_versions", schema = "staging")
public class BiStepRelationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bi_step_version_id")
    private Long biStepVersionId;

    @Column(name = "operation_version_id")
    private Long operationVersionId;

    /**
     * Неосновные атрибуты связи BI-шаг↔операция в JSONB-колонке json_data
     * (BLG-004/ADR-011, V0008). Ключи snake_case: call_order, stereotype.
     * NULL/{} семантически равны пустому набору (FR-003-22, BR-18).
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
