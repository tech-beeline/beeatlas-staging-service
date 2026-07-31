/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.TechCapability;
import ru.beeline.staging.domain.canonical.TechCapabilityVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.TechCapabilityRepository;
import ru.beeline.staging.repository.canonical.TechCapabilityVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TechCapabilityMatchService {

    private final TechCapabilityRepository        techCapabilityRepository;
    private final TechCapabilityVersionRepository techCapabilityVersionRepository;
    private final ArtifactNoticeService           noticeService;

    @Transactional
    public TechCapabilityVersion matchOrCreate(String uid, String extUid, String name, String description,
                                                String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        TechCapability entity = techCapabilityRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            TechCapability e = new TechCapability();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return techCapabilityRepository.save(e);
        });

        String code = created[0] ? "match.tech_capability.created" : "match.tech_capability.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        TechCapabilityVersion version = new TechCapabilityVersion();
        version.setTechCapabilityId(entity.getId());
        version.setExtUid(extUid);
        version.setName(name);
        version.setDescription(description);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return techCapabilityVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "tech_capability", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
