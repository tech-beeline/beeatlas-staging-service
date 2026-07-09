package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "sequences", schema = "staging")
public class SequenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tc_id", nullable = false)
    private Long tcId;

    @Column(name = "tc_code", nullable = false)
    private String tcCode;

    @Column(name = "key")
    private String key;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
