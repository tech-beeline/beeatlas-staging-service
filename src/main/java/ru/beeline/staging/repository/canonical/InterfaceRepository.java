/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.InterfaceEntity;

import java.util.Optional;

public interface InterfaceRepository extends JpaRepository<InterfaceEntity, Long> {
    Optional<InterfaceEntity> findByUid(String uid);
}
