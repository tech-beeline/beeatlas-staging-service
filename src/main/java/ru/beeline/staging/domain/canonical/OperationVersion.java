package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "operation_versions", schema = "staging")
public class OperationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id")
    private Long operationId;

    @Column(name = "interface_version_id")
    private Long interfaceVersionId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type")
    private String type;

    @Column(name = "rps")
    private BigDecimal rps;

    @Column(name = "latency")
    private BigDecimal latency;

    @Column(name = "error_rate")
    private BigDecimal errorRate;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "context")
    private String context;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
