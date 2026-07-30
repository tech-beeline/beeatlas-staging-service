package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "sequence_relation_versions", schema = "staging")
public class SequenceRelationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_version_id")
    private Long sequenceVersionId;

    @Column(name = "operation_version_id")
    private Long operationVersionId;

    @Column(name = "call_order")
    private Integer callOrder;

    @Column(name = "stereotype")
    private String stereotype;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
