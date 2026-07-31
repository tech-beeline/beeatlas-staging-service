/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stage_tech_capability_versions", schema = "staging")
public class StageTcVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage_id", nullable = false)
    private Long stageId;

    @Column(name = "tech_capability_version_id")
    private Long tcVersionId;

    @Column(name = "status")
    private String status;

    @Column(name = "next_version_id")
    private Long nextVersionId;
}
