package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.InterfaceVersion;

public interface InterfaceVersionRepository extends JpaRepository<InterfaceVersion, Long> {
}
