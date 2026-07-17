package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.E2eScenario;

import java.util.Optional;

public interface E2eScenarioRepository extends JpaRepository<E2eScenario, Long> {
    Optional<E2eScenario> findByUid(String uid);
}
