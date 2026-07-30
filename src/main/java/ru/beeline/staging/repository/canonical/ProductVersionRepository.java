package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.ProductVersion;

import java.util.Optional;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, Long> {
    Optional<ProductVersion> findTopByProductIdOrderByIdDesc(Long productId);
}
