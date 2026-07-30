package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.E2eScenario;
import ru.beeline.staging.domain.canonical.E2eScenarioVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.E2eScenarioRepository;
import ru.beeline.staging.repository.canonical.E2eScenarioVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class E2eScenarioMatchService {

    private final E2eScenarioRepository        e2eScenarioRepository;
    private final E2eScenarioVersionRepository e2eScenarioVersionRepository;
    private final ArtifactNoticeService        noticeService;

    @Transactional
    public E2eScenarioVersion matchOrCreate(String uid, String extUid, String name, String description,
                                             Long biStepVersionId, String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        E2eScenario entity = e2eScenarioRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            E2eScenario e = new E2eScenario();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return e2eScenarioRepository.save(e);
        });

        E2eScenarioVersion latestVersion = e2eScenarioVersionRepository.findTopByE2eScenarioIdOrderByIdDesc(entity.getId()).orElse(null);
        if (!created[0] && isUnchanged(latestVersion, biStepVersionId, extUid, name, description)) {
            saveMatchNotice("match.e2e_scenario.matched_unchanged", rawDataRefId, uid, jsonPointer);
            return latestVersion;
        }

        String code = created[0] ? "match.e2e_scenario.created" : "match.e2e_scenario.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        E2eScenarioVersion version = new E2eScenarioVersion();
        version.setE2eScenarioId(entity.getId());
        version.setBiStepVersionId(biStepVersionId);
        version.setExtUid(extUid);
        version.setName(name);
        version.setDescription(description);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return e2eScenarioVersionRepository.save(version);
    }

    private boolean isUnchanged(E2eScenarioVersion latest, Long biStepVersionId, String extUid, String name, String description) {
        if (latest == null) return false;
        return Objects.equals(latest.getBiStepVersionId(), biStepVersionId)
                && Objects.equals(latest.getExtUid(), extUid)
                && Objects.equals(latest.getName(), name)
                && Objects.equals(latest.getDescription(), description);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "e2e_scenario", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
