package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stage_tc", schema = "staging")
public class StageTc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage_id", nullable = false)
    private Long stageId;

    @Column(name = "tc_id")
    private Long tcId;

    @Column(name = "status")
    private String status;
}
