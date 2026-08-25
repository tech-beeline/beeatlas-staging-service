/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find-or-create + versioning for the interface identity (BLG-004/ADR-011, CMP-03).
 * Non-primary attributes (protocol, spec_link, version, description, source_metric) are serialized
 * into {@code json_data} via {@link JsonDataValidator} instead of individual column setters.
 */
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

        String code = created[0] ? "match.interface.created" : "match.interface.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        InterfaceVersion versionEntity = new InterfaceVersion();
        versionEntity.setInterfaceId(entity.getId());
        versionEntity.setExtUid(extUid);
        versionEntity.setName(name);
        // CMP-03: serialize non-primary attributes into json_data instead of column setters
        Map<String, Object> attrs = new HashMap<>();
        if (protocol != null) attrs.put("protocol", protocol);
        if (specLink != null) attrs.put("spec_link", specLink);
        if (version != null) attrs.put("version", version);
        if (description != null) attrs.put("description", description);
        if (sourceMetric != null) attrs.put("source_metric", sourceMetric);
        String jsonData = JsonDataValidator.toJsonData(attrs);
        JsonDataValidator.validate(jsonData);
        versionEntity.setJsonData(jsonData);
        versionEntity.setContainerVersionId(containerVersionId);
        versionEntity.setCreatedAt(LocalDateTime.now());
        versionEntity.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        versionEntity.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return interfaceVersionRepository.save(versionEntity);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "interface", entityUid, null, code, null, jsonPointer, null, null, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
