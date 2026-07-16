package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.SequenceEntity;

import java.util.Optional;

public interface SequenceEntityRepository extends JpaRepository<SequenceEntity, Long> {
    Optional<SequenceEntity> findByUid(String uid);
}
