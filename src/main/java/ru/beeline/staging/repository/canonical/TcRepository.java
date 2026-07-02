package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.Tc;

import java.util.Optional;

public interface TcRepository extends JpaRepository<Tc, Long> {
    Optional<Tc> findByTcCode(String tcCode);
}
