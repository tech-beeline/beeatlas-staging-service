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

    @Column(name = "ext_uid")
    private String extUid;

    @Column(name = "name")
    private String name;

    @Column(name = "rps")
    private BigDecimal rps;

    @Column(name = "latency")
    private BigDecimal latency;

    @Column(name = "error_rate")
    private BigDecimal errorRate;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
