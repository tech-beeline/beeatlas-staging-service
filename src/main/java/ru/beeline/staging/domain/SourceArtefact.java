package ru.beeline.staging.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One row per unique artifact instance seen in the source (by extUid) — identity/dedup
 *  bookkeeping, separate from staging.pipeline_runs (which tracks pipeline executions). */
@Getter
@Setter
@Entity
@Table(name = "source_artefacts", schema = "staging")
public class SourceArtefact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_artefact_type_id", nullable = false)
    private Long sourceArtefactTypeId;

    @Column(name = "ext_uid", nullable = false)
    private String extUid;

    @Column(name = "status", nullable = false)
    private String status = "active";

    @Column(name = "last_loaded_ref_id")
    private Long lastLoadedRefId;

    /** The pre-adapter scan (pipeline_runs row with artifactUid == null) that most recently found this extUid. */
    @Column(name = "last_seen_scan_run_id")
    private Long lastSeenScanRunId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
