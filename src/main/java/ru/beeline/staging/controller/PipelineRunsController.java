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
import ru.beeline.staging.dto.scan.ScanRun;
import ru.beeline.staging.repository.PipelineRunDetailsRepository;
import ru.beeline.staging.repository.ScanRunRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/v1/pipeline-runs")
@RequiredArgsConstructor
public class PipelineRunsController {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "pending", "loading", "validating", "transforming", "saving", "publishing", "completed", "failed");

    private static final int DEFAULT_LIMIT = 50;

    private final ScanRunRepository scanRunRepository;
    private final PipelineRunDetailsRepository pipelineRunDetailsRepository;

    @GetMapping("/{runId}/details")
    public ResponseEntity<?> getRunDetails(@PathVariable Long runId) {
        return pipelineRunDetailsRepository.findById(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Pipeline run not found", "runId", runId)));
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

        if (status != null && !ALLOWED_STATUSES.contains(status)) {
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
            from = parseDate(dateFrom);
            to = parseDate(dateTo);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format: " + e.getParsedString()));
        }

        List<ScanRun> scans = scanRunRepository.findScans(
                artifactType, sourceName, status, from, to,
                limit != null ? limit : DEFAULT_LIMIT, offset);

        return ResponseEntity.ok(scans);
    }

    private LocalDateTime parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(value);
        }
    }
}
