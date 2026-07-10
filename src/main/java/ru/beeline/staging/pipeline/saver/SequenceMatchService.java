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

@Service
@RequiredArgsConstructor
public class SequenceMatchService {

    private final SequenceEntityRepository  sequenceRepository;
    private final SequenceVersionRepository sequenceVersionRepository;
    private final ArtifactNoticeService     noticeService;

    @Transactional
    public SequenceVersion matchOrCreate(Long tcId, String tcCode, String key, String name, String description,
                                          String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        SequenceEntity entity = sequenceRepository.findByTcIdAndKey(tcId, key).orElseGet(() -> {
            created[0] = true;
            SequenceEntity e = new SequenceEntity();
            e.setTcId(tcId);
            e.setTcCode(tcCode);
            e.setKey(key);
            e.setCreatedAt(LocalDateTime.now());
            return sequenceRepository.save(e);
        });

        String code = created[0] ? "match.sequence.created" : "match.sequence.matched_by_key";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, key, jsonPointer);

        SequenceVersion version = new SequenceVersion();
        version.setSequenceId(entity.getId());
        version.setName(name);
        version.setDescription(description);
        version.setTcId(tcId);
        version.setRawDataRefId(rawDataRefId);
        version.setBatchId(batchId);
        version.setContext(jsonPointer);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        return sequenceVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "sequence", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
