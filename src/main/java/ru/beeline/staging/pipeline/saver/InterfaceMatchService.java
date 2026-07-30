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
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InterfaceMatchService {

    private final InterfaceRepository        interfaceRepository;
    private final InterfaceVersionRepository interfaceVersionRepository;
    private final ArtifactNoticeService      noticeService;

    @Transactional
    public InterfaceVersion matchOrCreate(String uid, String extUid, String protocol, String name,
                                           String specLink, String version, String description, String sourceMetric,
                                           Long containerVersionId, String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        InterfaceEntity entity = interfaceRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            InterfaceEntity e = new InterfaceEntity();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return interfaceRepository.save(e);
        });

        InterfaceVersion latestVersion = interfaceVersionRepository.findTopByInterfaceIdOrderByIdDesc(entity.getId()).orElse(null);
        if (!created[0] && isUnchanged(latestVersion, extUid, protocol, name, specLink, version, description, sourceMetric, containerVersionId)) {
            saveMatchNotice("match.interface.matched_unchanged", rawDataRefId, uid, jsonPointer);
            return latestVersion;
        }

        String code = created[0] ? "match.interface.created" : "match.interface.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        InterfaceVersion versionEntity = new InterfaceVersion();
        versionEntity.setInterfaceId(entity.getId());
        versionEntity.setExtUid(extUid);
        versionEntity.setProtocol(protocol);
        versionEntity.setName(name);
        versionEntity.setSpecLink(specLink);
        versionEntity.setVersion(version);
        versionEntity.setDescription(description);
        versionEntity.setSourceMetric(sourceMetric);
        versionEntity.setContainerVersionId(containerVersionId);
        versionEntity.setCreatedAt(LocalDateTime.now());
        versionEntity.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        versionEntity.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return interfaceVersionRepository.save(versionEntity);
    }

    private boolean isUnchanged(InterfaceVersion latest, String extUid, String protocol, String name, String specLink,
                                 String version, String description, String sourceMetric, Long containerVersionId) {
        if (latest == null) return false;
        return Objects.equals(latest.getExtUid(), extUid)
                && Objects.equals(latest.getProtocol(), protocol)
                && Objects.equals(latest.getName(), name)
                && Objects.equals(latest.getSpecLink(), specLink)
                && Objects.equals(latest.getVersion(), version)
                && Objects.equals(latest.getDescription(), description)
                && Objects.equals(latest.getSourceMetric(), sourceMetric)
                && Objects.equals(latest.getContainerVersionId(), containerVersionId);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "interface", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
