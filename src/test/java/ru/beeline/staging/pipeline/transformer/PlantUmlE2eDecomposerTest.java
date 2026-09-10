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
import static org.mockito.Mockito.when;

class PlantUmlE2eDecomposerTest {

    private static final String UID = "E2E-001";
    private static final String BNPL = "b2c-digital-payments-bnpl";
    private static final String ANTISPAM = "antispam";
    private static final String AI_TOOL = "ai-tool";
    private static final String ARFIX = "arfix";

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
    @DisplayName("В снимок попадают только сопоставленные с ландшафтом вызовы")
    void mapsOnlyMatchedCalls() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);
        E2ESequenceSnapshot snapshot = result.snapshot();

        assertThat(snapshot.getOperations()).extracting(E2ESequenceSnapshot.OperationDraft::getName)
                .containsExactly("/command/createApplication", "/api/v1/calls/", "/chat/completions");
        assertThat(snapshot.getProducts()).extracting(E2ESequenceSnapshot.ProductDraft::getUid)
                .containsExactly(BNPL, ANTISPAM, AI_TOOL);
        assertThat(snapshot.getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getUid)
                .containsExactly("bnpl-api", "antispam-api", "ai-tool-api");
    }

    @Test
    @DisplayName("Self-call, вызовы без эндпоинта и без совпадения исключаются с notice")
    void excludesUnusableCalls() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        List<String> reasons = result.notices().stream()
                .filter(notice -> PlantUmlE2eDecomposer.EXCLUDE.equals(notice.code()))
                .map(ArtifactNotice::details)
                .toList();
        assertThat(reasons).anyMatch(details -> details.contains("self_call"));
        assertThat(reasons).anyMatch(details -> details.contains("no_rest_endpoint"));
        assertThat(reasons).anyMatch(details -> details.contains("no_match_in_landscape"));
    }

    @Test
    @DisplayName("Дерево вызовов: первый вызов корневой, остальные висят на нём")
    void buildsCallTree() {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(universalDiagram(), UID, "Оплата", null);

        String rootUid = PlantUmlE2eDecomposer.operationUid(BNPL, "POST", "/command/createApplication");
        List<E2ESequenceSnapshot.OperationRelationDraft> relations = result.snapshot().getOperationRelations();

        assertThat(relations.get(0).getCallerOperationExtUid()).isNull();
        assertThat(relations.get(0).getCalleeOperationExtUid()).isEqualTo(rootUid);
        assertThat(relations.get(0).getCallOrder()).isZero();
        assertThat(relations.subList(1, relations.size()))
                .allMatch(relation -> rootUid.equals(relation.getCallerOperationExtUid()));
        assertThat(relations.subList(1, relations.size()))
                .extracting(E2ESequenceSnapshot.OperationRelationDraft::getCallOrder)
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("uid операции детерминирован и не зависит от прогона")
    void producesDeterministicOperationUids() {
        List<String> first = decomposer.decompose(universalDiagram(), UID, "Оплата", null).snapshot()
                .getOperations().stream().map(E2ESequenceSnapshot.OperationDraft::getExtUid).toList();
        List<String> second = decomposer.decompose(universalDiagram(), UID, "Оплата", null).snapshot()
                .getOperations().stream().map(E2ESequenceSnapshot.OperationDraft::getExtUid).toList();

        assertThat(first).isEqualTo(second);
        assertThat(first.get(0)).isEqualTo(PlantUmlE2eDecomposer.operationUid(BNPL, "POST", "/command/createApplication"));
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
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.getProtocol()).isEqualTo("REST"));
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

    private String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/e2e/" + name), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Fixture not readable: " + name, e);
        }
    }
}
