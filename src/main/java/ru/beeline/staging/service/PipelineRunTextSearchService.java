package ru.beeline.staging.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import ru.beeline.staging.dto.search.PipelineRunSearchHit;
import ru.beeline.staging.dto.search.PipelineRunSearchHitContext;
import ru.beeline.staging.dto.search.PipelineRunSearchPage;
import ru.beeline.staging.dto.search.PipelineRunSearchResult;
import ru.beeline.staging.repository.PipelineRunTextSearchRepository;
import ru.beeline.staging.repository.PipelineRunTextSearchRepository.ContextRow;
import ru.beeline.staging.repository.PipelineRunTextSearchRepository.PipelineRunRow;
import ru.beeline.staging.repository.PipelineRunTextSearchRepository.RunsPage;
import ru.beeline.staging.utils.GzipUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implements the "Алгоритм поиска" section of
 * documentation/staging-service/api/rest/GET__api_v1_pipeline-runs_search__artifactType___artifactUid_.md
 * — keep in sync with that spec.
 */
@Service
@RequiredArgsConstructor
public class PipelineRunTextSearchService {

    private static final int SNIPPET_MARGIN = 20;

    private final PipelineRunTextSearchRepository repository;

    public boolean runsExist(String artifactType, String artifactUid) {
        return repository.existsRuns(artifactType, artifactUid);
    }

    /**
     * One query for the page of runs (raw_content joined in), one query for all occurrences' overlapping
     * contexts across the whole page — regardless of how many rows are on the page. Per-row decompression
     * and substring search still happen in the JVM (each row's raw_content differs), but no per-row SQL.
     */
    public PipelineRunSearchPage search(String artifactType, String artifactUid, String text,
                                         String status, LocalDateTime dateFrom, LocalDateTime dateTo,
                                         int limit, int offset) {
        RunsPage runsPage = repository.findRuns(artifactType, artifactUid, status, dateFrom, dateTo, limit, offset);
        List<PipelineRunRow> rows = runsPage.rows();

        List<byte[]> contentByRow = new ArrayList<>(rows.size());
        List<List<long[]>> occurrencesByRow = new ArrayList<>(rows.size());
        List<Long> flatRefIds = new ArrayList<>();
        List<Long> flatStarts = new ArrayList<>();
        List<Long> flatEnds = new ArrayList<>();
        int[] rowStartInFlat = new int[rows.size()];

        for (int i = 0; i < rows.size(); i++) {
            PipelineRunRow row = rows.get(i);
            byte[] content = decompress(row);
            contentByRow.add(content);
            List<long[]> occurrences = findOccurrences(content, text);
            occurrencesByRow.add(occurrences);
            rowStartInFlat[i] = flatStarts.size();
            for (long[] occurrence : occurrences) {
                flatRefIds.add(row.rawDataRefId());
                flatStarts.add(occurrence[0]);
                flatEnds.add(occurrence[1]);
            }
        }

        Map<Integer, List<ContextRow>> contextsByGlobalOrdinal = groupByOccurrence(
                repository.findOverlappingContexts(toArray(flatRefIds), toArray(flatStarts), toArray(flatEnds)));

        List<PipelineRunSearchResult> results = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            PipelineRunRow row = rows.get(i);
            byte[] content = contentByRow.get(i);
            List<long[]> occurrences = occurrencesByRow.get(i);
            int base = rowStartInFlat[i];

            List<PipelineRunSearchHit> hits = new ArrayList<>(occurrences.size());
            for (int j = 0; j < occurrences.size(); j++) {
                long[] occurrence = occurrences.get(j);
                int globalOrdinal = base + j + 1; // matches the 1-based SQL ordinality of the flat arrays above
                hits.add(buildHit(content, occurrence[0], occurrence[1],
                        contextsByGlobalOrdinal.getOrDefault(globalOrdinal, List.of())));
            }

            results.add(new PipelineRunSearchResult(
                    row.id(), row.artifactUid(), row.artifactName(), row.artifactType(), row.status(), row.startedAt(),
                    row.rawDataRefId(), occurrences.size(), hits));
        }

