package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.SourceArtefact;
import ru.beeline.staging.domain.SourceArtefactType;
import ru.beeline.staging.repository.SourceArtefactRepository;
import ru.beeline.staging.repository.SourceArtefactTypeRepository;

import java.time.LocalDateTime;

/**
 * Identity/dedup bookkeeping for artifacts seen in a source — separate from pipeline_runs
 * (which tracks pipeline executions, not source-side identity). One row per unique extUid;
 * lastSeenScanRunId links it to whichever pre-adapter scan most recently found it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceArtefactService {

    private final SourceArtefactTypeRepository typeRepository;
    private final SourceArtefactRepository     artefactRepository;

    @Transactional
    public void recordSeen(Configuration config, String extUid, Long scanRunId) {
        if (config.getSourceSystemId() == null) {
            log.warn("Configuration {} has no sourceSystemId — skipping source_artefacts bookkeeping for extUid={}",
                    config.getId(), extUid);
            return;
        }

        SourceArtefactType type = typeRepository
                .findByDataTypeIdAndSourceSystemId(config.getDataTypeId(), config.getSourceSystemId())
                .orElseGet(() -> {
                    SourceArtefactType t = new SourceArtefactType();
                    t.setDataTypeId(config.getDataTypeId());
                    t.setSourceSystemId(config.getSourceSystemId());
                    t.setName(config.getArtifactType());
                    return typeRepository.save(t);
                });

        SourceArtefact artefact = artefactRepository
                .findBySourceArtefactTypeIdAndExtUid(type.getId(), extUid)
                .orElseGet(SourceArtefact::new);
        artefact.setSourceArtefactTypeId(type.getId());
        artefact.setExtUid(extUid);
        artefact.setStatus("active");
        artefact.setLastSeenScanRunId(scanRunId);
        artefact.setUpdatedAt(LocalDateTime.now());
        artefactRepository.save(artefact);
    }
}
