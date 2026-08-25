package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.domain.SourceSystem;
import ru.beeline.staging.repository.SourceSystemRepository;

import java.util.List;

@RestController
@RequestMapping("/api/v1/data-sources")
@RequiredArgsConstructor
public class DataSourceController {

    private final SourceSystemRepository sourceSystemRepository;

    @GetMapping
    public ResponseEntity<List<SourceSystem>> listDataSources() {
        return ResponseEntity.ok(sourceSystemRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
    }
}
