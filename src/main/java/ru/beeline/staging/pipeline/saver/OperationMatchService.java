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

@Service
@RequiredArgsConstructor
public class OperationMatchService {

    private final OperationRepository        operationRepository;
    private final OperationVersionRepository operationVersionRepository;
    private final ArtifactNoticeService      noticeService;

    @Transactional
    public OperationVersion matchOrCreate(String extUid, String name, String type,
                                           Double rps, Double latency, Double errorRate,
                                           InterfaceVersion ifaceVersionOrNull, String jsonPointer,
                                           Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        OperationEntity entity = operationRepository.findByExtUid(extUid).orElseGet(() -> {
            created[0] = true;
            OperationEntity e = new OperationEntity();
            e.setExtUid(extUid);
            e.setCreatedAt(LocalDateTime.now());
            return operationRepository.save(e);
        });
        if (entity.getInterfaceId() == null && ifaceVersionOrNull != null) {
            entity.setInterfaceId(ifaceVersionOrNull.getInterfaceId());
        }
        entity.setName(name);
        entity.setType(type);
        operationRepository.save(entity);

        String code = created[0] ? "match.operation.created" : "match.operation.matched_by_ext_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, extUid, jsonPointer);

        OperationVersion version = new OperationVersion();
        version.setOperationId(entity.getId());
        version.setInterfaceVersionId(ifaceVersionOrNull != null ? ifaceVersionOrNull.getId() : null);
        version.setName(name);
        version.setType(type);
        version.setRps(toDecimal(rps));
        version.setLatency(toDecimal(latency));
        version.setErrorRate(toDecimal(errorRate));
        version.setRawDataRefId(rawDataRefId);
        version.setBatchId(batchId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return operationVersionRepository.save(version);
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
