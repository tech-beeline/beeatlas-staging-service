/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.ModuleCatalogEntry;

public interface ModuleCatalogEntryRepository extends JpaRepository<ModuleCatalogEntry, String> {
}
