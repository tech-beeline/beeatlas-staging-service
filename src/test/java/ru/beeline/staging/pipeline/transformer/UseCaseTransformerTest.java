package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.e2e.CmdbAliasLookup;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UseCaseTransformerTest {

    private static final String DIAGRAM = """
            @startuml
            actor Architect
            participant "web.BC-1" as web
            participant "api.BC-2" as api
            Architect -> web: Открывает форму
            web -> api: POST /orders
            alt успех
              api -> web: GET /status
            end
            web -> api: уточняет статус
            api --> web: ответ
            @enduml
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CmdbAliasLookup cmdbAliasLookup;
    private ProductServiceClient productServiceClient;
    private UseCaseTransformer transformer;

    @BeforeEach
    void setUp() {
        cmdbAliasLookup = mock(CmdbAliasLookup.class);
        productServiceClient = mock(ProductServiceClient.class);
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                "web.BC-1", new CmdbAliasLookup.ResolvedParticipant("web", "Web",
                        CmdbAliasLookup.ResolvedParticipant.Kind.SYSTEM, "BC-1"),
                "api.BC-2", new CmdbAliasLookup.ResolvedParticipant("api", "Api",
                        CmdbAliasLookup.ResolvedParticipant.Kind.SYSTEM, "BC-2")));
        when(productServiceClient.searchMatchedOperations(any())).thenReturn(List.of());
        transformer = new UseCaseTransformer(new PlantUmlDiagramParser(), cmdbAliasLookup, productServiceClient,
                objectMapper);
    }

    @Test
    @DisplayName("Каждое сообщение даёт шаг со своей операцией; сопоставленная операция несёт connectionOperationId")
    void buildsStepsAndOperations() {
        when(productServiceClient.searchMatchedOperations(any())).thenReturn(List.of(match("BC-2", "/orders", "POST")));

        TransformResult result = transformer.transform("UC-001", DIAGRAM, context("design"));
        UseCaseSnapshot snapshot = (UseCaseSnapshot) result.snapshot();

        assertThat(snapshot.getBranch()).isEqualTo("design");
        assertThat(snapshot.getUsecase().getCode()).isEqualTo("UC-001");
        assertThat(snapshot.getUsecase().getProjectCode()).isEqualTo("PRJ-1");
        assertThat(snapshot.getSteps()).extracting(UseCaseSnapshot.Step::getPartId)
                .containsExactly("P-01", "P-02", "P-03", "P-04");

        UseCaseSnapshot.Step order = snapshot.getSteps().get(1);
        assertThat(order.getOperationType()).isEqualTo("POST");
        assertThat(order.getOperationName()).isEqualTo("/orders");
        assertThat(order.getProductAlias()).isEqualTo("BC-2");
        assertThat(order.getCalleeOperationExtUid()).isNotBlank();
        assertThat(order.getReason()).isNull();

        UseCaseSnapshot.Step status = snapshot.getSteps().get(2);
        assertThat(status.getScenarioType()).isEqualTo("alternative");
        assertThat(status.getStepType()).isEqualTo("condition");
        assertThat(status.getCallerOperationExtUid()).isEqualTo(order.getCalleeOperationExtUid());
        assertThat(status.getReason()).contains("не найдена в архитектуре");

        assertThat(snapshot.getEntities().getOperations())
                .filteredOn(operation -> "/orders".equals(operation.getName()))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.getConnectionOperationId()).isEqualTo(4242);
                    assertThat(operation.getMatchedOperation()).containsEntry("productAlias", "BC-2");
                });
        assertThat(snapshot.getEntities().getProducts()).extracting(E2ESequenceSnapshot.ProductDraft::getUid)
                .contains("BC-1", "BC-2");
    }

    @Test
    @DisplayName("Сообщение без REST-вызова сохраняется операцией типа UNKNOWN и остаётся без архитектурной связи")
    void keepsUnknownRequestsAsOperations() {
        TransformResult result = transformer.transform("UC-001", DIAGRAM, context("main"));
        UseCaseSnapshot snapshot = (UseCaseSnapshot) result.snapshot();

        UseCaseSnapshot.Step free = snapshot.getSteps().get(0);
        assertThat(free.getOperationType()).isEqualTo("UNKNOWN");
        assertThat(free.getOperationName()).isEqualTo("Открывает форму");
        assertThat(free.getReason()).contains("нет REST-вызова");
        assertThat(result.notices()).extracting("code").contains(UseCaseTransformer.UNKNOWN_REQUEST);
    }

    @Test
    @DisplayName("Снапшот сериализуется без null-полей и читается обратно")
    void serializesSnapshot() throws Exception {
        TransformResult result = transformer.transform("UC-001", DIAGRAM, context(null));

        String json = objectMapper.writeValueAsString(result.snapshot());
        UseCaseSnapshot roundTrip = objectMapper.readValue(json, UseCaseSnapshot.class);
        assertThat(roundTrip.getBranch()).isEqualTo("main");
        assertThat(roundTrip.getSteps()).hasSize(4);
    }

    @Test
    @DisplayName("Недоступный поиск сопоставлений — error-замечание, снапшота нет")
    void reportsSearchFailure() {
        when(productServiceClient.searchMatchedOperations(any())).thenThrow(new IllegalStateException("products down"));

        TransformResult result = transformer.transform("UC-001", DIAGRAM, context("main"));

        assertThat(result.snapshot()).isNull();
        assertThat(result.notices()).extracting("code").contains(UseCaseTransformer.SEARCH_UNAVAILABLE);
    }

    @Test
    @DisplayName("Нераспарсиваемая диаграмма — error-замечание, снапшота нет")
    void reportsParseFailure() {
        TransformResult result = transformer.transform("UC-001", "not a diagram", context("main"));

        assertThat(result.snapshot()).isNull();
        assertThat(result.notices()).singleElement()
                .satisfies(notice -> assertThat(notice.code()).isEqualTo(UseCaseTransformer.PARSE_FAILED));
    }

    private MatchedArchOperation match(String productCode, String path, String method) {
        MatchedArchOperation matched = new MatchedArchOperation();
        matched.setId(4242);
        matched.setName(path);
        matched.setType(method);
        matched.setProductCode(productCode);
        MatchedArchOperation.Ref iface = new MatchedArchOperation.Ref();
        iface.setId(7);
        iface.setCode("orders_api.api.BC-2");
        iface.setName("Orders API");
        matched.setInterfaceObj(iface);
        MatchedArchOperation.ProductRef product = new MatchedArchOperation.ProductRef();
        product.setAlias(productCode);
        product.setName("Заказы");
        matched.setProduct(product);
        return matched;
    }

    private StageContext context(String branch) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", "Онлайн-заказ");
        payload.put("projectCode", "PRJ-1");
        return new StageContext(77L, "usecase", "beeatlas-ui", branch, payload);
    }
}
