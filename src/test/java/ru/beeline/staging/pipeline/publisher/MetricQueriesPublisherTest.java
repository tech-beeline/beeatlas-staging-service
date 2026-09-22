package ru.beeline.staging.pipeline.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dashboard.DashboardServicePublishClient;
import ru.beeline.staging.domain.RawDataRef;
import ru.beeline.staging.pipeline.transformer.MetricQueriesObjectPublish;
import ru.beeline.staging.repository.RawDataRefRepository;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricQueriesPublisherTest {

    private final DashboardServicePublishClient publishClient = mock(DashboardServicePublishClient.class);
    private final RawDataRefRepository rawDataRefRepository = mock(RawDataRefRepository.class);
    private final MetricQueriesPublisher publisher =
            new MetricQueriesPublisher(publishClient, rawDataRefRepository, new ObjectMapper());

    @Test
    @DisplayName("Снимок берётся из raw_data_refs и уходит в dashboard-service")
    void publishesTheCanonicalSnapshot() throws Exception {
        when(rawDataRefRepository.findById(42L)).thenReturn(Optional.of(refWith("{}")));
        when(publishClient.publish(any(MetricQueriesObjectPublish.class), anyLong())).thenReturn(true);

        Map<String, Object> summary = publisher.publish("РБС", "metric-queries", 42L, 7L);

        assertThat(summary).containsEntry("published", true);
        verify(publishClient).publish(any(MetricQueriesObjectPublish.class), eq(42L));
    }

    @Test
    @DisplayName("Пустой снимок не отправляется")
    void skipsAnEmptySnapshot() throws Exception {
        when(rawDataRefRepository.findById(42L)).thenReturn(Optional.of(refWith("  ")));

        assertThat(publisher.publish("РБС", "metric-queries", 42L, 7L)).containsEntry("published", false);
        verify(publishClient, never()).publish(any(), anyLong());
    }

    @Test
    @DisplayName("Пропавшая ссылка на сырые данные — ошибка стадии, а не тихий пропуск")
    void failsWhenTheRawDataRefIsMissing() {
        when(rawDataRefRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publisher.publish("РБС", "metric-queries", 42L, 7L))
                .isInstanceOf(NoSuchElementException.class);
    }

    private RawDataRef refWith(String canonicalSnapshotJson) {
        RawDataRef ref = new RawDataRef();
        ref.setId(42L);
        ref.setCanonicalSnapshotJson(canonicalSnapshotJson);
        return ref;
    }
}
