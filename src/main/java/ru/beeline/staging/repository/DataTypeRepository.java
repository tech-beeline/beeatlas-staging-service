/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.DataType;

import java.util.Optional;

public interface DataTypeRepository extends JpaRepository<DataType, Integer> {
    Optional<DataType> findByCode(String code);
}
