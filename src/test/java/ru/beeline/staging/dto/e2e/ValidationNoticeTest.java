package ru.beeline.staging.dto.e2e;

import org.junit.jupiter.api.Test;
import ru.beeline.staging.e2e.Finding;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationNoticeTest {

    private static final String SOURCE = String.join("\n",
            "@startuml",
            "participant CRM as crm",
            "participant BILLING as billing",
            "crm -> billing: GET /a",
            "crm -> billing: GET /b",
            "@enduml");

    @Test
    void appendsTheOffendingSourceLinesToTheMessageWithoutChangingLineFields() {
        Finding finding = Finding.warning("e2e.validation.call.no_rest_endpoint", "No matching REST endpoint GET /a",
                4, 4, "crm->billing");

        ValidationNotice notice = ValidationNotice.from(finding, SOURCE);

        assertThat(notice.message()).contains("No matching REST endpoint GET /a");
        assertThat(notice.message()).contains("crm -> billing: GET /a");
        assertThat(notice.lineFrom()).isEqualTo(4);
        assertThat(notice.lineTo()).isEqualTo(4);
    }

    @Test
    void concatenatesAllLinesInTheRangeWhenLineFromAndLineToDiffer() {
        Finding finding = Finding.warning("e2e.validation.some.range", "some message", 4, 5, null);

        ValidationNotice notice = ValidationNotice.from(finding, SOURCE);

        assertThat(notice.message()).contains("crm -> billing: GET /a");
        assertThat(notice.message()).contains("crm -> billing: GET /b");
    }

    @Test
    void leavesTheMessageUnchangedWhenThereIsNoLineReference() {
        Finding finding = Finding.info("e2e.validation.messages.empty", "no messages", null, null, null);

        ValidationNotice notice = ValidationNotice.from(finding, SOURCE);

        assertThat(notice.message()).isEqualTo("no messages");
    }
}
