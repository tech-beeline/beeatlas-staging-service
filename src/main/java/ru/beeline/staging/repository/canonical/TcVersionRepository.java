package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.TcVersion;

import java.util.List;

public interface TcVersionRepository extends JpaRepository<TcVersion, Long> {
    List<TcVersion> findByTcId(Long tcId);
}
