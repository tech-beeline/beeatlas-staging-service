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
import java.util.List;
import java.util.Objects;

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

        SequenceVersion latestVersion = sequenceVersionRepository.findTopBySequenceIdOrderByIdDesc(entity.getId()).orElse(null);
        if (!created[0] && isUnchanged(latestVersion, extUid, name, description, techCapabilityVersionId)) {
            saveMatchNotice("match.sequence.matched_unchanged", rawDataRefId, uid, jsonPointer);
            return latestVersion;
        }

        String code = created[0] ? "match.sequence.created" : "match.sequence.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        SequenceVersion version = new SequenceVersion();
        version.setSequenceId(entity.getId());
        version.setExtUid(extUid);
        version.setName(name);
        version.setDescription(description);
        version.setTechCapabilityVersionId(techCapabilityVersionId);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return sequenceVersionRepository.save(version);
    }

    private boolean isUnchanged(SequenceVersion latest, String extUid, String name, String description, Long techCapabilityVersionId) {
        if (latest == null) return false;
        return Objects.equals(latest.getExtUid(), extUid)
                && Objects.equals(latest.getName(), name)
                && Objects.equals(latest.getDescription(), description)
                && Objects.equals(latest.getTechCapabilityVersionId(), techCapabilityVersionId);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "sequence", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
