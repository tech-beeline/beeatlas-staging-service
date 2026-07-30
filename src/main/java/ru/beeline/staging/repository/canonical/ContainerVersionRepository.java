package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.ContainerVersion;

import java.util.Optional;

public interface ContainerVersionRepository extends JpaRepository<ContainerVersion, Long> {
    Optional<ContainerVersion> findTopByContainerIdOrderByIdDesc(Long containerId);
}
