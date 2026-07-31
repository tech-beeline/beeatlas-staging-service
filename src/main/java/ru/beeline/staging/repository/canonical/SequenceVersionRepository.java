/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.SequenceVersion;

import java.util.List;

public interface SequenceVersionRepository extends JpaRepository<SequenceVersion, Long> {
    List<SequenceVersion> findBySequenceId(Long sequenceId);
}
