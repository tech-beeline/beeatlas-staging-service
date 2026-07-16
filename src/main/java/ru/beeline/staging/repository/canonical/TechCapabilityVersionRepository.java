package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.TechCapabilityVersion;

import java.util.List;

public interface TechCapabilityVersionRepository extends JpaRepository<TechCapabilityVersion, Long> {
    List<TechCapabilityVersion> findByTechCapabilityId(Long techCapabilityId);
}
