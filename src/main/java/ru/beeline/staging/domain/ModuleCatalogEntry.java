/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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
