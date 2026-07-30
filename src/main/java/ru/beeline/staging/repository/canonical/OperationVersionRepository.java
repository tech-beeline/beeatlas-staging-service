package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.OperationVersion;

import java.util.Optional;

public interface OperationVersionRepository extends JpaRepository<OperationVersion, Long> {
    Optional<OperationVersion> findTopByOperationIdOrderByIdDesc(Long operationId);
}
