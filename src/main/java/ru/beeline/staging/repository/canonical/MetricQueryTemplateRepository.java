package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.MetricQueryTemplate;

import java.util.Optional;

public interface MetricQueryTemplateRepository extends JpaRepository<MetricQueryTemplate, Long> {
    Optional<MetricQueryTemplate> findByUid(String uid);
}
