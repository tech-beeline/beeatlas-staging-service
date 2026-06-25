package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One immutable version of an artifactType's module sequence. A new row is appended only
 * when the sequence actually changes (see ModuleCatalogPublisher) — old rows are kept
 * forever and never edited, so historical pipeline_runs.pipeline_definition_id keeps
 * pointing at whatever sequence was actually current when that run started, even after a
 * later deploy changes the wiring. isCurrent=true marks the one version new runs resolve to.
 */
@Getter
@Setter
@Entity
@Table(name = "pipeline_definitions", schema = "staging")
public class PipelineDefinitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    /** Ordered list of {"stage": "...", "moduleCode": "..."}, in PipelineDefinitions.STAGE_ORDER order. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modules_sequence", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, String>> modulesSequence;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
