package ru.beeline.staging.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.repository.RawDataRefRepository;
import ru.beeline.staging.utils.GzipUtils;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/raw-data")
@RequiredArgsConstructor
public class RawDataController {

    private static final Map<String, MediaType> CONTENT_TYPES = Map.of(
            "json", MediaType.APPLICATION_JSON,
            "yaml", MediaType.valueOf("application/x-yaml"),
            "xml", MediaType.APPLICATION_XML,
            "text", MediaType.TEXT_PLAIN,
            "binary", MediaType.APPLICATION_OCTET_STREAM);

    private final RawDataRefRepository rawDataRefRepository;

    @GetMapping("/{id}")
    public ResponseEntity<?> getRawContent(@PathVariable Long id) {
        RawDataRef ref = rawDataRefRepository.findById(id).orElse(null);
        if (ref == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Raw data not found", "id", id));
        }

        byte[] content = ref.getRawContent();
        byte[] decompressed;
        try {
            // raw_content is only gzip-compressed when the adapter that wrote it chose to (currently
            // disabled pipeline-wide) — detect via the gzip magic bytes rather than trusting a fixed
            // convention, so this works whether or not compression is on.
            decompressed = GzipUtils.isGzip(content) ? GzipUtils.gunzip(content) : content;
        } catch (Exception e) {
            log.error("Failed to decompress raw_content: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to decompress raw content", "id", id));
        }

        MediaType contentType = CONTENT_TYPES.getOrDefault(ref.getFormat(), MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(contentType).body(decompressed);
    }
}
