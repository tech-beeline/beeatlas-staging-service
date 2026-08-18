package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "metric_query_templates", schema = "staging")
public class MetricQueryTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false)
    private String uid;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
