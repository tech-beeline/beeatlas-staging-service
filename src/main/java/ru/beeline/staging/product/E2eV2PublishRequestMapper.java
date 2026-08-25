/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import ru.beeline.staging.product.dto.e2e.E2eInfoDto;
import ru.beeline.staging.product.dto.e2e.E2eOperationSlaDto;
import ru.beeline.staging.product.dto.e2e.E2eProductDto;
import ru.beeline.staging.product.dto.e2e.E2eV2InterfaceDto;
import ru.beeline.staging.product.dto.e2e.E2eV2OperationDto;
import ru.beeline.staging.product.dto.e2e.E2eV2OperationRelationDto;
import ru.beeline.staging.product.dto.e2e.E2eV2PublishRequest;

import java.util.List;
import java.util.Map;

/**
 * Maps the staging canonical model (snake_case, per ActualE2eScenarioRepository) onto the
 * fdm-products POST /api/v2/e2e contract (camelCase, no containers layer). Interfaces link directly
 * to their product; since v2 carries no separate containers[] array, the source container is folded
 * into the published interface code as {@code "{interfaceCode}.{containerCode}"} so the origin isn't
 * lost.
 */
@Component
public class E2eV2PublishRequestMapper {

    public E2eV2PublishRequest map(JsonNode root) {
        E2eV2PublishRequest request = new E2eV2PublishRequest();
        request.setE2e(mapInfo(root.path("e2e")));
        request.setProducts(mapList(root.path("products"), this::mapProduct));
        // operations[].interface_code (from ActualE2eScenarioRepository) is the raw, uncompounded
        // interface code — index interfaces[] by that same raw code so each operation's
        // parentInterfaceCode can be resolved to the compound code actually published as
        // interfaces[].code. Without this, fdm-products rejects the payload: operations reference a
        // parentInterfaceCode that doesn't match any interfaces[].code.
        Map<String, String> compoundCodeByRawCode = indexCompoundInterfaceCodes(root.path("interfaces"));
        request.setInterfaces(mapList(root.path("interfaces"), this::mapInterface));
        request.setOperations(mapList(root.path("operations"), node -> mapOperation(node, compoundCodeByRawCode)));
        request.setOperationsRelations(mapList(root.path("operation_relations"), this::mapOperationRelation));
        return request;
    }

    private Map<String, String> indexCompoundInterfaceCodes(JsonNode interfacesNode) {
        Map<String, String> index = new java.util.HashMap<>();
        if (interfacesNode == null || !interfacesNode.isArray()) {
            return index;
        }
        for (JsonNode node : interfacesNode) {
            String rawCode = text(node, "code");
            if (rawCode == null) {
                continue;
            }
            index.put(rawCode, compoundInterfaceCode(rawCode, text(node, "parent_container_code")));
        }
        return index;
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
        dto.setProductVersionId(longVal(node, "product_version_id"));
        dto.setCmdb(text(node, "code"));
        dto.setName(text(node, "name"));
        return dto;
    }

    private E2eV2InterfaceDto mapInterface(JsonNode node) {
        E2eV2InterfaceDto dto = new E2eV2InterfaceDto();
        dto.setCode(compoundInterfaceCode(text(node, "code"), text(node, "parent_container_code")));
        dto.setName(text(node, "name"));
        dto.setParentProductCmdb(text(node, "parent_product_cmdb"));
        dto.setProtocol(text(node, "protocol"));
        // specLink/version: not available from staging — always null, same as v1.
        return dto;
    }

    /** {@code "{interfaceCode}.{containerCode}"} — containers[] isn't part of the v2 contract. */
    private String compoundInterfaceCode(String interfaceCode, String containerCode) {
        if (containerCode == null || containerCode.isBlank()) {
            return interfaceCode;
        }
        return interfaceCode + "." + containerCode;
    }

    private E2eV2OperationDto mapOperation(JsonNode node, Map<String, String> compoundCodeByRawCode) {
        E2eV2OperationDto dto = new E2eV2OperationDto();
        dto.setUid(text(node, "uid"));
        dto.setName(text(node, "name"));
        // type is computed at transform time (name/type split + SOAP fallback, transform-spec §4.5 v4)
        // and persisted on operation_versions.type — read it as-is rather than re-deriving it here.
        dto.setType(text(node, "type"));
        String rawInterfaceCode = text(node, "interface_code");
        dto.setParentInterfaceCode(compoundCodeByRawCode.getOrDefault(rawInterfaceCode, rawInterfaceCode));
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

    private E2eV2OperationRelationDto mapOperationRelation(JsonNode node) {
        E2eV2OperationRelationDto dto = new E2eV2OperationRelationDto();
        dto.setOperationId(text(node, "operation_uid"));
        dto.setRelatedOperationId(text(node, "related_operation_uid"));
        dto.setOrder(node.hasNonNull("call_order") ? node.get("call_order").asInt() : null);
        dto.setStereoType(text(node, "stereotype"));
        return dto;
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

    private Long longVal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
