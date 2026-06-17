package ru.beeline.staging.repository.cxbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.cxbackend.CxBackendCjLink;

public interface CxBackendCjLinkRepository extends JpaRepository<CxBackendCjLink, String> {
}
