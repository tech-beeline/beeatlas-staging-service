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
import ru.beeline.staging.dto.search.NoticeSearchPage;
import ru.beeline.staging.repository.NoticeSearchRepository;
import ru.beeline.staging.repository.PipelineRunRepository;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_TEXT_LENGTH = 255;

    private final NoticeSearchRepository noticeSearchRepository;
    private final PipelineRunRepository pipelineRunRepository;

    @GetMapping("/notices/{runId}")
    public ResponseEntity<?> searchNotices(
            @PathVariable Long runId,
            @RequestParam(required = false) String text,
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
        if (limit != null && limit < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Параметры limit и offset не могут быть отрицательными"));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Параметры limit и offset не могут быть отрицательными"));
        }

        Optional<PipelineRun> run = pipelineRunRepository.findById(runId);
        if (run.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Pipeline run not found", "runId", runId));
        }
        Long rawDataRefId = run.get().getRawDataRefId();
        if (rawDataRefId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Pipeline run has no raw data reference", "runId", runId));
        }

        NoticeSearchPage page = noticeSearchRepository.search(
                rawDataRefId, text, limit != null ? limit : DEFAULT_LIMIT, offset);
        return ResponseEntity.ok(page);
    }
}
