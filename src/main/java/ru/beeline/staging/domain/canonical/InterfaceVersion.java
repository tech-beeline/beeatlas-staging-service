package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "interface_versions", schema = "staging")
public class InterfaceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interface_id")
    private Long interfaceId;

    @Column(name = "ext_uid")
    private String extUid;

    @Column(name = "protocol")
    private String protocol;

    @Column(name = "raw_data_ref_id")
    private Long rawDataRefId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "context")
    private String context;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
