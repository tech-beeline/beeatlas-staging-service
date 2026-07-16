package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.canonical.Container;

import java.util.Optional;

public interface ContainerRepository extends JpaRepository<Container, Long> {
    Optional<Container> findByUid(String uid);
}
