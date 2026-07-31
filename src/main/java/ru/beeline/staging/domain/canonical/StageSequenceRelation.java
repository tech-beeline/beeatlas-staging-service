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
@Table(name = "stage_sequence_relations", schema = "staging")
public class StageSequenceRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage_id")
    private Long stageId;

    @Column(name = "sequence_relation_version_id")
    private Long sequenceRelationVersionId;

    @Column(name = "status")
    private String status;
}
