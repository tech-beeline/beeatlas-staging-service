package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "configurations", schema = "staging")
public class Configuration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "params_json", columnDefinition = "jsonb")
    private String paramsJson;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
