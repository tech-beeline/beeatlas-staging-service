package ru.beeline.staging.pipeline.cxbackend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.beeline.staging.cxbackend.CxBackendClient;
import ru.beeline.staging.cxbackend.dto.BiElementDto;
import ru.beeline.staging.cxbackend.dto.CjResponseDto;
import ru.beeline.staging.cxbackend.dto.CjTagsDto;
import ru.beeline.staging.cxbackend.dto.CollapsedSubProcessDto;
import ru.beeline.staging.cxbackend.dto.CxBiStepDto;
import ru.beeline.staging.cxbackend.dto.ProcessCjDto;
import ru.beeline.staging.domain.cxbackend.CxBackendCjLink;
import ru.beeline.staging.pipeline.CanonicalModelPublisher;
import ru.beeline.staging.pipeline.CanonicalModelSaverService;
import ru.beeline.staging.pipeline.CanonicalSnapshot;
import ru.beeline.staging.repository.cxbackend.CxBackendCjLinkRepository;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Real {@link CanonicalModelPublisher} for e2e-sequence artifacts: pushes the canonical
 * snapshot into cx-backend's CJ/BI library so it appears as "наше представление beatlas".
 *
 * Rather than reimplementing BI/BiStep creation against cx-backend's CRUD endpoints (which
 * don't support creating BiStep rows at all outside cx-backend's own BPMN-import flow), this
 * reuses that existing, already-tested import pipeline. The pipeline's only job with a BPMN
 * file is to parse it into a {@code ProcessCJ} model — since we already have the data in our
 * own canonical shape, we skip generating/parsing BPMN XML entirely and build that
 * {@code ProcessCJ}-equivalent model ({@link ProcessCjDto}) directly, posting it as JSON to the
 * {@code /product/cj/{id}/import-from-model} endpoint added to cx-backend for this purpose.
 *
 * Only the CJ itself needs idempotency tracking on our side (staging.cx_backend_cj_links —
 * cx-backend has no field/filter we could use to find "our" CJ for a given artifactUid).
 * BI/BiStep dedup on re-publish is cx-backend's own responsibility: once a CJ has been
 * bpmn-imported once, re-importing matches existing BIs/steps by their BPMN element id
 * (which we derive deterministically from artifactUid / step uid), so re-publishing the same
 * artifact updates rather than duplicates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "staging.cx-backend.enabled", havingValue = "true", matchIfMissing = true)
public class CxBackendCanonicalModelPublisher implements CanonicalModelPublisher {

    private static final String SUPPORTED_TYPE = "e2e-sequence";

    private final CxBackendClient           cxBackendClient;
    private final CxBackendCjLinkRepository cjLinkRepository;

    @Value("${staging.cx-backend.system-user-id}")
    private Long systemUserId;

    @Value("${staging.cx-backend.default-product-id}")
    private Long defaultProductId;

    @Override
    public void publish(String artifactType, String artifactUid, CanonicalSnapshot snapshot,
                        CanonicalModelSaverService.SaveResult result) {
        if (!SUPPORTED_TYPE.equals(artifactType)) {
            log.warn("CxBackendCanonicalModelPublisher: no publish logic for artifactType='{}', uid={} — skipping",
                    artifactType, artifactUid);
            return;
        }

        Long cjId = resolveCj(artifactUid);
        ProcessCjDto model = buildModel(artifactUid, snapshot);

        log.info("Importing model ({} bi_step(s)) into cx-backend CJ id={} for artifactUid={}",
                snapshot.getBiSteps().size(), cjId, artifactUid);
        cxBackendClient.importFromModel(cjId, model, systemUserId);
        log.info("cx-backend import-from-model completed for artifactUid={}, cjId={}", artifactUid, cjId);
    }

    /**
     * Builds the {@code ProcessCJ}-equivalent model directly from the canonical snapshot:
     * one stage containing one BI element (named so cx-backend's element matcher recognizes it
     * as a BI, i.e. name starts with "BI") with one cx-backend BiStep per canonical bi_step.
     * Ids are derived deterministically from artifactUid/step uid so re-imports update rather
     * than duplicate (see {@code CJimportFromBpmnService#saveOrUpdateElements}).
     */
    private ProcessCjDto buildModel(String artifactUid, CanonicalSnapshot snapshot) {
        BiElementDto biElement = new BiElementDto();
        biElement.setType("subProcess");
        biElement.setId("BI_" + artifactUid);
        biElement.setName("BI E2E " + artifactUid);
        biElement.setBiSteps(snapshot.getBiSteps().stream()
                .map(step -> {
                    CxBiStepDto stepDto = new CxBiStepDto();
                    stepDto.setType("serviceTask");
                    stepDto.setId("BISTEP_" + step.getUid());
                    // cx-backend's BiStep.name is NOT NULL; dashboard's message.name is sometimes absent.
                    String name = step.getName();
                    stepDto.setName(name != null && !name.isBlank() ? name : step.getUid());
                    return stepDto;
                })
                .collect(Collectors.toList()));

        CollapsedSubProcessDto stage = new CollapsedSubProcessDto();
        stage.setId("STAGE_" + artifactUid);
        stage.setName("E2E " + artifactUid);
        stage.setBiElements(java.util.List.of(biElement));

        ProcessCjDto model = new ProcessCjDto();
        model.setId(artifactUid);
        model.setCollapsedSubProcesses(java.util.List.of(stage));
        return model;
    }

    private Long resolveCj(String artifactUid) {
        return cjLinkRepository.findById(artifactUid)
                .map(CxBackendCjLink::getCjId)
                .orElseGet(() -> createCj(artifactUid));
    }

    private Long createCj(String artifactUid) {
        CjTagsDto dto = new CjTagsDto();
        dto.setName("E2E " + artifactUid);
        dto.setBDraft(true);

        CjResponseDto created = cxBackendClient.createCj(defaultProductId, systemUserId, dto);
        log.info("Created cx-backend CJ id={} for artifactUid={}", created.getId(), artifactUid);

        CxBackendCjLink link = new CxBackendCjLink();
        link.setArtifactUid(artifactUid);
        link.setCjId(created.getId());
        link.setCreatedAt(LocalDateTime.now());
        cjLinkRepository.save(link);

        return created.getId();
    }
}
