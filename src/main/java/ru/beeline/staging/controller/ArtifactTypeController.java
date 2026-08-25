package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.dto.artifacttype.ArtifactTypeSummary;
import ru.beeline.staging.repository.ArtifactTypeSummaryRepository;

import java.util.List;

@RestController
@RequestMapping("/api/v1/artifact-types")
@RequiredArgsConstructor
public class ArtifactTypeController {

    private final ArtifactTypeSummaryRepository artifactTypeSummaryRepository;

    @GetMapping
    public ResponseEntity<List<ArtifactTypeSummary>> listArtifactTypes() {
        return ResponseEntity.ok(artifactTypeSummaryRepository.findAll());
    }
}
