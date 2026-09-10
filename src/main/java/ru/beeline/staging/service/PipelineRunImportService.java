/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.client.DocumentServiceClient;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.domain.SourceSystem;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunRequest;
import ru.beeline.staging.dto.pipelinerun.CreatePipelineRunResponse;
import ru.beeline.staging.exception.ActivePipelineRunException;
import ru.beeline.staging.exception.DocumentNotFoundException;
import ru.beeline.staging.exception.PipelineRunBadRequestException;
import ru.beeline.staging.exception.PipelineRunNotFoundException;
import ru.beeline.staging.repository.ConfigurationRepository;
import ru.beeline.staging.repository.DataTypeRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.SourceSystemRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRunImportService {

    public static final String USECASE_TYPE = "usecase";
    public static final String E2E_PLANTUML_TYPE = "e2e-plantuml";

    private static final String DEFAULT_BRANCH = "main";
    private static final int MAX_BRANCH_LENGTH = 64;
    private static final int MAX_PLANT_UML_BYTES = 512 * 1024;
    private static final String MANUAL_SOURCE = "manual";
    private static final String INITIAL_STATUS = "pending";
    private static final String CANCELLED_STATUS = "cancelled";
    private static final List<String> TERMINAL_STATUSES = List.of("completed", "failed", CANCELLED_STATUS);

    private static final Map<String, String> SOURCE_SYSTEM_BY_SOURCE = Map.of(
            MANUAL_SOURCE, "beeatlas-ui",
            "solution-checker", "solution-checker",
            "confluence", "confluence");

    private final PipelineRunRepository pipelineRunRepository;
    private final DataTypeRepository dataTypeRepository;
    private final SourceSystemRepository sourceSystemRepository;
    private final ConfigurationRepository configurationRepository;
    private final DocumentServiceClient documentServiceClient;
    private final PipelineExecutionService pipelineExecutionService;

    @Transactional
    public CreatePipelineRunResponse createImportRun(CreatePipelineRunRequest request) {
        String artifactType = required(request.getArtifactType(), "artifactType");
        String artifactUid = required(request.getArtifactUid(), "artifactUid");
        String source = required(request.getSource(), "source");
        JsonNode payload = request.getPayload();
        if (payload == null || !payload.isObject()) {
            throw new PipelineRunBadRequestException("Поле payload обязательно и должно быть объектом");
        }
        String branch = resolveBranch(request.getBranch());

        dataTypeRepository.findByCode(artifactType)
                .orElseThrow(() -> new PipelineRunNotFoundException(
                        "Тип артефакта не зарегистрирован в data_types: " + artifactType));
        SourceSystem sourceSystem = resolveSourceSystem(artifactType, source);

        validatePayload(artifactType, payload);

        Long supersededRunId = cancelSupersededRun(request.getSupersedesRunId());
        if (supersededRunId == null) {
            rejectIfActiveRunExists(artifactUid, artifactType);
        }

        PipelineRun run = new PipelineRun();
        run.setArtifactUid(artifactUid);
        run.setArtifactType(artifactType);
        run.setStatus(INITIAL_STATUS);
        run.setPayload(payload.toString());
        run.setSourceId(sourceSystem.getId());
        run.setBranch(branch);
        run.setSupersedesRunId(supersededRunId);
        run.setConfigurationId(manualConfigurationId(artifactType));
        run = pipelineRunRepository.save(run);

        log.info("Created manual import run {}: artifactType={} artifactUid={} source={} branch={} supersedes={}",
                run.getId(), artifactType, artifactUid, source, branch, supersededRunId);

        pipelineExecutionService.submitArtifactChain(run.getId(), artifactType, artifactType);

        return new CreatePipelineRunResponse(run.getId(), artifactType, artifactUid, run.getStatus(),
                statusUrl(run.getId(), artifactType));
    }

    private Long manualConfigurationId(String artifactType) {
        return configurationRepository.findByArtifactTypeAndIsActiveTrue(artifactType).stream()
                .filter(configuration -> configuration.getScheduleIntervalSeconds() == null)
                .map(Configuration::getId)
                .findFirst()
                .orElse(null);
    }

    private SourceSystem resolveSourceSystem(String artifactType, String source) {
        if (E2E_PLANTUML_TYPE.equals(artifactType) && !MANUAL_SOURCE.equals(source)) {
            throw new PipelineRunBadRequestException(
                    "Для типа " + E2E_PLANTUML_TYPE + " допустим только source=" + MANUAL_SOURCE);
        }
        String code = SOURCE_SYSTEM_BY_SOURCE.get(source);
        if (code == null) {
            throw new PipelineRunBadRequestException("Неизвестный source: " + source);
        }
        return sourceSystemRepository.findByCode(code)
                .orElseThrow(() -> new PipelineRunNotFoundException(
                        "Источник не зарегистрирован в source_systems: " + code));
    }

    private void validatePayload(String artifactType, JsonNode payload) {
        switch (artifactType) {
            case USECASE_TYPE -> validateUseCasePayload(payload);
            case E2E_PLANTUML_TYPE -> validateE2ePlantUmlPayload(payload);
            default -> { }
        }
    }

    private void validateUseCasePayload(JsonNode payload) {
        requirePayloadField(payload, "projectCode");
        requirePayloadField(payload, "name");
        String plantUml = requirePayloadField(payload, "plantUml");
        validatePlantUmlSize(plantUml);
    }

    private void validateE2ePlantUmlPayload(JsonNode payload) {
        requirePayloadField(payload, "name");

        String plantUml = text(payload, "plantUml");
        boolean hasPlantUml = plantUml != null && !plantUml.isBlank();
        boolean hasDocId = payload.hasNonNull("docId");
        if (hasPlantUml == hasDocId) {
            throw new PipelineRunBadRequestException(
                    "Требуется ровно одно из полей payload.plantUml и payload.docId");
        }

        if (hasPlantUml) {
            validatePlantUmlSize(plantUml);
            return;
        }
        JsonNode docId = payload.get("docId");
        if (!docId.canConvertToLong()) {
            throw new PipelineRunBadRequestException("Поле payload.docId должно быть числом");
        }
        requireDocumentExists(docId.asLong());
    }

    private void requireDocumentExists(long docId) {
        try {
            documentServiceClient.fetchContent(docId);
        } catch (DocumentNotFoundException e) {
            throw new PipelineRunNotFoundException("Документ не найден в document-service: docId=" + docId);
        }
    }

    private void validatePlantUmlSize(String plantUml) {
        if (plantUml.getBytes(StandardCharsets.UTF_8).length > MAX_PLANT_UML_BYTES) {
            throw new PipelineRunBadRequestException(
                    "Размер payload.plantUml превышает " + MAX_PLANT_UML_BYTES + " байт");
        }
    }

    private Long cancelSupersededRun(Long supersedesRunId) {
        if (supersedesRunId == null) {
            return null;
        }
        PipelineRun superseded = pipelineRunRepository.findById(supersedesRunId)
                .orElseThrow(() -> new PipelineRunNotFoundException(
                        "Запуск не найден: supersedesRunId=" + supersedesRunId));
        if (!TERMINAL_STATUSES.contains(superseded.getStatus())) {
            pipelineRunRepository.markCompleted(superseded.getId(), CANCELLED_STATUS);
            log.info("Cancelled run {} superseded by a new import request", superseded.getId());
        }
        return superseded.getId();
    }

    private void rejectIfActiveRunExists(String artifactUid, String artifactType) {
        Optional<PipelineRun> active = pipelineRunRepository
                .findFirstByArtifactUidAndArtifactTypeAndStatusNotInOrderByStartedAtDesc(
                        artifactUid, artifactType, TERMINAL_STATUSES);
        if (active.isPresent()) {
            throw new ActivePipelineRunException(
                    "Для артефакта уже выполняется запуск: artifactUid=" + artifactUid, active.get().getId());
        }
    }

    private String resolveBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return DEFAULT_BRANCH;
        }
        String trimmed = branch.trim();
        if (trimmed.length() > MAX_BRANCH_LENGTH) {
            throw new PipelineRunBadRequestException("Поле branch длиннее " + MAX_BRANCH_LENGTH + " символов");
        }
        return trimmed;
    }

    private String statusUrl(Long runId, String artifactType) {
        String url = "/api/v1/pipeline-runs/" + runId + "/status";
        return USECASE_TYPE.equals(artifactType) ? url + "?waitFor=awaiting_review&timeoutMs=30000" : url;
    }

    private String requirePayloadField(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            throw new PipelineRunBadRequestException("Поле payload." + field + " обязательно");
        }
        return value;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PipelineRunBadRequestException("Поле " + field + " обязательно");
        }
        return value.trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
