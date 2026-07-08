package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.RawDataContextEntity;

import java.util.UUID;

public interface RawDataContextRepository extends JpaRepository<RawDataContextEntity, UUID> {
}
