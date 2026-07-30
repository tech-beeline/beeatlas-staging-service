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
import java.util.List;
import java.util.Objects;

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

        Long interfaceVersionId = ifaceVersionOrNull != null ? ifaceVersionOrNull.getId() : null;
        BigDecimal rpsDecimal = toDecimal(rps);
        BigDecimal latencyDecimal = toDecimal(latency);
        BigDecimal errorRateDecimal = toDecimal(errorRate);

        OperationVersion latestVersion = operationVersionRepository.findTopByOperationIdOrderByIdDesc(entity.getId()).orElse(null);
        if (!created[0] && isUnchanged(latestVersion, interfaceVersionId, extUid, name, type, rpsDecimal, latencyDecimal,
                errorRateDecimal, description, returnType, techCapabilityVersionId)) {
            saveMatchNotice("match.operation.matched_unchanged", rawDataRefId, uid, jsonPointer);
            return latestVersion;
        }

        String code = created[0] ? "match.operation.created" : "match.operation.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        OperationVersion version = new OperationVersion();
        version.setOperationId(entity.getId());
        version.setInterfaceVersionId(interfaceVersionId);
        version.setExtUid(extUid);
        version.setName(name);
        version.setType(type);
        version.setRps(rpsDecimal);
        version.setLatency(latencyDecimal);
        version.setErrorRate(errorRateDecimal);
        version.setDescription(description);
        version.setReturnType(returnType);
        version.setTechCapabilityVersionId(techCapabilityVersionId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return operationVersionRepository.save(version);
    }

    private boolean isUnchanged(OperationVersion latest, Long interfaceVersionId, String extUid, String name, String type,
                                 BigDecimal rps, BigDecimal latency, BigDecimal errorRate, String description,
                                 String returnType, Long techCapabilityVersionId) {
        if (latest == null) return false;
        return Objects.equals(latest.getInterfaceVersionId(), interfaceVersionId)
                && Objects.equals(latest.getExtUid(), extUid)
                && Objects.equals(latest.getName(), name)
                && Objects.equals(latest.getType(), type)
                && bdEquals(latest.getRps(), rps)
                && bdEquals(latest.getLatency(), latency)
                && bdEquals(latest.getErrorRate(), errorRate)
                && Objects.equals(latest.getDescription(), description)
                && Objects.equals(latest.getReturnType(), returnType)
                && Objects.equals(latest.getTechCapabilityVersionId(), techCapabilityVersionId);
    }

    private static boolean bdEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "operation", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }

    private static BigDecimal toDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }
}
