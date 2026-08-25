package ru.beeline.staging.dto.search;

import java.util.List;

public record NoticeSearchPage(
        long totalCount,
        List<NoticeSearchResult> results
) {}
