package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.e2e.CmdbAliasLookup;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant;
import ru.beeline.staging.e2e.CmdbAliasLookup.ResolvedParticipant.Kind;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlantUmlE2eDecomposerTest {

    private static final String UID = "E2E-001";
    private static final String BNPL = "b2c-digital-payments-bnpl";
    private static final String ANTISPAM = "antispam";
    private static final String AI_TOOL = "ai-tool";
    private static final String ARFIX = "arfix";
    private static final String SHOWCASE = "fdmshowcaseapp";
    private static final String MOBILE = "mobileapp";
    private static final String RICH = "rich";

    private CmdbAliasLookup cmdbAliasLookup;
    private ProductServiceClient productServiceClient;
    private PlantUmlE2eDecomposer decomposer;

    @BeforeEach
    void setUp() {
        cmdbAliasLookup = mock(CmdbAliasLookup.class);
        productServiceClient = mock(ProductServiceClient.class);
        decomposer = new PlantUmlE2eDecomposer(new PlantUmlDiagramParser(), cmdbAliasLookup,
                productServiceClient, new ObjectMapper());

        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                BNPL, new ResolvedParticipant(BNPL, "BNPL", Kind.SYSTEM),
                ANTISPAM, new ResolvedParticipant(ANTISPAM, "Антиспам", Kind.SYSTEM),
                AI_TOOL, new ResolvedParticipant(AI_TOOL, "AI Tool", Kind.SYSTEM),
                ARFIX, new ResolvedParticipant(ARFIX, "AR Collection", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(landscapeMatches());
    }

    @Test
    @DisplayName("Код интерфейса — всегда 8 hex-символов, ведущий ноль не теряется")
    void padsTheInterfaceCode() {
        assertThat(PlantUmlE2eDecomposer.interfaceCode("POST", "/api/v1/sequence")).isEqualTo("033d51b4");
        assertThat(PlantUmlE2eDecomposer.interfaceCode("GET", "/api/v1/calls/")).hasSize(8);
        assertThat(PlantUmlE2eDecomposer.interfaceCode("POST", "/chat/completions")).hasSize(8);
    }

    @Test
    @DisplayName("Контекст паузы несёт участников, стороны вызова и подписи — того, чего нет в каноне")
    void buildsThePauseContext() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        assertThat(result.pauseContext()).isNotNull();
        assertThat(result.pauseContext().e2e().uid()).isEqualTo(UID);
        assertThat(result.pauseContext().e2e().name()).isEqualTo("Оплата");
        assertThat(result.pauseContext().participants()).isNotEmpty();
        assertThat(result.pauseContext().participants())
                .allSatisfy(participant -> assertThat(participant.alias()).isNotBlank());
        assertThat(result.pauseContext().requests()).isNotEmpty();
        assertThat(result.pauseContext().requests()).allSatisfy(request -> {
            assertThat(request.fromAlias()).isNotBlank();
            assertThat(request.toAlias()).isNotBlank();
            assertThat(request.label()).isNotBlank();
            assertThat(request.interfaceCode()).isNotBlank();
            assertThat(request.match().status()).isIn("matched", "not_found", "skipped");
        });
        assertThat(result.pauseContext().requests())
                .filteredOn(request -> request.unknown())
                .allSatisfy(request -> assertThat(request.match().status()).isEqualTo("skipped"));
    }

    @Test
    @DisplayName("Каждый вызов даёт свой интерфейс с кодом-хешем, продукт берётся у получателя")
    void mapsEveryCallToItsOwnInterface() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);
        E2ESequenceSnapshot snapshot = result.snapshot();

        assertThat(snapshot.getOperations()).extracting(E2ESequenceSnapshot.OperationDraft::getName)
                .contains("/command/createApplication", "/api/v1/calls/", "/api/v1/calls/feedback",
                        "/chat/completions", "/api/v1/payment/12345/paymentItem", "reconciliation-note");
        assertThat(snapshot.getProducts()).extracting(E2ESequenceSnapshot.ProductDraft::getUid)
                .containsExactly(BNPL, ANTISPAM, AI_TOOL, ARFIX);
        assertThat(snapshot.getOperations())
                .filteredOn(draft -> "/api/v1/calls/feedback".equals(draft.getName()))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.getType()).isEqualTo("POST");
                    assertThat(draft.getInterfaceUid())
                            .isEqualTo(PlantUmlE2eDecomposer.interfaceCode("POST", "/api/v1/calls/feedback"));
                });
        assertThat(snapshot.getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getName)
                .contains("POST /command/createApplication");
    }

    @Test
    @DisplayName("Сопоставленный вызов несёт id арх-операции, несопоставленный — пустой")
    void keepsTheMatchedArchOperationIdOnTheDraft() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        assertThat(result.snapshot().getOperations())
                .filteredOn(draft -> "/command/createApplication".equals(draft.getName()))
                .singleElement()
                .satisfies(draft -> assertThat(draft.getConnectionOperationId())
                        .isEqualTo(Math.abs((BNPL + "/command/createApplication" + "POST").hashCode())));
        assertThat(result.snapshot().getOperations())
                .filteredOn(draft -> "/api/v1/calls/feedback".equals(draft.getName()))
                .singleElement()
                .satisfies(draft -> assertThat(draft.getConnectionOperationId()).isNull());
    }

    @Test
    @DisplayName("Несопоставленные вызовы не исключаются, а выносятся в warning notice")
    void reportsUnmatchedCalls() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        assertThat(result.notices())
                .filteredOn(notice -> PlantUmlE2eDecomposer.NOT_IN_LANDSCAPE.equals(notice.code()))
                .hasSize(3)
                .allMatch(notice -> "warning".equals(notice.level()));
    }

    @Test
    @DisplayName("Self-call и вызовы без эндпоинта исключаются с notice")
    void excludesUnusableCalls() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        List<String> reasons = result.notices().stream()
                .filter(notice -> PlantUmlE2eDecomposer.EXCLUDE.equals(notice.code()))
                .map(ArtifactNotice::details)
                .toList();
        assertThat(reasons).anyMatch(details -> details.contains("self_call"));
        assertThat(reasons).noneMatch(details -> details.contains("no_rest_endpoint"));
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .contains(PlantUmlE2eDecomposer.UNKNOWN_REQUEST);
        assertThat(reasons).noneMatch(details -> details.contains("no_match_in_landscape"));
    }

    @Test
    @DisplayName("Вызов внутри вызова к участнику вне CMDB исключается вместе с родителем, а не становится корнем")
    void excludesCallNestedInExcludedCall() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM),
                RICH, new ResolvedParticipant(RICH, "Rich", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of(
                match(SHOWCASE, "/api/v1/sequence", "POST", "showcase-api", "showcase-core"),
                match(RICH, "/pair-request", "POST", "rich-api", "rich-core")));

        PlantUmlE2eDecomposer.Result result = decomposer.decompose(nestedDiagram(), UID, "Витрина", null);

        List<E2ESequenceSnapshot.OperationRelationDraft> relations = result.snapshot().getOperationRelations();
        assertThat(relations).singleElement().satisfies(relation -> {
            assertThat(relation.getCallerOperationExtUid()).isNull();
            assertThat(relation.getCalleeOperationExtUid())
                    .isEqualTo(PlantUmlE2eDecomposer.operationUid(SHOWCASE, PlantUmlE2eDecomposer.interfaceCode("POST", "/api/v1/sequence"), "POST", "/api/v1/sequence"));
        });
        assertThat(result.snapshot().getOperations()).extracting(E2ESequenceSnapshot.OperationDraft::getName)
                .containsExactly("/api/v1/sequence");
        assertThat(result.notices())
                .filteredOn(notice -> PlantUmlE2eDecomposer.EXCLUDE.equals(notice.code()))
                .extracting(ArtifactNotice::details)
                .anyMatch(details -> details.contains("receiver_not_in_cmdb"))
                .anyMatch(details -> details.contains("parent_excluded") && details.contains("\"parentLine\":7"));
    }

    @Test
    @DisplayName("Вызов внутри несопоставленного вызова висит на нём, а не на корне")
    void attachesCallNestedInUnmatchedCall() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM),
                MOBILE, new ResolvedParticipant(MOBILE, "Mobile", Kind.SYSTEM),
                RICH, new ResolvedParticipant(RICH, "Rich", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of(
                match(SHOWCASE, "/api/v1/sequence", "POST", "showcase-api", "showcase-core"),
                match(RICH, "/pair-request", "POST", "rich-api", "rich-core")));

        PlantUmlE2eDecomposer.Result result = decomposer.decompose(nestedDiagram(), UID, "Витрина", null);

        String root = PlantUmlE2eDecomposer.operationUid(SHOWCASE, PlantUmlE2eDecomposer.interfaceCode("POST", "/api/v1/sequence"), "POST", "/api/v1/sequence");
        String unmatched = PlantUmlE2eDecomposer.operationUid(MOBILE, PlantUmlE2eDecomposer.interfaceCode("GET", "/fcp-pi/v2/products"), "GET", "/fcp-pi/v2/products");
        String nested = PlantUmlE2eDecomposer.operationUid(RICH, PlantUmlE2eDecomposer.interfaceCode("POST", "/pair-request"), "POST", "/pair-request");
        assertThat(result.snapshot().getOperationRelations())
                .extracting(E2ESequenceSnapshot.OperationRelationDraft::getCallerOperationExtUid,
                        E2ESequenceSnapshot.OperationRelationDraft::getCalleeOperationExtUid,
                        E2ESequenceSnapshot.OperationRelationDraft::getCallOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(null, root, 0),
                        org.assertj.core.groups.Tuple.tuple(root, unmatched, 0),
                        org.assertj.core.groups.Tuple.tuple(unmatched, nested, 0));
        assertThat(result.snapshot().getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getUid)
                .contains(PlantUmlE2eDecomposer.interfaceCode("GET", "/fcp-pi/v2/products"));
    }

    @Test
    @DisplayName("Операция на интерфейсе с protocol=UNKNOWN сопоставляется")
    void matchesAnOperationRegardlessOfInterfaceProtocol() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM),
                MOBILE, new ResolvedParticipant(MOBILE, "Mobile", Kind.SYSTEM),
                RICH, new ResolvedParticipant(RICH, "Rich", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of(
                match(SHOWCASE, "/api/v1/sequence", "POST", "showcase-api", "showcase-core"),
                match(MOBILE, "/fcp-pi/v2/products", "GET", "mobileapp-rest-api", "mobileapp-core"),
                match(RICH, "/pair-request", "POST", "rich-api", "rich-core")));

        PlantUmlE2eDecomposer.Result result = decomposer.decompose(nestedDiagram(), UID, "Витрина", null);

        assertThat(result.snapshot().getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getUid)
                .contains(PlantUmlE2eDecomposer.interfaceCode("GET", "/fcp-pi/v2/products"));
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .doesNotContain(PlantUmlE2eDecomposer.NOT_IN_LANDSCAPE);
    }

    @Test
    @DisplayName("Неоднозначный получатель исключается с отдельной причиной")
    void excludesCallToAnAmbiguousReceiver() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM),
                MOBILE, new ResolvedParticipant(MOBILE, "Mobile", Kind.SYSTEM, MOBILE,
                        new ResolvedParticipant(MOBILE, "Mobile Container", Kind.CONTAINER, SHOWCASE)),
                RICH, new ResolvedParticipant(RICH, "Rich", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of(
                match(SHOWCASE, "/api/v1/sequence", "POST", "showcase-api", "showcase-core")));

        PlantUmlE2eDecomposer.Result result = decomposer.decompose(nestedDiagram(), UID, "Витрина", null);

        assertThat(result.notices())
                .filteredOn(notice -> PlantUmlE2eDecomposer.EXCLUDE.equals(notice.code()))
                .extracting(ArtifactNotice::details)
                .anyMatch(details -> details.contains("receiver_ambiguous"));
        assertThat(result.snapshot().getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getUid)
                .doesNotContain(PlantUmlE2eDecomposer.interfaceCode("GET", "/fcp-pi/v2/products"));
    }

    @Test
    @DisplayName("Дерево вызовов: первый вызов корневой, остальные висят на нём")
    void buildsCallTree() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        String rootUid = PlantUmlE2eDecomposer.operationUid(BNPL, PlantUmlE2eDecomposer.interfaceCode("POST", "/command/createApplication"), "POST", "/command/createApplication");
        List<E2ESequenceSnapshot.OperationRelationDraft> relations = result.snapshot().getOperationRelations();

        assertThat(relations.get(0).getCallerOperationExtUid()).isNull();
        assertThat(relations.get(0).getCalleeOperationExtUid()).isEqualTo(rootUid);
        assertThat(relations.get(0).getCallOrder()).isZero();
        assertThat(relations.subList(1, relations.size()))
                .allMatch(relation -> relation.getCallerOperationExtUid() != null);
        assertThat(result.snapshot().getOperations())
                .extracting(E2ESequenceSnapshot.OperationDraft::getType)
                .contains("UNKNOWN");
    }

    @Test
    @DisplayName("uid операции детерминирован и не зависит от прогона")
    void producesDeterministicOperationUids() {
        List<String> first = decomposer.decompose(universalDiagram(), UID, "Оплата", null).snapshot()
                .getOperations().stream().map(E2ESequenceSnapshot.OperationDraft::getExtUid).toList();
        List<String> second = decomposer.decompose(universalDiagram(), UID, "Оплата", null).snapshot()
                .getOperations().stream().map(E2ESequenceSnapshot.OperationDraft::getExtUid).toList();

        assertThat(first).isEqualTo(second);
        assertThat(first.get(0)).isEqualTo(PlantUmlE2eDecomposer.operationUid(BNPL, PlantUmlE2eDecomposer.interfaceCode("POST", "/command/createApplication"), "POST", "/command/createApplication"));
    }

    @Test
    @DisplayName("Один и тот же путь у разных получателей даёт разные операции")
    void doesNotCollapseTheSamePathCalledOnDifferentParticipants() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM),
                MOBILE, new ResolvedParticipant(MOBILE, "Mobile", Kind.SYSTEM),
                RICH, new ResolvedParticipant(RICH, "Rich", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of());

        PlantUmlE2eDecomposer.Result result = decomposer.decompose("""
                @startuml
                participant fdmshowcaseapp
                participant mobileapp
                participant rich
                fdmshowcaseapp -> mobileapp: POST /workspace
                fdmshowcaseapp -> rich: POST /workspace
                @enduml
                """, UID, "Витрина", null);

        assertThat(result.snapshot().getOperations())
                .extracting(E2ESequenceSnapshot.OperationDraft::getExtUid)
                .doesNotHaveDuplicates()
                .hasSize(2);
        assertThat(result.snapshot().getOperations())
                .extracting(E2ESequenceSnapshot.OperationDraft::getInterfaceUid)
                .containsOnly(PlantUmlE2eDecomposer.interfaceCode("POST", "/workspace"));
        assertThat(result.snapshot().getProducts())
                .extracting(E2ESequenceSnapshot.ProductDraft::getUid)
                .containsExactlyInAnyOrder(MOBILE, RICH);
    }

    @Test
    @DisplayName("Нераспознанный вызов становится операцией UNKNOWN, путь обрезается и сопоставление не запрашивается")
    void registersAnUnparsedCallAsAnUnknownRequest() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM),
                MOBILE, new ResolvedParticipant(MOBILE, "Mobile", Kind.SYSTEM)));
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of());
        String longLabel = "получает справочник " + "я".repeat(300);

        PlantUmlE2eDecomposer.Result result = decomposer.decompose("""
                @startuml
                participant fdmshowcaseapp
                participant mobileapp
                fdmshowcaseapp -> mobileapp: %s
                @enduml
                """.formatted(longLabel), UID, "Витрина", null);

        assertThat(result.snapshot().getOperations()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo("UNKNOWN");
            assertThat(draft.getName()).hasSize(255).startsWith("получает справочник");
            assertThat(draft.getConnectionOperationId()).isNull();
        });
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .contains(PlantUmlE2eDecomposer.UNKNOWN_REQUEST);
        verify(productServiceClient, never()).searchMatchedOperations(anyList());
    }

    @Test
    @DisplayName("Совпадение с другим именем параметра сшивается по эху запроса")
    void stitchesAMatchWhoseCatalogNameDiffersFromTheRequestedPath() {
        when(cmdbAliasLookup.resolveAll(anySet())).thenReturn(Map.of(
                SHOWCASE, new ResolvedParticipant(SHOWCASE, "Showcase", Kind.SYSTEM)));
        MatchedArchOperation matched = match(SHOWCASE, "/api/v1/product/{code}", "GET",
                "ext_product-api", "ext_container_product");
        matched.setRequestedMethodName("/api/v1/product/{cmdb}");
        when(productServiceClient.searchMatchedOperations(anyList())).thenReturn(List.of(matched));

        PlantUmlE2eDecomposer.Result result = decomposer.decompose("""
                @startuml
                participant fdmshowcaseapp
                participant rich
                rich -> fdmshowcaseapp: GET /api/v1/product/{cmdb}
                @enduml
                """, UID, "Витрина", null);

        assertThat(result.snapshot().getOperations()).singleElement().satisfies(draft -> {
            assertThat(draft.getConnectionOperationId()).isEqualTo(matched.getId());
            assertThat(draft.getMatchedOperation()).containsEntry("name", "/api/v1/product/{code}");
        });
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .doesNotContain(PlantUmlE2eDecomposer.NOT_IN_LANDSCAPE);
    }

    @Test
    @DisplayName("Данные сопоставленной арх-операции складываются в снимок для UI")
    void keepsMatchedArchOperationAttributes() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        assertThat(result.snapshot().getOperations())
                .filteredOn(draft -> "/command/createApplication".equals(draft.getName()))
                .singleElement()
                .satisfies(draft -> assertThat(draft.getMatchedOperation())
                        .containsEntry("name", "/command/createApplication")
                        .containsEntry("type", "POST")
                        .containsEntry("interfaceCode", "bnpl-api")
                        .containsEntry("containerCode", "bnpl-gateway")
                        .containsEntry("productAlias", BNPL));
        assertThat(result.snapshot().getOperations())
                .filteredOn(draft -> "/api/v1/calls/feedback".equals(draft.getName()))
                .singleElement()
                .satisfies(draft -> assertThat(draft.getMatchedOperation()).isNull());
    }

    @Test
    @DisplayName("Недостающие protocol/source/SLA заполняются дефолтами с notice")
    void fillsMissingAttributesWithDefaults() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        assertThat(result.snapshot().getInterfaces())
                .allSatisfy(draft -> {
                    assertThat(draft.getProtocol()).isEqualTo("UNKNOWN");
                    assertThat(draft.getSource()).isNull();
                });
        assertThat(result.snapshot().getOperations()).allSatisfy(draft -> assertThat(draft.getRps()).isNull());
        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .contains(PlantUmlE2eDecomposer.IMPLICIT_CAST);
    }

    @Test
    @DisplayName("biStepCode попадает в biSteps и в сценарий")
    void mapsBiStep() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", "Step.00.00.02.08");

        assertThat(result.snapshot().getBiSteps()).extracting(E2ESequenceSnapshot.BiStepDraft::getUid)
                .containsExactly("Step.00.00.02.08");
        assertThat(result.snapshot().getE2eScenario().getBiStepUid()).isEqualTo("Step.00.00.02.08");
        assertThat(result.snapshot().getE2eScenario().getUid()).isEqualTo(UID);
    }

    @Test
    @DisplayName("Недоступный search-matched — error notice, снимок без сущностей")
    void reportsSearchMatchedFailure() {
        when(productServiceClient.searchMatchedOperations(anyList()))
                .thenThrow(new IllegalStateException("fdm-products unreachable"));

        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .contains(PlantUmlE2eDecomposer.SEARCH_UNAVAILABLE);
        assertThat(result.snapshot().getOperations()).isEmpty();
        assertThat(result.snapshot().getOperationRelations()).isEmpty();
    }

    @Test
    @DisplayName("Нераспарсенный текст — error notice parse_failed")
    void reportsParseFailure() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(fixture("syntax_error.puml"), UID, "Оплата", null);

        assertThat(result.notices()).extracting(ArtifactNotice::code)
                .containsExactly(PlantUmlE2eDecomposer.PARSE_FAILED);
        assertThat(result.snapshot().getOperations()).isEmpty();
    }

    @Test
    @DisplayName("Кандидаты уходят одним батчем без дублей")
    void sendsDistinctCandidatesInOneBatch() {
        decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<OperationMatchCandidate>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(productServiceClient).searchMatchedOperations(captor.capture());

        List<OperationMatchCandidate> candidates = captor.getValue();
        assertThat(candidates).extracting(OperationMatchCandidate::getMethodName)
                .containsExactly("/command/createApplication", "/api/v1/calls/", "/api/v1/calls/feedback",
                        "/chat/completions", "/api/v1/payment/12345/paymentItem", "reconciliation-note");
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.getProtocol()).isNull());
        assertThat(candidates).extracting(OperationMatchCandidate::getProductCode)
                .containsExactly(BNPL, ANTISPAM, ANTISPAM, AI_TOOL, ARFIX, ARFIX);
    }

    private List<MatchedArchOperation> landscapeMatches() {
        return List.of(
                match(BNPL, "/command/createApplication", "POST", "bnpl-api", "bnpl-gateway"),
                match(ANTISPAM, "/api/v1/calls/", "GET", "antispam-api", "antispam-core"),
                match(AI_TOOL, "/chat/completions", "POST", "ai-tool-api", "ai-tool-core"));
    }

    private MatchedArchOperation match(String productCode, String name, String type,
            String interfaceCode, String containerCode) {
        MatchedArchOperation matched = new MatchedArchOperation();
        matched.setId(Math.abs((productCode + name + type).hashCode()));
        matched.setName(name);
        matched.setType(type);
        matched.setProductCode(productCode);

        MatchedArchOperation.Ref interfaceRef = new MatchedArchOperation.Ref();
        interfaceRef.setCode(interfaceCode);
        interfaceRef.setName(interfaceCode);
        matched.setInterfaceObj(interfaceRef);

        MatchedArchOperation.Ref containerRef = new MatchedArchOperation.Ref();
        containerRef.setCode(containerCode);
        containerRef.setName(containerCode);
        matched.setContainer(containerRef);

        MatchedArchOperation.ProductRef productRef = new MatchedArchOperation.ProductRef();
        productRef.setAlias(productCode);
        productRef.setName(productCode);
        matched.setProduct(productRef);
        return matched;
    }

    private String universalDiagram() {
        return fixture("universal.puml");
    }

    private String nestedDiagram() {
        return """
                @startuml
                participant BLN
                participant fdmshowcaseapp
                participant mobileapp
                participant rich
                BLN -> fdmshowcaseapp: POST /api/v1/sequence
                fdmshowcaseapp -> mobileapp: GET /fcp-pi/v2/products
                mobileapp -> rich: POST /pair-request
                @enduml
                """;
    }

    private String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/e2e/" + name), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Fixture not readable: " + name, e);
        }
    }
}
