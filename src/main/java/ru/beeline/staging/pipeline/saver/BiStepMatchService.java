/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find-or-create + versioning for the bi_step identity, matched by uid (step_id) — same pattern as
 * InterfaceMatchService/OperationMatchService.
 * <p>
 * Non-primary attributes (rps, latency, error_rate, source_id) are serialized into {@code json_data}
 * via {@link JsonDataValidator} instead of individual column setters (BLG-004/ADR-011, CMP-03).
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
        version.setExtUid(externalGuid);
        version.setName(name);
        // CMP-03: serialize non-primary attributes into json_data instead of column setters
        Map<String, Object> attrs = new HashMap<>();
        BigDecimal rpsDec = toDecimal(rps);
        BigDecimal latencyDec = toDecimal(latency);
        BigDecimal errorRateDec = toDecimal(errorRate);
        if (rpsDec != null) attrs.put("rps", rpsDec);
        if (latencyDec != null) attrs.put("latency", latencyDec);
        if (errorRateDec != null) attrs.put("error_rate", errorRateDec);
        if (sourceId != null) attrs.put("source_id", sourceId);
        String jsonData = JsonDataValidator.toJsonData(attrs);
        JsonDataValidator.validate(jsonData);
        version.setJsonData(jsonData);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return biStepVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "bi_step", entityUid, null, code, null, jsonPointer, null, null, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }
}
