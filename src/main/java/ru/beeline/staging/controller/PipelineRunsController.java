package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.domain.PipelineRun;
import ru.beeline.staging.dto.rundetails.ChildPipelineRunPage;
import ru.beeline.staging.dto.scan.ScanRunDetails;
import ru.beeline.staging.dto.scan.ScanRunPage;
import ru.beeline.staging.dto.search.PipelineRunSearchPage;
import ru.beeline.staging.repository.ChildPipelineRunRepository;
import ru.beeline.staging.repository.PipelineRunDetailsRepository;
import ru.beeline.staging.repository.PipelineRunRepository;
import ru.beeline.staging.repository.ScanRunRepository;
import ru.beeline.staging.service.PipelineRunTextSearchService;
import ru.beeline.staging.service.RawContentDecompressionException;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/v1/pipeline-runs")
@RequiredArgsConstructor
public class PipelineRunsController {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "pending", "loading", "validating", "transforming", "saving", "publishing", "completed", "failed");

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_ARTIFACT_TYPE_LENGTH = 100;
    private static final int MAX_ARTIFACT_UID_LENGTH = 255;
    private static final int MAX_TEXT_LENGTH = 255;

    private final ScanRunRepository scanRunRepository;
    private final PipelineRunDetailsRepository pipelineRunDetailsRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final ChildPipelineRunRepository childPipelineRunRepository;
    private final PipelineRunTextSearchService pipelineRunTextSearchService;

    @GetMapping("/{runId}/details")
    public ResponseEntity<?> getRunDetails(@PathVariable Long runId) {
        return pipelineRunDetailsRepository.findById(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Pipeline run not found", "runId", runId)));
    }

    @GetMapping("/{scanId}/scan-details")
    public ResponseEntity<?> getScanDetails(@PathVariable Long scanId) {
        Optional<ScanRunDetails> details = scanRunRepository.findScanDetails(scanId);
        return details.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Pipeline run not found", "scanId", scanId)));
    }

    @GetMapping("/{parentId}/child")
    public ResponseEntity<?> listChildRuns(
            @PathVariable Long parentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String artifactUid,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {

        Optional<PipelineRun> parent = pipelineRunRepository.findById(parentId);
        if (parent.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Pipeline run not found", "parentId", parentId));
        }
        if (parent.get().getParentRunId() != null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "parentId is not a scan run", "parentId", parentId));
        }
        String normalizedStatus = status != null ? status.toLowerCase() : null;
        if (normalizedStatus != null && !ALLOWED_STATUSES.contains(normalizedStatus)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status: " + status));
        }
        if (artifactUid != null && artifactUid.length() > 255) {
            return ResponseEntity.badRequest().body(Map.of("error", "artifactUid must not exceed 255 characters"));
        }
        if (limit != null && limit < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit must not be negative"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "offset must not be negative"));
        }

        ChildPipelineRunPage children = childPipelineRunRepository.findChildRuns(
                parentId, normalizedStatus, artifactUid, limit != null ? limit : DEFAULT_LIMIT, offset);
        return ResponseEntity.ok(children);
    }

    @GetMapping("/scans")
    public ResponseEntity<?> listScans(
            @RequestParam(required = false) String artifactType,
            @RequestParam(required = false) String sourceName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {

        String normalizedStatus = status != null ? status.toLowerCase() : null;
        if (normalizedStatus != null && !ALLOWED_STATUSES.contains(normalizedStatus)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status: " + status));
        }
        if (limit != null && limit < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit must not be negative"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "offset must not be negative"));
        }

        LocalDateTime from;
        LocalDateTime to;
        try {
            from = parseDate(dateFrom, false);
            to = parseDate(dateTo, true);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format: " + e.getParsedString()));
        }

        ScanRunPage scans = scanRunRepository.findScans(
                artifactType, sourceName, normalizedStatus, from, to,
                limit != null ? limit : DEFAULT_LIMIT, offset);

        return ResponseEntity.ok(scans);
    }

    @GetMapping("/search/{artifactType}/{artifactUid}")
    public ResponseEntity<?> searchPipelineRuns(
            @PathVariable String artifactType,
            @PathVariable String artifactUid,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Не передан обязательный query-параметр text"));
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Длина text превышает " + MAX_TEXT_LENGTH + " символов"));
        }
        if (artifactType.length() > MAX_ARTIFACT_TYPE_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Длина artifactType превышает " + MAX_ARTIFACT_TYPE_LENGTH + " символов"));
        }
        if (artifactUid.length() > MAX_ARTIFACT_UID_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Длина artifactUid превышает " + MAX_ARTIFACT_UID_LENGTH + " символов"));
        }
        String normalizedStatus = status != null ? status.toLowerCase() : null;
        if (normalizedStatus != null && !ALLOWED_STATUSES.contains(normalizedStatus)) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage",
                    "Недопустимое значение параметра status. Допустимые значения: " + String.join(", ", ALLOWED_STATUSES)));
        }
        if (limit != null && limit < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Параметры limit и offset не могут быть отрицательными"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Параметры limit и offset не могут быть отрицательными"));
        }

        LocalDateTime from;
        LocalDateTime to;
        try {
            from = parseDate(dateFrom, false);
            to = parseDate(dateTo, true);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format: " + e.getParsedString()));
        }
        if (from != null && to != null && from.isAfter(to)) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "dateFrom не может быть позже dateTo"));
        }

        if (!pipelineRunTextSearchService.runsExist(artifactType, artifactUid)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Pipeline runs not found",
                    "artifactType", artifactType,
                    "artifactUid", artifactUid));
        }

        try {
            PipelineRunSearchPage page = pipelineRunTextSearchService.search(
                    artifactType, artifactUid, text, normalizedStatus, from, to,
                    limit != null ? limit : DEFAULT_LIMIT, offset);
            return ResponseEntity.ok(page);
        } catch (RawContentDecompressionException e) {
            log.error("Failed to decompress raw content: rawDataRefId={}", e.getRawDataRefId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to decompress raw content", "rawDataRefId", e.getRawDataRefId()));
        }
    }

    private LocalDateTime parseDate(String value, boolean endOfDay) {
        if (value == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException e) {
            // fall through to try LocalDateTime / date-only formats
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            // fall through to try date-only format
        }
        java.time.LocalDate date = java.time.LocalDate.parse(value);
        return endOfDay ? date.atTime(23, 59, 59, 999_999_999) : date.atStartOfDay();
    }
}
