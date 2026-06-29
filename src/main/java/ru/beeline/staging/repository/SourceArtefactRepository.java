package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.beeline.staging.domain.SourceArtefact;

import java.util.Optional;

public interface SourceArtefactRepository extends JpaRepository<SourceArtefact, Long> {

    Optional<SourceArtefact> findBySourceArtefactTypeIdAndExtUid(Long sourceArtefactTypeId, String extUid);
}