        return new PipelineRunSearchPage(runsPage.totalCount(), results);
    }

    private byte[] decompress(PipelineRunRow row) {
        try {
            byte[] rawContent = row.rawContent();
            return GzipUtils.isGzip(rawContent) ? GzipUtils.gunzip(rawContent) : rawContent;
        } catch (IOException e) {
            throw new RawContentDecompressionException(row.rawDataRefId(), e);
        }
    }

    private long[] toArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    /** occurrenceIndex from the repository is the 1-based ordinal into the flat arrays passed to it. */
    private Map<Integer, List<ContextRow>> groupByOccurrence(
            List<PipelineRunTextSearchRepository.OverlapHit> overlapHits) {
        Map<Integer, List<ContextRow>> byOccurrence = new HashMap<>();
        for (PipelineRunTextSearchRepository.OverlapHit hit : overlapHits) {
            byOccurrence.computeIfAbsent(hit.occurrenceIndex(), k -> new ArrayList<>()).add(hit.context());
        }
        return byOccurrence;
    }

    /** contexts here are already overlap-filtered in SQL (findOverlappingContexts) — no re-checking needed. */
    private PipelineRunSearchHit buildHit(byte[] content, long startOffset, long endOffset, List<ContextRow> contexts) {
        List<PipelineRunSearchHitContext> matchedContexts = new ArrayList<>();
        for (ContextRow ctx : contexts) {
            long[] byteRange = extractByteRange(ctx.position());
            if (byteRange == null) {
                continue;
            }
            matchedContexts.add(new PipelineRunSearchHitContext(
                    ctx.id(), ctx.position(), extractSnippet(content, byteRange[0], byteRange[1])));
        }

        String hitSnippet = matchedContexts.isEmpty()
                ? extractSnippet(content, startOffset - SNIPPET_MARGIN, endOffset + SNIPPET_MARGIN)
                : null;

        return new PipelineRunSearchHit(startOffset, endOffset, matchedContexts, hitSnippet);
    }

    /**
     * Case-insensitive, non-overlapping substring search. Offsets are computed in UTF-8 bytes of
     * the original (non-folded) content, since case folding can change a fragment's byte length.
     */
    private List<long[]> findOccurrences(byte[] content, String text) {
        String original = new String(content, StandardCharsets.UTF_8);
        String haystack = original.toLowerCase(Locale.ROOT);
        String needle = text.toLowerCase(Locale.ROOT);

        List<long[]> result = new ArrayList<>();
        int prevCharIdx = 0;
        long byteOffset = 0;
        int searchFrom = 0;
        while (true) {
            int idx = haystack.indexOf(needle, searchFrom);
            if (idx < 0) {
                break;
            }
            int idxEnd = idx + needle.length();
            byteOffset += utf8Length(original, prevCharIdx, idx);
            long start = byteOffset;
            byteOffset += utf8Length(original, idx, idxEnd);
            long end = byteOffset;
            result.add(new long[]{start, end});
            prevCharIdx = idxEnd;
            searchFrom = idxEnd;
        }
        return result;
    }

    private int utf8Length(String s, int from, int to) {
        return s.substring(from, to).getBytes(StandardCharsets.UTF_8).length;
    }

    private long[] extractByteRange(JsonNode position) {
        if (position == null) {
            return null;
        }
        JsonNode primary = position.get("primary");
        if (primary == null || primary.get("type") == null
                || !"byte_range".equals(primary.get("type").asText(null))) {
            return null;
        }
        JsonNode value = primary.get("value");
        if (value == null || !value.hasNonNull("start_offset") || !value.hasNonNull("end_offset")) {
            return null;
        }
        return new long[]{value.get("start_offset").asLong(), value.get("end_offset").asLong()};
    }

    private String extractSnippet(byte[] content, long start, long end) {
        int from = (int) Math.max(0, Math.min(start, content.length));
        int to = (int) Math.max(from, Math.min(end, content.length));
        return new String(content, from, to - from, StandardCharsets.UTF_8);
    }
}
