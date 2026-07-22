package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import ru.beeline.staging.product.dto.e2e.E2eContainerDto;
import ru.beeline.staging.product.dto.e2e.E2eInfoDto;
import ru.beeline.staging.product.dto.e2e.E2eInterfaceDto;
import ru.beeline.staging.product.dto.e2e.E2eOperationDto;
import ru.beeline.staging.product.dto.e2e.E2eOperationRelationDto;
import ru.beeline.staging.product.dto.e2e.E2eOperationSlaDto;
import ru.beeline.staging.product.dto.e2e.E2ePublishRequest;
import ru.beeline.staging.product.dto.e2e.E2eProductDto;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the staging canonical model (snake_case, per get-actual-e2e-scenario.sql) onto the
 * fdm-products POST /api/v1/e2e contract (camelCase). See ea-e2e-sequence-save-spec.md §4 and §6.
 */
@Component
public class E2ePublishRequestMapper {

    private static final Pattern HTTP_METHOD_PREFIX = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE)\\s");

    public E2ePublishRequest map(JsonNode root) {
        E2ePublishRequest request = new E2ePublishRequest();
        request.setE2e(mapInfo(root.path("e2e")));
        request.setProducts(mapList(root.path("products"), this::mapProduct));
        request.setContainers(mapList(root.path("containers"), this::mapContainer));
        request.setInterfaces(mapList(root.path("interfaces"), this::mapInterface));
        request.setOperations(mapList(root.path("operations"), this::mapOperation));
        request.setOperationsRelations(mapList(root.path("operation_relations"), this::mapOperationRelation));
        return request;
    }

    private E2eInfoDto mapInfo(JsonNode node) {
        E2eInfoDto dto = new E2eInfoDto();
        dto.setUid(text(node, "uid"));
        dto.setName(text(node, "name"));
        dto.setDescription(text(node, "description"));
        dto.setBiStepCode(text(node, "bi_step_code"));
        return dto;
    }

    private E2eProductDto mapProduct(JsonNode node) {
        E2eProductDto dto = new E2eProductDto();
        dto.setCmdb(text(node, "code"));
        dto.setName(text(node, "name"));
        return dto;
    }

    private E2eContainerDto mapContainer(JsonNode node) {
        E2eContainerDto dto = new E2eContainerDto();
        dto.setCode(text(node, "code"));
        dto.setName(text(node, "name"));
        dto.setParentProductCmdb(text(node, "parent_product_cmdb"));
        return dto;
    }

    private E2eInterfaceDto mapInterface(JsonNode node) {
        E2eInterfaceDto dto = new E2eInterfaceDto();
        dto.setCode(text(node, "code"));
        dto.setName(text(node, "name"));
        dto.setParentContainerCode(text(node, "parent_container_code"));
        dto.setProtocol(text(node, "protocol"));
        // specLink/version: not available from staging — always null per save-spec §4.4.
        return dto;
    }

    private E2eOperationDto mapOperation(JsonNode node) {
        E2eOperationDto dto = new E2eOperationDto();
        String name = text(node, "name");
        dto.setUid(text(node, "uid"));
        dto.setName(name);
        dto.setType(extractHttpMethod(name));
        dto.setParentInterfaceCode(text(node, "interface_code"));
        dto.setSla(mapSla(node.path("sla")));
        return dto;
    }

    private E2eOperationSlaDto mapSla(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        E2eOperationSlaDto sla = new E2eOperationSlaDto();
        sla.setRps(number(node, "rps"));
        sla.setLatency(number(node, "latency"));
        sla.setErrorRate(number(node, "error_rate"));
        return sla;
    }

    private E2eOperationRelationDto mapOperationRelation(JsonNode node) {
        E2eOperationRelationDto dto = new E2eOperationRelationDto();
        dto.setOperationId(text(node, "operation_uid"));
        dto.setRelatedOperationId(text(node, "related_operation_uid"));
        dto.setOrder(node.hasNonNull("call_order") ? node.get("call_order").asInt() : null);
        dto.setStereoType(text(node, "stereotype"));
        return dto;
    }

    private String extractHttpMethod(String operationName) {
        if (operationName == null) {
            return null;
        }
        Matcher matcher = HTTP_METHOD_PREFIX.matcher(operationName);
        return matcher.find() ? matcher.group(1) : null;
    }

    private <T> List<T> mapList(JsonNode arrayNode, java.util.function.Function<JsonNode, T> mapper) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(arrayNode.spliterator(), false)
                .map(mapper)
                .toList();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }
}
