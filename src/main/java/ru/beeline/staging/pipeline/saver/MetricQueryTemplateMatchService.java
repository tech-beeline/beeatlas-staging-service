package ru.beeline.staging.pipeline.saver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.MetricQueryTemplate;
import ru.beeline.staging.domain.canonical.MetricQueryTemplateVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.pipeline.transformer.MetricTemplate;
import ru.beeline.staging.repository.canonical.MetricQueryTemplateRepository;
import ru.beeline.staging.repository.canonical.MetricQueryTemplateVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Find-or-create + versioning for the metric_query_templates identity (save-spec §5/§9). Version's
 * is_current is documented directly on metric_query_template_versions (unlike e2e, where "current"
 * is tracked only at the artifact_batches level) — see model/tables/metric_query_template_versions.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricQueryTemplateMatchService {

    private final MetricQueryTemplateRepository        templateRepository;
    private final MetricQueryTemplateVersionRepository versionRepository;
    private final ArtifactNoticeService                noticeService;
    private final ObjectMapper                          objectMapper;

    @Transactional
    public MetricQueryTemplateVersion matchOrCreate(String uid, String entityType, String schemaVersion,
                                                      List<MetricTemplate> metricTemplates,
                                                      Long rawDataRefId, Long batchId) throws Exception {
        boolean[] created = {false};
        MetricQueryTemplate entity = templateRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            MetricQueryTemplate e = new MetricQueryTemplate();
            e.setUid(uid);
            e.setEntityType(entityType);
            e.setCreatedAt(LocalDateTime.now());
            return templateRepository.save(e);
        });

        String code = created[0] ? "match.metric_query_template.created" : "match.metric_query_template.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid);

        versionRepository.clearCurrentFlag(entity.getId());

        MetricQueryTemplateVersion version = new MetricQueryTemplateVersion();
        version.setMetricQueryTemplateId(entity.getId());
        version.setSchemaVersion(schemaVersion);
        version.setJsonData(objectMapper.writeValueAsString(metricTemplates));
        version.setBatchId(batchId);
        version.setCurrent(true);
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        version.setCreatedAt(LocalDateTime.now());
        return versionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "metric_query_template", entityUid, null, code, null, null, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
