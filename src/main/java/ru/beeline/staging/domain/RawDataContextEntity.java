package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "raw_data_context", schema = "staging")
public class RawDataContextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "raw_data_ref_id", nullable = false)
    private Long rawDataRefId;

    @Column(name = "format", nullable = false)
    private String format;

    @Column(name = "navigation_type", nullable = false)
    private String navigationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "position", columnDefinition = "jsonb", nullable = false)
    private String position;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
