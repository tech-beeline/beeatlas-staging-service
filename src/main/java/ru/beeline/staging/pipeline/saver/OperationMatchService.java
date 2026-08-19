package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.domain.canonical.OperationEntity;
import ru.beeline.staging.domain.canonical.OperationVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.OperationRepository;
import ru.beeline.staging.repository.canonical.OperationVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find-or-create + versioning for the operation identity (BLG-004/ADR-011, CMP-03).
 * Non-primary attributes (type, rps, latency, error_rate, description, return_type) are serialized
 * into {@code json_data} via {@link JsonDataValidator} instead of individual column setters.
 */
@Service
@RequiredArgsConstructor
public class OperationMatchService {

    private final OperationRepository        operationRepository;
    private final OperationVersionRepository operationVersionRepository;
    private final ArtifactNoticeService      noticeService;

    @Transactional
    public OperationVersion matchOrCreate(String uid, String extUid, String name, String type,
                                           Double rps, Double latency, Double errorRate,
                                           String description, String returnType, Long techCapabilityVersionId,
                                           InterfaceVersion ifaceVersionOrNull, String jsonPointer,
                                           Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        OperationEntity entity = operationRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            OperationEntity e = new OperationEntity();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return operationRepository.save(e);
        });

        String code = created[0] ? "match.operation.created" : "match.operation.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        OperationVersion version = new OperationVersion();
        version.setOperationId(entity.getId());
        version.setInterfaceVersionId(ifaceVersionOrNull != null ? ifaceVersionOrNull.getId() : null);
        version.setExtUid(extUid);
        version.setName(name);
        // CMP-03: serialize non-primary attributes into json_data instead of column setters
        Map<String, Object> attrs = new HashMap<>();
        if (type != null) attrs.put("type", type);
        BigDecimal rpsDec = toDecimal(rps);
        BigDecimal latencyDec = toDecimal(latency);
        BigDecimal errorRateDec = toDecimal(errorRate);
        if (rpsDec != null) attrs.put("rps", rpsDec);
        if (latencyDec != null) attrs.put("latency", latencyDec);
        if (errorRateDec != null) attrs.put("error_rate", errorRateDec);
        if (description != null) attrs.put("description", description);
        if (returnType != null) attrs.put("return_type", returnType);
        String jsonData = JsonDataValidator.toJsonData(attrs);
        JsonDataValidator.validate(jsonData);
        version.setJsonData(jsonData);
        version.setTechCapabilityVersionId(techCapabilityVersionId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return operationVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "operation", entityUid, null, code, null, jsonPointer, null, null, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }
}
