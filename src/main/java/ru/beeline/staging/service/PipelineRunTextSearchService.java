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
import java.util.List;
import java.util.Locale;

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

    public PipelineRunSearchPage search(String artifactType, String artifactUid, String text,
                                         String status, LocalDateTime dateFrom, LocalDateTime dateTo,
                                         int limit, int offset) {
        RunsPage runsPage = repository.findRuns(artifactType, artifactUid, status, dateFrom, dateTo, limit, offset);

        List<PipelineRunSearchResult> results = new ArrayList<>();
        for (PipelineRunRow row : runsPage.rows()) {
            results.add(searchRun(row, text));
        }
        return new PipelineRunSearchPage(runsPage.totalCount(), results);
    }

    private PipelineRunSearchResult searchRun(PipelineRunRow row, String text) {
        byte[] rawContent = repository.findRawContent(row.rawDataRefId());
        byte[] content;
        try {
            content = GzipUtils.isGzip(rawContent) ? GzipUtils.gunzip(rawContent) : rawContent;
        } catch (IOException e) {
            throw new RawContentDecompressionException(row.rawDataRefId(), e);
        }

        List<long[]> occurrences = findOccurrences(content, text);
        List<ContextRow> contexts = occurrences.isEmpty() ? List.of() : repository.findContexts(row.rawDataRefId());

        List<PipelineRunSearchHit> hits = new ArrayList<>();
        for (long[] occurrence : occurrences) {
            hits.add(buildHit(content, occurrence[0], occurrence[1], contexts));
        }

        return new PipelineRunSearchResult(
                row.id(), row.artifactUid(), row.artifactType(), row.status(), row.startedAt(),
                row.rawDataRefId(), occurrences.size(), hits);
    }

    private PipelineRunSearchHit buildHit(byte[] content, long startOffset, long endOffset, List<ContextRow> contexts) {
        List<PipelineRunSearchHitContext> matchedContexts = new ArrayList<>();
        for (ContextRow ctx : contexts) {
            long[] byteRange = extractByteRange(ctx.position());
            if (byteRange == null) {
                continue;
            }
            long ctxStart = byteRange[0];
            long ctxEnd = byteRange[1];
            if (ctxStart < endOffset && ctxEnd > startOffset) {
                matchedContexts.add(new PipelineRunSearchHitContext(
                        ctx.id(), ctx.position(), extractSnippet(content, ctxStart, ctxEnd)));
            }
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
