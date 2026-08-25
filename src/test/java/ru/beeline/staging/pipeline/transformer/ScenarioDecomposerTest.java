package ru.beeline.staging.pipeline.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dto.notice.ArtifactNotice;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioDecomposerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScenarioDecomposer decomposer = new ScenarioDecomposer(objectMapper);

    /**
     * One scenario exercising every branch of the collapse algorithm at once:
     *  - M1: a normal, resolvable call — survives, registers interface iface.b / operation OP1.
     *  - M2: nested under M1, different app (SYS_C), show_in_e2e=1 and a different operation_guid
     *        than its parent — kept via the "always show" escape hatch (checked before same-app-code).
     *  - M3: nested under M1, same app as M1's own callee (SYS_B) and show_in_e2e=0 — collapsed as
     *        an internal call (proves the escape hatch above isn't just "same app_code never hides").
     *  - M4: root-level call to an object with no resolvable operation at all — hidden because the
     *        (synthetic, app_front) root context forces it (rule 1), not because of same-app-code.
     *  - M5: a return message (pdata4=1) — dropped before collapse even runs, as routine sequence noise.
     */
    @Test
    void decomposesSurvivingCallsAndRecordsNoticesForEveryFilteredFragment() throws Exception {
        String json = """
            {
              "entrance_diagram_uid": "D1",
              "diagrams": [
                {
                  "uid": "D1",
                  "name": "Root scenario",
                  "notes": "step_id=Step.01.00.00.00",
                  "messages": [
                    {"uid":"M1","name":"CallB","start_object_id":1,"end_object_id":2,"operation_guid":"OP1","seqno":1,"pdata4":"0"},
                    {"uid":"M2","name":"CallC","start_object_id":2,"end_object_id":3,"operation_guid":"OP2","seqno":2,"pdata4":"0"},
                    {"uid":"M3","name":"InternalCall","start_object_id":2,"end_object_id":4,"operation_guid":"OP3","seqno":3,"pdata4":"0"},
                    {"uid":"M4","name":"Unresolved","start_object_id":1,"end_object_id":99,"seqno":4,"pdata4":"0"},
                    {"uid":"M5","name":"ReturnFromB","start_object_id":2,"end_object_id":1,"seqno":5,"pdata4":"1"}
                  ]
                }
              ],
              "objects": [
                {"id":1,"name":"Actor","alias":"ACTOR"},
                {"id":2,"name":"B","alias":"SYS_B"},
                {"id":3,"name":"C","alias":"SYS_C"},
                {"id":4,"name":"B-internal","alias":"SYS_B"}
              ],
              "systems": [],
              "containers": [
                {"id":100,"code":"container.b.SYS_B","name":"Container B","system_code":"SYS_B"},
                {"id":200,"code":"container.c.SYS_C","name":"Container C","system_code":"SYS_C"}
              ],
              "interfaces": [
                {"id":10,"code":"iface.b.container.b.SYS_B","name":"Iface B","source":"manual","container_id":100,"tags":[{"property":"show_in_e2e","value":"0"},{"property":"app_front","value":"0"},{"property":"protocol","value":"rest"}]},
                {"id":20,"code":"iface.c.container.c.SYS_C","name":"Iface C","source":"structurizr","container_id":200,"tags":[{"property":"show_in_e2e","value":"1"},{"property":"app_front","value":"0"}]}
              ],
              "operations": [
                {"uid":"OP1","name":"DoB","interface_id":10,"tags":[{"property":"rps","value":"10"},{"property":"latency","value":"20"},{"property":"error_rate","value":"0.1"}]},
                {"uid":"OP2","name":"DoC","interface_id":20,"tags":[]},
                {"uid":"OP3","name":"InternalDoB","interface_id":10,"tags":[]}
              ]
            }
            """;

        JsonNode root = objectMapper.readTree(json);
        ScenarioDecomposer.Result result = decomposer.decompose(root, "scenario-1");

        assertThat(result.stepId()).isEqualTo("Step.01.00.00.00");

        E2ESequenceSnapshot snapshot = result.snapshot();
        List<String> operationExtUids = snapshot.getOperations().stream()
                .map(E2ESequenceSnapshot.OperationDraft::getExtUid).toList();

        assertThat(operationExtUids).contains("OP1", "OP2");
        assertThat(operationExtUids).doesNotContain("OP3");

        assertThat(snapshot.getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getUid)
                .contains("iface.b", "iface.c");
        // ext_uid = code (same as uid), not the raw Sparx id — per transform-spec §4.2.
        assertThat(snapshot.getInterfaces()).filteredOn(i -> "iface.b".equals(i.getUid()))
                .extracting(E2ESequenceSnapshot.InterfaceDraft::getExtUid)
                .containsExactly("iface.b");
        assertThat(snapshot.getInterfaces()).filteredOn(i -> "iface.c".equals(i.getUid()))
                .extracting(E2ESequenceSnapshot.InterfaceDraft::getSource)
                .containsExactly("structurizr");

        // Root-level call (M1 -> OP1): no caller operation, stored with operation_version_id = NULL.
        assertThat(snapshot.getOperationRelations())
                .anyMatch(r -> r.getCallerOperationExtUid() == null && "OP1".equals(r.getCalleeOperationExtUid()));
        assertThat(snapshot.getOperationRelations())
                .anyMatch(r -> "OP1".equals(r.getCallerOperationExtUid()) && "OP2".equals(r.getCalleeOperationExtUid()));
        assertThat(snapshot.getOperationRelations())
                .noneMatch(r -> "OP3".equals(r.getCalleeOperationExtUid()));

        assertThat(snapshot.getE2eScenario().getUid()).isEqualTo("D1");
        assertThat(snapshot.getE2eScenario().getBiStepUid()).isEqualTo("Step.01.00.00.00");
        assertThat(snapshot.getE2eScenario().getDescription()).isEqualTo("step_id=Step.01.00.00.00");

        List<ArtifactNotice> notices = result.notices();

        assertHasNotice(notices, "transform.exclude", "M3", "child.app_code==parent.app_code");
        assertHasNotice(notices, "transform.exclude", "M5", "is_ret");

        assertThat(notices).allMatch(n -> "transform".equals(n.category()) || "match".equals(n.category()));
    }

    @Test
    void emitsMapFailedNoticeForUnresolvedStartObjectId() throws Exception {
        String json = """
            {
              "entrance_diagram_uid": "D1",
              "diagrams": [
                {
                  "uid": "D1",
                  "name": "Root scenario",
                  "notes": "step_id=Step.01.00.00.00",
                  "messages": [
                    {"uid":"M1","name":"CallB","start_object_id":77,"end_object_id":2,"operation_guid":"OP1","seqno":1,"pdata4":"0"}
                  ]
                }
              ],
              "objects": [ {"id":2,"name":"B","alias":"SYS_B"} ],
              "systems": [],
              "interfaces": [ {"id":10,"code":"iface.b","tags":[]} ],
              "operations": [ {"uid":"OP1","name":"DoB","interface_id":10,"tags":[]} ]
            }
            """;

        ScenarioDecomposer.Result result = decomposer.decompose(objectMapper.readTree(json), "scenario-3");

        assertHasNotice(result.notices(), "transform.map_failed", "M1", "missing_reference");
        assertThat(result.notices()).anyMatch(n -> "transform.map_failed".equals(n.code())
                && n.details() != null && n.details().contains("\"start_object_id\""));
    }

    @Test
    void dropsOperationWhenItsInterfaceHasBlankName() throws Exception {
        String json = """
            {
              "entrance_diagram_uid": "D1",
              "diagrams": [
                {
                  "uid": "D1",
                  "name": "Root scenario",
                  "notes": "step_id=Step.01.00.00.00",
                  "messages": [
                    {"uid":"M1","name":"CallB","start_object_id":1,"end_object_id":2,"operation_guid":"OP1","seqno":1,"pdata4":"0"}
                  ]
                }
              ],
              "objects": [
                {"id":1,"name":"Actor","alias":"ACTOR"},
                {"id":2,"name":"B","alias":"SYS_B"}
              ],
              "systems": [],
              "containers": [
                {"id":100,"code":"container.b.SYS_B","name":"Container B","system_code":"SYS_B"}
              ],
              "interfaces": [
                {"id":10,"code":"iface.b.container.b.SYS_B","name":"","source":"manual","container_id":100,"tags":[]}
              ],
              "operations": [
                {"uid":"OP1","name":"DoB","interface_id":10,"tags":[]}
              ]
            }
            """;

        ScenarioDecomposer.Result result = decomposer.decompose(objectMapper.readTree(json), "scenario-4");

        E2ESequenceSnapshot snapshot = result.snapshot();
        assertThat(snapshot.getOperations()).extracting(E2ESequenceSnapshot.OperationDraft::getExtUid)
                .doesNotContain("OP1");
        assertThat(snapshot.getInterfaces()).extracting(E2ESequenceSnapshot.InterfaceDraft::getUid)
                .doesNotContain("iface.b");

        assertThat(result.notices()).anyMatch(n -> "transform.exclude".equals(n.code())
                && n.details() != null && n.details().contains("\"missing_interface_name\""));
    }

    @Test
    void createsScenarioWithoutBiStepWhenRootDiagramHasNoStepId() throws Exception {
        String json = """
            {
              "entrance_diagram_uid": "D1",
              "diagrams": [ {"uid":"D1","name":"Root","notes":"no step id here","messages":[]} ],
              "objects": [], "systems": [], "interfaces": [], "operations": []
            }
            """;

        ScenarioDecomposer.Result result = decomposer.decompose(objectMapper.readTree(json), "scenario-2");

        assertThat(result.snapshot().getBiSteps()).isEmpty();
        assertThat(result.snapshot().getE2eScenario().getUid()).isEqualTo("D1");
        assertThat(result.snapshot().getE2eScenario().getBiStepUid()).isNull();
        assertThat(result.snapshot().getE2eScenario().getDescription()).isNull();
        assertThat(result.notices()).anyMatch(n ->
                "transform.map_failed".equals(n.code()) && "warning".equals(n.level()));
    }

    private void assertHasNotice(List<ArtifactNotice> notices, String code, String messageUid, String expectedReason) {
        Predicate<ArtifactNotice> matches = n -> {
            if (!code.equals(n.code())) return false;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = objectMapper.readValue(n.details(), Map.class);
                boolean uidMatches = messageUid.equals(details.get("message_uid"));
                boolean reasonMatches = expectedReason.equals(details.get("type")) || expectedReason.equals(details.get("reason"));
                return uidMatches && reasonMatches;
            } catch (Exception e) {
                return false;
            }
        };
        Optional<ArtifactNotice> found = notices.stream().filter(matches).findFirst();
        assertThat(found)
                .withFailMessage("Expected a %s notice for message_uid=%s reason=%s, got: %s",
                        code, messageUid, expectedReason, notices)
                .isPresent();
    }
}
