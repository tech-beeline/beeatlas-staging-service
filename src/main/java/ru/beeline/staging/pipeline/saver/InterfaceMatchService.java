package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.InterfaceEntity;
import ru.beeline.staging.domain.canonical.InterfaceVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.InterfaceRepository;
import ru.beeline.staging.repository.canonical.InterfaceVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InterfaceMatchService {

    private final InterfaceRepository        interfaceRepository;
    private final InterfaceVersionRepository interfaceVersionRepository;
    private final ArtifactNoticeService      noticeService;

    @Transactional
    public InterfaceVersion matchOrCreate(String uid, String extUid, String protocol, String source,
                                           String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        InterfaceEntity entity = interfaceRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            InterfaceEntity e = new InterfaceEntity();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return interfaceRepository.save(e);
        });

        String code = created[0] ? "match.interface.created" : "match.interface.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        InterfaceVersion version = new InterfaceVersion();
        version.setInterfaceId(entity.getId());
        version.setExtUid(extUid);
        version.setProtocol(protocol);
        version.setSource(source);
        version.setRawDataRefId(rawDataRefId);
        version.setBatchId(batchId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return interfaceVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "interface", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
