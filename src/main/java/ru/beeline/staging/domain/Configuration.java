package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Data
@Entity
@Table(name = "configurations", schema = "staging")
public class Configuration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "data_type_id", nullable = false)
    private Long dataTypeId;

    @Column(name = "source_system_id")
    private Long sourceSystemId;

    /** NULL means this configuration is triggered only by external events, not by Scheduler. */
    @Column(name = "schedule_interval_seconds")
    private Long scheduleIntervalSeconds;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Optional<Duration> getScheduleInterval() {
        return Optional.ofNullable(scheduleIntervalSeconds).map(Duration::ofSeconds);
    }
}
