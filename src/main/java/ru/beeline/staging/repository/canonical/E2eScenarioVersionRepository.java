package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.E2eScenarioVersion;

public interface E2eScenarioVersionRepository extends JpaRepository<E2eScenarioVersion, Long> {
}
