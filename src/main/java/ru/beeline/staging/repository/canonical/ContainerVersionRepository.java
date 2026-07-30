package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.ContainerVersion;

public interface ContainerVersionRepository extends JpaRepository<ContainerVersion, Long> {
}
