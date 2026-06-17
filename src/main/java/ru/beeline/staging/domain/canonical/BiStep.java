package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "bi_steps", schema = "staging")
public class BiStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ref_id")
    private Integer refId;

    @Column(name = "uid")
    private String uid;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
