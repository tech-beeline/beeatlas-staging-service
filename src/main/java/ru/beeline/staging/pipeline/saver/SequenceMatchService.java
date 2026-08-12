package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.SequenceEntity;
import ru.beeline.staging.domain.canonical.SequenceVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.SequenceEntityRepository;
import ru.beeline.staging.repository.canonical.SequenceVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find-or-create + versioning for the sequence identity (BLG-004/ADR-011, CMP-03).
 * Non-primary attribute (description) is serialized into {@code json_data}
 * via {@link JsonDataValidator} instead of the column setter.
 */
@Service
@RequiredArgsConstructor
public class SequenceMatchService {

    private final SequenceEntityRepository  sequenceRepository;
    private final SequenceVersionRepository sequenceVersionRepository;
    private final ArtifactNoticeService     noticeService;

    @Transactional
    public SequenceVersion matchOrCreate(String uid, String extUid, String name, String description,
                                          Long techCapabilityVersionId,
                                          String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        SequenceEntity entity = sequenceRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            SequenceEntity e = new SequenceEntity();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return sequenceRepository.save(e);
        });

        String code = created[0] ? "match.sequence.created" : "match.sequence.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        SequenceVersion version = new SequenceVersion();
        version.setSequenceId(entity.getId());
        version.setExtUid(extUid);
        version.setName(name);
        // CMP-03: serialize non-primary attribute into json_data instead of column setter
        Map<String, Object> attrs = new HashMap<>();
        if (description != null) attrs.put("description", description);
        String jsonData = JsonDataValidator.toJsonData(attrs);
        JsonDataValidator.validate(jsonData);
        version.setJsonData(jsonData);
        version.setTechCapabilityVersionId(techCapabilityVersionId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return sequenceVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "sequence", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
