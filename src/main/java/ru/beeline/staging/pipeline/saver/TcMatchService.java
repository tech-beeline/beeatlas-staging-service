package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.Tc;
import ru.beeline.staging.domain.canonical.TcVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.TcRepository;
import ru.beeline.staging.repository.canonical.TcVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TcMatchService {

    private final TcRepository        tcRepository;
    private final TcVersionRepository tcVersionRepository;
    private final ArtifactNoticeService noticeService;

    @Transactional
    public TcVersion matchOrCreate(String tcCode, String name, String description, Integer productId,
                                    String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        Tc entity = tcRepository.findByTcCode(tcCode).orElseGet(() -> {
            created[0] = true;
            Tc e = new Tc();
            e.setTcCode(tcCode);
            e.setProductId(productId);
            e.setCreatedAt(LocalDateTime.now());
            return tcRepository.save(e);
        });
        if (entity.getProductId() == null && productId != null) {
            entity.setProductId(productId);
            tcRepository.save(entity);
        }

        String code = created[0] ? "match.tc.created" : "match.tc.matched_by_tc_code";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, tcCode, jsonPointer);

        TcVersion version = new TcVersion();
        version.setTcId(entity.getId());
        version.setName(name);
        version.setDescription(description);
        version.setRawDataRefId(rawDataRefId);
        version.setBatchId(batchId);
        version.setContext(jsonPointer);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        return tcVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "tc", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
