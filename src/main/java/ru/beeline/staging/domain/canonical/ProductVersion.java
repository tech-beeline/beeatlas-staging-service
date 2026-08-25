/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.domain.canonical;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "product_versions", schema = "staging")
public class ProductVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "ext_uid")
    private String extUid;

    @Column(name = "name")
    private String name;

    /**
     * Неосновные атрибуты продукта в JSONB-колонке json_data (BLG-004/ADR-011, V0008).
     * Ключи snake_case: description, author. NULL/{} семантически равны пустому набору
     * (FR-003-22, BR-18). CHECK (json_data IS NULL OR jsonb_typeof(json_data) = 'object').
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "json_data", columnDefinition = "jsonb")
    private String jsonData;

    @Column(name = "raw_data_context_id")
    private Long rawDataContextId;

    @Column(name = "match_notice_id")
    private Long matchNoticeId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
