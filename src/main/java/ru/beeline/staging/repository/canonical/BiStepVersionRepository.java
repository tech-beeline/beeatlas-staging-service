package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.BiStepVersion;

import java.util.Optional;

public interface BiStepVersionRepository extends JpaRepository<BiStepVersion, Long> {
    Optional<BiStepVersion> findTopByBiStepIdOrderByIdDesc(Long biStepId);
}
