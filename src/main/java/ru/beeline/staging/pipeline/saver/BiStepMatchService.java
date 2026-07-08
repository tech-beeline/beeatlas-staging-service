package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.BiStep;
import ru.beeline.staging.domain.canonical.BiStepVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.BiStepRepository;
import ru.beeline.staging.repository.canonical.BiStepVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Find-or-create + versioning for the bi_step identity, matched by uid (step_id) — same pattern as
 * InterfaceMatchService/OperationMatchService.
 */
@Service
@RequiredArgsConstructor
public class BiStepMatchService {

    private final BiStepRepository        biStepRepository;
    private final BiStepVersionRepository biStepVersionRepository;
    private final ArtifactNoticeService   noticeService;

    @Transactional
    public BiStepVersion matchOrCreate(String stepId, String name, Double rps, Double latency, Double errorRate,
                                        String externalGuid, String sourceId, String jsonPointer,
                                        Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        BiStep entity = biStepRepository.findByUid(stepId).orElseGet(() -> {
            created[0] = true;
            BiStep e = new BiStep();
            e.setUid(stepId);
            e.setCreatedAt(LocalDateTime.now());
            return biStepRepository.save(e);
        });

        String code = created[0] ? "match.bi_step.created" : "match.bi_step.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, stepId, jsonPointer);

        BiStepVersion version = new BiStepVersion();
        version.setBiStepId(entity.getId());
        version.setName(name);
        version.setRps(toDecimal(rps));
        version.setLatency(toDecimal(latency));
        version.setErrorRate(toDecimal(errorRate));
        version.setExternalGuid(externalGuid);
        version.setSourceId(sourceId);
        version.setRawDataRefId(rawDataRefId);
        version.setBatchId(batchId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return biStepVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "bi_step", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }
}
