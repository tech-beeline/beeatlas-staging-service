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
@Table(name = "metric_query_template_versions", schema = "staging")
public class MetricQueryTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_query_template_id", nullable = false)
    private Long metricQueryTemplateId;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    /** Снимок metricTemplates: массив {metric_code, template} (может быть пустым, EC-007-03). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "json_data", columnDefinition = "jsonb", nullable = false)
    private String jsonData;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
