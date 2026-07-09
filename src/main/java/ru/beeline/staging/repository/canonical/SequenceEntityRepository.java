package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.SequenceEntity;

import java.util.List;
import java.util.Optional;

public interface SequenceEntityRepository extends JpaRepository<SequenceEntity, Long> {
    List<SequenceEntity> findByTcId(Long tcId);
    Optional<SequenceEntity> findByTcCode(String tcCode);
    Optional<SequenceEntity> findByTcIdAndKey(Long tcId, String key);
}
