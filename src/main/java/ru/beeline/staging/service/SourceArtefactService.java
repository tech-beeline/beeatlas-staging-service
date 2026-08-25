package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
    public void recordSeen(Configuration config, String extUid, Long scanRunId, Long runId, String name) {
        SourceArtefactType type = resolveType(config, extUid);
        if (type == null) return;

        SourceArtefact artefact = artefactRepository
                .findBySourceArtefactTypeIdAndExtUid(type.getId(), extUid)
                .orElseGet(SourceArtefact::new);
        artefact.setSourceArtefactTypeId(type.getId());
        artefact.setExtUid(extUid);
        artefact.setStatus("active");
        artefact.setLastRunId(runId);
        artefact.setLastSeenScanRunId(scanRunId);
        // FR-003-17 / BR-13: name опционален; не перезаписываем непустое значение пустым.
        // Если пришедший name имеет текст — устанавливаем (и для нового, и для существующего).
        // Если пришедший name пустой/blank — не трогаем уже сохранённое (существующее) или оставляем null (новый).
        if (StringUtils.hasText(name)) {
            artefact.setName(name);
        }
        artefact.setUpdatedAt(LocalDateTime.now());
        artefactRepository.save(artefact);
    }

    /** Called once the Adapter stage has actually persisted a raw_data_refs row for this artifact. */
    @Transactional
    public void recordLoaded(Configuration config, String extUid, Long rawDataRefId) {
        SourceArtefactType type = resolveType(config, extUid);
        if (type == null) return;

        artefactRepository.findBySourceArtefactTypeIdAndExtUid(type.getId(), extUid)
                .ifPresentOrElse(artefact -> {
                    artefact.setLastLoadedRefId(rawDataRefId);
                    artefact.setUpdatedAt(LocalDateTime.now());
                    artefactRepository.save(artefact);
                }, () -> log.warn("No source_artifacts row for extUid={} (type={}) — recordSeen should have run first",
                        extUid, type.getId()));
    }

    private SourceArtefactType resolveType(Configuration config, String extUid) {
        if (config.getSourceSystemId() == null) {
            log.warn("Configuration {} has no sourceSystemId — skipping source_artefacts bookkeeping for extUid={}",
                    config.getId(), extUid);
            return null;
        }
        return typeRepository
                .findByDataTypeIdAndSourceSystemId(config.getDataTypeId(), config.getSourceSystemId())
                .orElseGet(() -> {
                    SourceArtefactType t = new SourceArtefactType();
                    t.setDataTypeId(config.getDataTypeId());
                    t.setSourceSystemId(config.getSourceSystemId());
                    t.setName(config.getArtifactType());
                    return typeRepository.save(t);
                });
    }
}
