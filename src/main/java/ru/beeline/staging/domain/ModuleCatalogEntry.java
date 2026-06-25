package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One row per registered module bean (ArtifactPreAdapter/Adapter/Validator/Transformer/Saver),
 * rebuilt from scratch on every startup by ModuleCatalogPublisher — this table is a generated
 * reflection of what's actually deployed in code, never edited directly.
 */
@Getter
@Setter
@Entity
@Table(name = "module_catalog", schema = "staging")
public class ModuleCatalogEntry {

    @Id
    @Column(name = "module_code")
    private String moduleCode;

    @Column(name = "module_type", nullable = false)
    private String moduleType;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
