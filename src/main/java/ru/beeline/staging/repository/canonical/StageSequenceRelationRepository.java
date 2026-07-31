/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.StageSequenceRelation;

import java.util.List;

public interface StageSequenceRelationRepository extends JpaRepository<StageSequenceRelation, Long> {
    List<StageSequenceRelation> findByStageId(Long stageId);
}
