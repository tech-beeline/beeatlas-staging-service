package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.Container;
import ru.beeline.staging.domain.canonical.ContainerVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.ContainerRepository;
import ru.beeline.staging.repository.canonical.ContainerVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find-or-create + versioning for the container identity (BLG-004/ADR-011, CMP-03).
 * Non-primary attributes (version, description, technology) are serialized into {@code json_data}
 * via {@link JsonDataValidator} instead of individual column setters.
 */
@Service
@RequiredArgsConstructor
public class ContainerMatchService {

    private final ContainerRepository        containerRepository;
    private final ContainerVersionRepository containerVersionRepository;
    private final ArtifactNoticeService      noticeService;

    @Transactional
    public ContainerVersion matchOrCreate(String uid, String extUid, String name, String version, String description,
                                           String technology, Long productVersionId,
                                           String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        Container entity = containerRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            Container e = new Container();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return containerRepository.save(e);
        });

        String code = created[0] ? "match.container.created" : "match.container.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        ContainerVersion containerVersion = new ContainerVersion();
        containerVersion.setContainerId(entity.getId());
        containerVersion.setProductVersionId(productVersionId);
        containerVersion.setExtUid(extUid);
        containerVersion.setName(name);
        // CMP-03: serialize non-primary attributes into json_data instead of column setters
        Map<String, Object> attrs = new HashMap<>();
        if (version != null) attrs.put("version", version);
        if (description != null) attrs.put("description", description);
        if (technology != null) attrs.put("technology", technology);
        String jsonData = JsonDataValidator.toJsonData(attrs);
        JsonDataValidator.validate(jsonData);
        containerVersion.setJsonData(jsonData);
        containerVersion.setCreatedAt(LocalDateTime.now());
        containerVersion.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        containerVersion.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return containerVersionRepository.save(containerVersion);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "container", entityUid, null, code, null, jsonPointer, null, null, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
