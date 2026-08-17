package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.dto.search.ArtifactSearchPage;
import ru.beeline.staging.repository.SourceArtefactSearchRepository;
import ru.beeline.staging.repository.SourceArtefactTypeRepository;

import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/v1/artifacts")
@RequiredArgsConstructor
public class ArtifactSearchController {

    private static final Set<String> ALLOWED_STATUSES = Set.of("active", "inactive", "deleted");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_NAME_LENGTH = 255;

    private final SourceArtefactSearchRepository sourceArtefactSearchRepository;
    private final SourceArtefactTypeRepository sourceArtefactTypeRepository;

    @GetMapping
    public ResponseEntity<?> searchArtifacts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer artifactTypeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Не передан обязательный query-параметр name"));
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorMessage", "Длина name превышает " + MAX_NAME_LENGTH + " символов"));
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
        if (artifactTypeId != null && !sourceArtefactTypeRepository.existsById(artifactTypeId.longValue())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Artifact type not found", "artifactTypeId", artifactTypeId));
        }

        ArtifactSearchPage page = sourceArtefactSearchRepository.search(
                name, artifactTypeId, normalizedStatus, limit != null ? limit : DEFAULT_LIMIT, offset);
        return ResponseEntity.ok(page);
    }
}
