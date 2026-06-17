package ru.beeline.staging.domain.cxbackend;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "cx_backend_cj_links", schema = "staging")
public class CxBackendCjLink {

    @Id
    @Column(name = "artifact_uid")
    private String artifactUid;

    @Column(name = "cj_id", nullable = false)
    private Long cjId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
