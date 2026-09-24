package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.client.E2eProductsClient;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;
import ru.beeline.staging.service.ArtifactNoticeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class E2eProductsPublisherTest {

    private static final String UID = "auto_test_plantuml_src_92302";
    private static final long REF_ID = 777L;
    private static final long RUN_ID = 1646875L;
    private static final String SCENARIO_JSON = """
            {"e2e":{"uid":"E2E-001","name":"Scenario","bi_step_code":null},"operation_relations":[]}
            """;

    private ActualE2eScenarioRepository actualE2eScenarioRepository;
    private E2eV2PublishRequestMapper mapper;
    private E2eProductsClient e2eProductsClient;
    private CxBiStepRelationsPublisher cxBiStepRelationsPublisher;
    private ArtifactNoticeService artifactNoticeService;
    private E2eProductsPublisher publisher;

    @BeforeEach
    void setUp() {
        actualE2eScenarioRepository = mock(ActualE2eScenarioRepository.class);
        mapper = mock(E2eV2PublishRequestMapper.class);
        e2eProductsClient = mock(E2eProductsClient.class);
        cxBiStepRelationsPublisher = mock(CxBiStepRelationsPublisher.class);
        artifactNoticeService = mock(ArtifactNoticeService.class);
        when(mapper.map(any())).thenReturn(new E2eV2PublishRequest());
        publisher = new E2eProductsPublisher(actualE2eScenarioRepository, mapper, e2eProductsClient,
                cxBiStepRelationsPublisher, artifactNoticeService, new ObjectMapper());
    }

    @Test
    @DisplayName("Актуальное состояние ищется по типу артефакта, plantuml публикуется с source=PLANTUML")
    void publishesPlantUmlScenarioWithItsOwnSource() {
        when(actualE2eScenarioRepository.fetchActualScenarioRaw(UID, "e2e-plantuml")).thenReturn(SCENARIO_JSON);

        publisher.publish(UID, "e2e-plantuml", REF_ID, RUN_ID);

        verify(e2eProductsClient).upsertE2e(any(), eq(REF_ID), eq(RUN_ID), eq("PLANTUML"));
        verify(artifactNoticeService, never()).saveNoticeInNewTransaction(anyLong(), any());
    }

    @Test
    @DisplayName("Поток e2e-sequence остаётся на source=SPARX")
    void keepsSparxSourceForSequenceFlow() {
        when(actualE2eScenarioRepository.fetchActualScenarioRaw(UID, "e2e-sequence")).thenReturn(SCENARIO_JSON);

        publisher.publish(UID, "e2e-sequence", REF_ID, RUN_ID);

        verify(e2eProductsClient).upsertE2e(any(), eq(REF_ID), eq(RUN_ID), eq("SPARX"));
    }

    @Test
    @DisplayName("Отсутствие актуального состояния фиксируется замечанием publish.skipped, а не тихим выходом")
    void recordsNoticeWhenNothingToPublish() {
        when(actualE2eScenarioRepository.fetchActualScenarioRaw(anyString(), anyString())).thenReturn(null);

        publisher.publish(UID, "e2e-plantuml", REF_ID, RUN_ID);

        verify(e2eProductsClient, never()).upsertE2e(any(), anyLong(), anyLong(), anyString());
        ArgumentCaptor<ArtifactNotice> notice = ArgumentCaptor.forClass(ArtifactNotice.class);
        verify(artifactNoticeService).saveNoticeInNewTransaction(eq(REF_ID), notice.capture());
        assertThat(notice.getValue().code()).isEqualTo("publish.skipped");
        assertThat(notice.getValue().level()).isEqualTo("warning");
        assertThat(notice.getValue().details()).contains("e2e-plantuml");
    }
}
