package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.dto.usecase.UseCaseDraft;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.pipeline.StageContext;
import ru.beeline.staging.repository.UseCaseLandscapeRepository;
import ru.beeline.staging.repository.UseCaseLandscapeRepository.LandscapeOperation;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UseCaseTransformerTest {

    private static final String DIAGRAM = """
            @startuml
            actor Architect
            participant "web.BC-1" as web
            participant "api.BC-2" as api
            participant "notify.BC-3" as notify
            Architect -> web: Открывает форму
            web -> api: POST /orders
            alt успех
              api -> web: GET /status
            else ошибка
              web -> api: DELETE /orders
            end
            loop повтор
              web -> api: GET /orders
            end
            opt уведомление
              api -> notify: POST /notify
            end
            @enduml
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UseCaseLandscapeRepository landscapeRepository;
    private UseCaseTransformer transformer;

    @BeforeEach
    void setUp() {
        landscapeRepository = mock(UseCaseLandscapeRepository.class);
        transformer = new UseCaseTransformer(new PlantUmlDiagramParser(), landscapeRepository, objectMapper);
    }

    @Test
    @DisplayName("Сообщения раскладываются на mapped/unmapped части с типами сценария и шага из фрагментов")
    void buildsDraftFromDiagram() {
        LandscapeOperation createOrder = new LandscapeOperation(11L, "/orders", "orders_api", "api", "BC-2");
        LandscapeOperation notifyOp = new LandscapeOperation(12L, "/notify", "notify_api", "notify", "BC-3");
        when(landscapeRepository.findOperationByCall("/orders", "POST", "design", "api", "api"))
                .thenReturn(Optional.of(createOrder));
        when(landscapeRepository.findOperationByCall("/notify", "POST", "design", "notify", "notify"))
                .thenReturn(Optional.of(notifyOp));

        TransformResult result = transformer.transform("UC-001", DIAGRAM, context("design"));

        UseCaseDraft draft = (UseCaseDraft) result.snapshot();
        assertThat(result.notices()).isEmpty();
        assertThat(draft.branch()).isEqualTo("design");
        assertThat(draft.usecase().code()).isEqualTo("UC-001");
        assertThat(draft.usecase().projectCode()).isEqualTo("PRJ-1");

        assertThat(draft.mapped()).extracting(UseCaseDraft.MappedPart::partId).containsExactly("P-02", "P-06");
        UseCaseDraft.MappedPart order = draft.mapped().get(0);
        assertThat(order.operation()).isEqualTo("/orders");
        assertThat(order.interfaceCode()).isEqualTo("orders_api");
        assertThat(order.caller()).isNull();
        assertThat(order.callStatus()).isEqualTo("confirmed");

        UseCaseDraft.MappedPart notify = draft.mapped().get(1);
        assertThat(notify.scenarioType()).isEqualTo("exception");
        assertThat(notify.stepType()).isEqualTo("exception");
        assertThat(notify.caller().operation()).isEqualTo("/orders");

        assertThat(draft.unmapped()).extracting(UseCaseDraft.UnmappedPart::partId)
                .containsExactly("P-01", "P-03", "P-04", "P-05");
        assertThat(draft.unmapped().get(0).reason()).contains("нет REST-вызова");
        assertThat(draft.unmapped().get(1).scenarioType()).isEqualTo("alternative");
        assertThat(draft.unmapped().get(1).stepType()).isEqualTo("condition");
        assertThat(draft.unmapped().get(2).scenarioType()).isEqualTo("alternative");
        assertThat(draft.unmapped().get(3).scenarioType()).isEqualTo("main");
        assertThat(draft.unmapped().get(3).stepType()).isEqualTo("loop");
        assertThat(draft.unmapped().get(3).side()).isEqualTo("callee");
        assertThat(draft.unmapped().get(3).participants()).containsExactly("api.BC-2");
    }

    @Test
    @DisplayName("Черновик сериализуется по контракту ADR-028: interface, без null-полей")
    void serializesDraftByContract() throws Exception {
        when(landscapeRepository.findOperationByCall("/orders", "POST", "main", "api", "api"))
                .thenReturn(Optional.of(new LandscapeOperation(11L, "/orders", "orders_api", "api", "BC-2")));

        TransformResult result = transformer.transform("UC-001", DIAGRAM, context(null));
        String json = objectMapper.writeValueAsString(result.snapshot());

        assertThat(json).contains("\"interface\":\"orders_api\"").doesNotContain("interfaceCode").doesNotContain("null");
        UseCaseDraft roundTrip = objectMapper.readValue(json, UseCaseDraft.class);
        assertThat(roundTrip.mapped().get(0).interfaceCode()).isEqualTo("orders_api");
        assertThat(roundTrip.branch()).isEqualTo("main");
    }

    @Test
    @DisplayName("Нераспарсиваемая диаграмма — error-замечание, черновика нет")
    void reportsParseFailure() {
        TransformResult result = transformer.transform("UC-001", "not a diagram", context("main"));

        assertThat(result.snapshot()).isNull();
        assertThat(result.notices()).singleElement()
                .satisfies(notice -> assertThat(notice.code()).isEqualTo(UseCaseTransformer.PARSE_FAILED));
    }

    private StageContext context(String branch) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", "Онлайн-заказ");
        payload.put("projectCode", "PRJ-1");
        return new StageContext(77L, "usecase", "beeatlas-ui", branch, payload);
    }
}
