package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "bi_step_versions", schema = "staging")
public class BiStepVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bi_step_id")
    private Long biStepId;

    @Column(name = "name")
    private String name;

    @Column(name = "rps")
    private BigDecimal rps;

    @Column(name = "latency")
    private BigDecimal latency;

    @Column(name = "error_rate")
    private BigDecimal errorRate;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "context")
    private String context;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
