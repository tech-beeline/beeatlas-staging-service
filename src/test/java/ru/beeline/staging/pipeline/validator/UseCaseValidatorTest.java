package ru.beeline.staging.pipeline.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.e2e.PlantUmlDiagramParser;
import ru.beeline.staging.pipeline.StageContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UseCaseValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UseCaseValidator validator = new UseCaseValidator(new PlantUmlDiagramParser(), objectMapper);

    @Test
    @DisplayName("Корректная Sequence-диаграмма с именем проходит без замечаний")
    void acceptsValidDiagram() {
        List<ArtifactNotice> notices = validate("@startuml\nA -> B: POST /orders\n@enduml", payload("Заказ"));

        assertThat(notices).isEmpty();
    }

    @Test
    @DisplayName("Не Sequence-диаграмма — error usecase.validation.diagram.not_sequence")
    void rejectsNonSequenceDiagram() {
        List<ArtifactNotice> notices = validate("@startuml\nclass Order\n@enduml", payload("Заказ"));

        assertThat(notices).extracting(ArtifactNotice::code).containsExactly("usecase.validation.diagram.not_sequence");
        assertThat(notices.get(0).level()).isEqualTo("error");
    }

    @Test
    @DisplayName("Пустой biStepCode — error, пустое имя — warning")
    void checksPayloadMetadata() {
        ObjectNode payload = payload(" ");
        payload.put("biStepCode", "");

        List<ArtifactNotice> notices = validate("@startuml\nA -> B: POST /orders\n@enduml", payload);

        assertThat(notices).extracting(ArtifactNotice::code, ArtifactNotice::level).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("usecase.validation.bi_step.invalid", "error"),
                org.assertj.core.groups.Tuple.tuple("usecase.validation.metadata.empty_name", "warning"));
    }

    private List<ArtifactNotice> validate(String plantUml, ObjectNode payload) {
        return validator.validate("UC-001", plantUml,
                new StageContext(77L, "usecase", "beeatlas-ui", "main", payload)).notices();
    }

    private ObjectNode payload(String name) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", name);
        payload.put("projectCode", "PRJ-1");
        return payload;
    }
}
