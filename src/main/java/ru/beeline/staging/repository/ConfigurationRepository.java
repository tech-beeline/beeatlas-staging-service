package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.Configuration;

import java.util.List;

public interface ConfigurationRepository extends JpaRepository<Configuration, Long> {
    List<Configuration> findByArtifactTypeAndEnabledTrue(String artifactType);
}
