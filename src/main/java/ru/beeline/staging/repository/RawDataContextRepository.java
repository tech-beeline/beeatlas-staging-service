/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.RawDataContextEntity;

import java.util.Optional;

public interface RawDataContextRepository extends JpaRepository<RawDataContextEntity, Long> {

    // findFirst..., not findBy...: raw_data_refs are reused across runs (upsert by content_hash), and
    // before find-or-create landed here every re-processing of the same ref inserted another context
    // row for the same position. Those historical duplicates are still there — a unique-result query
    // blew up on them with IncorrectResultSizeDataAccessException ("query did not return a unique
    // result: N", N growing over time), failing the whole transformer stage for that artifact
    // (defect QA-2). Oldest id wins so the row that existing FKs already point at stays the survivor.
    Optional<RawDataContextEntity> findFirstByRawDataRefIdAndPositionOrderByIdAsc(Long rawDataRefId, String position);
}
