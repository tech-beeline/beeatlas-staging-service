package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.beeline.staging.client.CxBackendClient;
import ru.beeline.staging.client.E2eProductsClient;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.product.dto.ProductAliasSummary;
import ru.beeline.staging.product.dto.cx.CxBiStepRelation;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CxBiStepRelationsPublisherTest {

    private static final String UID = "e2e_plantuml_92302";
    private static final long REF_ID = 777L;
    private static final long RUN_ID = 1646875L;
    private static final int BI_STEP_ID = 15;
    private static final int PRODUCT_ID = 42;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CxBackendClient cxBackendClient;
    private ProductServiceClient productServiceClient;
    private ArtifactNoticeService artifactNoticeService;
    private CxBiStepRelationsPublisher publisher;

    @BeforeEach
    void setUp() {
        cxBackendClient = mock(CxBackendClient.class);
        productServiceClient = mock(ProductServiceClient.class);
        artifactNoticeService = mock(ArtifactNoticeService.class);
        when(cxBackendClient.isConfigured()).thenReturn(true);
        when(cxBackendClient.findBiStepIdByCode("BI-STEP-1")).thenReturn(Optional.of(BI_STEP_ID));
        ProductAliasSummary product = new ProductAliasSummary();
        product.setId(PRODUCT_ID);
        product.setAlias("CRM");
        when(productServiceClient.getByAliases(anyList())).thenReturn(List.of(product));
        publisher = new CxBiStepRelationsPublisher(cxBackendClient, productServiceClient, artifactNoticeService,
                objectMapper);
    }

    @Test
    @DisplayName("Сопоставленный корневой вызов уходит в CX с id метода архитектуры")
    void mappedRootCallSendsArchitectureOperationId() {
        publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-a","call_order":1,"stereotype":"sync"}]
                """), UID, REF_ID, RUN_ID);

        assertThat(sentRelations()).containsExactly(new CxBiStepRelation(null, "sync", PRODUCT_ID, null, 501, null));
    }

    @Test
    @DisplayName("Интерфейс сопоставленной арх-операции уходит в CX как interfaceId")
    void mappedRootCallSendsArchitectureInterfaceId() {
        publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501,
                  "connection_interface_id":77}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-a","call_order":1,"stereotype":"sync"}]
                """), UID, REF_ID, RUN_ID);

        assertThat(sentRelations()).containsExactly(new CxBiStepRelation(null, "sync", PRODUCT_ID, null, 501, 77));
    }

    @Test
    @DisplayName("Продукт берётся у интерфейса своей операции, даже если код интерфейса совпадает у разных систем")
    void resolvesProductByOperationInterfaceVersion() {
        ProductAliasSummary umcs = new ProductAliasSummary();
        umcs.setId(43);
        umcs.setAlias("UMCS");
        ProductAliasSummary crm = new ProductAliasSummary();
        crm.setId(PRODUCT_ID);
        crm.setAlias("CRM");
        when(productServiceClient.getByAliases(anyList())).thenReturn(List.of(crm, umcs));

        publisher.publish(json("""
                {"e2e":{"uid":"E2E-001","bi_step_code":"BI-STEP-1"},
                 "interfaces":[{"interface_version_id":11,"code":"033d51b4","parent_product_cmdb":"CRM"},
                               {"interface_version_id":12,"code":"033d51b4","parent_product_cmdb":"UMCS"}],
                 "operations":[{"uid":"op-a","interface_version_id":11,"interface_code":"033d51b4",
                                "connection_operation_id":501},
                               {"uid":"op-b","interface_version_id":12,"interface_code":"033d51b4",
                                "connection_operation_id":502}],
                 "operation_relations":[{"operation_uid":null,"related_operation_uid":"op-a","call_order":1},
                                        {"operation_uid":null,"related_operation_uid":"op-b","call_order":2}]}
                """), UID, REF_ID, RUN_ID);

        assertThat(sentRelations()).extracting(CxBiStepRelation::getOperationId, CxBiStepRelation::getProductId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(501, PRODUCT_ID),
                        org.assertj.core.groups.Tuple.tuple(502, 43));
    }

    @Test
    @DisplayName("Несопоставленный корневой вызов в тело PUT не входит")
    void unmappedRootCallIsExcluded() {
        publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501},
                 {"uid":"op-b","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":null},
                 {"uid":"op-c","interface_version_id":1,"interface_code":"crm-api"}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-b","call_order":1},
                 {"operation_uid":null,"related_operation_uid":"op-a","call_order":2,"stereotype":"async"},
                 {"operation_uid":null,"related_operation_uid":"op-c","call_order":3},
                 {"operation_uid":null,"related_operation_uid":"op-unknown","call_order":4}]
                """), UID, REF_ID, RUN_ID);

        assertThat(sentRelations()).containsExactly(new CxBiStepRelation(null, "async", PRODUCT_ID, null, 501, null));
    }

    @Test
    @DisplayName("Вложенный вызов в тело PUT не входит, даже если вызываемый метод сопоставлен")
    void nestedCallIsExcluded() {
        publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501},
                 {"uid":"op-b","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":502}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-a","call_order":1},
                 {"operation_uid":"op-a","related_operation_uid":"op-b","call_order":2}]
                """), UID, REF_ID, RUN_ID);

        assertThat(sentRelations()).extracting(CxBiStepRelation::getOperationId).containsExactly(501);
    }

    @Test
    @DisplayName("Все корневые вызовы без сопоставления — PUT в CX не вызывается")
    void allRootCallsUnmappedSkipsPut() {
        publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":null},
                 {"uid":"op-b","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":502}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-a","call_order":1},
                 {"operation_uid":"op-a","related_operation_uid":"op-b","call_order":2}]
                """), UID, REF_ID, RUN_ID);

        verify(cxBackendClient, never()).replaceBiStepRelations(anyInt(), anyList());
        verify(artifactNoticeService, never()).saveNoticeInNewTransaction(anyLong(), any());
    }

    @Test
    @DisplayName("Нет корневых вызовов — синк в CX не вызывается")
    void noRootCallsSkipsSync() {
        publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501}]
                """, "[]"), UID, REF_ID, RUN_ID);

        verify(cxBackendClient, never()).findBiStepIdByCode(anyString());
        verify(cxBackendClient, never()).replaceBiStepRelations(anyInt(), anyList());
    }

    @Test
    @DisplayName("Нет bi_step_code — PUT в CX не вызывается")
    void noBiStepCodeSkipsSync() {
        publisher.publish(scenario(null, """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-a","call_order":1}]
                """), UID, REF_ID, RUN_ID);

        verify(cxBackendClient, never()).replaceBiStepRelations(anyInt(), anyList());
    }

    @Test
    @DisplayName("Ошибка CX пишет notice publish.cx.failed и не пробрасывается наверх")
    void cxFailureRecordsNoticeWithoutRethrow() {
        doThrow(new IllegalStateException("cx is down"))
                .when(cxBackendClient).replaceBiStepRelations(anyInt(), anyList());

        assertThatCode(() -> publisher.publish(scenario("BI-STEP-1", """
                [{"uid":"op-a","interface_version_id":1,"interface_code":"crm-api","connection_operation_id":501}]
                """, """
                [{"operation_uid":null,"related_operation_uid":"op-a","call_order":1}]
                """), UID, REF_ID, RUN_ID)).doesNotThrowAnyException();

        ArgumentCaptor<ArtifactNotice> notice = ArgumentCaptor.forClass(ArtifactNotice.class);
        verify(artifactNoticeService).saveNoticeInNewTransaction(eq(REF_ID), notice.capture());
        assertThat(notice.getValue().code()).isEqualTo("publish.cx.failed");
    }

    @Test
    @DisplayName("Публикация в CX не читает e2e из fdm-products")
    void doesNotDependOnE2eProductsClient() {
        assertThat(Arrays.stream(CxBiStepRelationsPublisher.class.getDeclaredFields()))
                .noneMatch(field -> field.getType() == E2eProductsClient.class);
    }

    private List<CxBiStepRelation> sentRelations() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CxBiStepRelation>> captor = ArgumentCaptor.forClass(List.class);
        verify(cxBackendClient).replaceBiStepRelations(eq(BI_STEP_ID), captor.capture());
        return captor.getValue();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode scenario(String biStepCode, String operations, String relations) {
        try {
            return objectMapper.readTree("""
                    {"e2e":{"uid":"E2E-001","bi_step_code":%s},
                     "interfaces":[{"interface_version_id":1,"code":"crm-api","parent_product_cmdb":"CRM"}],
                     "operations":%s,
                     "operation_relations":%s}
                    """.formatted(biStepCode == null ? "null" : "\"" + biStepCode + "\"", operations, relations));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
