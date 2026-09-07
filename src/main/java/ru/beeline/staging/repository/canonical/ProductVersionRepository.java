/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.ProductVersion;

import java.util.Optional;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, Long> {

    /**
     * Current version of a product identity, for containers whose owning system this artifact's
     * export never described (see {@code ScenarioDecomposer#ensureSelfContained}) but which another
     * artifact already brought into the catalog.
     */
    Optional<ProductVersion> findFirstByProductIdOrderByIdDesc(Long productId);
}
