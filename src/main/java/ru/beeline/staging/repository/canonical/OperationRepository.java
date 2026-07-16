package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.OperationEntity;

import java.util.Optional;

public interface OperationRepository extends JpaRepository<OperationEntity, Long> {
    Optional<OperationEntity> findByUid(String uid);
}
