package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.product.dto.e2e.E2eV2InterfaceDto;
import ru.beeline.staging.product.dto.e2e.E2eV2OperationDto;
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;

import static org.assertj.core.api.Assertions.assertThat;

class E2eV2PublishRequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final E2eV2PublishRequestMapper mapper = new E2eV2PublishRequestMapper();

    @Test
    @DisplayName("Сопоставленная арх-операция уезжает в publish как connectionOperationId")
    void carriesTheMatchedArchOperationIdIntoThePublishRequest() throws Exception {
        E2eV2PublishRequest request = mapper.map(objectMapper.readTree("""
                {
                  "e2e": {"uid": "E2E-001", "name": "Оплата"},
                  "interfaces": [{"code": "bnpl-api", "parent_container_code": "bnpl-gateway"}],
                  "operations": [
                    {"uid": "op-1", "name": "/command/createApplication", "type": "POST",
                     "interface_code": "bnpl-api", "connection_operation_id": 39329},
                    {"uid": "op-2", "name": "GET описание связи", "type": "UNKNOWN",
                     "interface_code": "bnpl-api"}
                  ]
                }
                """));

        assertThat(request.getOperations()).hasSize(2);
        assertThat(request.getOperations().get(0).getConnectionOperationId()).isEqualTo(39329);
        assertThat(request.getOperations().get(1).getConnectionOperationId()).isNull();
    }

    @Test
    @DisplayName("Операции с одинаковым кодом интерфейса у разных систем уходят каждая в свой интерфейс")
    void bindsOperationsToTheirOwnInterfaceVersion() throws Exception {
        E2eV2PublishRequest request = mapper.map(objectMapper.readTree("""
                {
                  "e2e": {"uid": "E2E-001", "name": "Дубли"},
                  "interfaces": [
                    {"interface_version_id": 11, "code": "033d51b4", "parent_container_code": "fdmshowcaseapp"},
                    {"interface_version_id": 12, "code": "033d51b4", "parent_container_code": "umcs"}
                  ],
                  "operations": [
                    {"uid": "op-1", "name": "/api/v1/sequence", "type": "POST",
                     "interface_version_id": 11, "interface_code": "033d51b4"},
                    {"uid": "op-2", "name": "/api/v1/sequence", "type": "POST",
                     "interface_version_id": 12, "interface_code": "033d51b4"}
                  ]
                }
                """));

        assertThat(request.getInterfaces()).extracting(E2eV2InterfaceDto::getCode)
                .containsExactly("033d51b4.fdmshowcaseapp", "033d51b4.umcs");
        assertThat(request.getOperations()).extracting(E2eV2OperationDto::getParentInterfaceCode)
                .containsExactly("033d51b4.fdmshowcaseapp", "033d51b4.umcs");
    }
}
