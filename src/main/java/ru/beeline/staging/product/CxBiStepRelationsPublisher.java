/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.client.CxBackendClient;
import ru.beeline.staging.client.E2eProductsClient;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.product.dto.ProductAliasSummary;
import ru.beeline.staging.product.dto.cx.CxBiStepRelation;
import ru.beeline.staging.product.dto.e2e.E2eGetResponse;
import ru.beeline.staging.product.dto.e2e.E2eRelationTreeNode;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CxBiStepRelationsPublisher {

    private static final String NOTICE_CODE = "publish.cx.failed";

    private final CxBackendClient cxBackendClient;
    private final E2eProductsClient e2eProductsClient;
    private final ProductServiceClient productServiceClient;
    private final ArtifactNoticeService artifactNoticeService;
    private final ObjectMapper objectMapper;

    public void publish(JsonNode root, String e2eCode, String artifactUid, Long rawDataRefId, Long pipelineRunId) {
        if (!cxBackendClient.isConfigured()) {
            log.debug("cx-backend base url is not configured — skipping bi step relations sync for uid={}", artifactUid);
            return;
        }
        String biStepCode = text(root.path("e2e"), "bi_step_code");
        if (biStepCode == null || biStepCode.isBlank()) {
            log.debug("Scenario uid={} has no bi_step_code — nothing to sync to cx-backend", artifactUid);
            return;
        }
        try {
            List<JsonNode> rootCalls = rootCalls(root.path("operation_relations"));
            if (rootCalls.isEmpty()) {
                log.info("Scenario uid={} has no root calls — skipping cx-backend sync for biStepCode={}",
                        artifactUid, biStepCode);
                return;
            }
            Integer biStepId = cxBackendClient.findBiStepIdByCode(biStepCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "cx-backend has no bi_step with code=" + biStepCode));

            cxBackendClient.replaceBiStepRelations(biStepId, buildRelations(root, rootCalls, e2eCode));
        } catch (RuntimeException e) {
            recordFailure(artifactUid, biStepCode, rawDataRefId, pipelineRunId, e);
        }
    }

    private List<JsonNode> rootCalls(JsonNode relationsNode) {
        List<JsonNode> roots = new ArrayList<>();
        if (relationsNode == null || !relationsNode.isArray()) {
            return roots;
        }
        for (JsonNode relation : relationsNode) {
            if (!relation.hasNonNull("operation_uid")) {
                roots.add(relation);
            }
        }
        return roots;
    }

    private List<CxBiStepRelation> buildRelations(JsonNode root, List<JsonNode> rootCalls, String e2eCode) {
        Map<Integer, Integer> operationIdByOrder = readBackRootOperationIds(e2eCode);
        Map<String, String> productCmdbByOperationUid = indexProductCmdbByOperationUid(root);
        Map<String, Integer> productIdByCmdb = resolveProductIds(productCmdbByOperationUid.values());

        List<CxBiStepRelation> relations = new ArrayList<>(rootCalls.size());
        for (JsonNode call : rootCalls) {
            Integer callOrder = call.hasNonNull("call_order") ? call.get("call_order").asInt() : null;
            String productCmdb = productCmdbByOperationUid.get(text(call, "related_operation_uid"));

            CxBiStepRelation relation = new CxBiStepRelation();
            relation.setDescription(text(call, "stereotype"));
            relation.setOperationId(callOrder != null ? operationIdByOrder.get(callOrder) : null);
            relation.setProductId(productIdByCmdb.get(productCmdb));
            relations.add(relation);
        }
        return relations;
    }

    private Map<Integer, Integer> readBackRootOperationIds(String e2eCode) {
        if (e2eCode == null || e2eCode.isBlank()) {
            return Map.of();
        }
        Optional<E2eGetResponse> response = e2eProductsClient.getE2eByCode(e2eCode);
        if (response.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> byOrder = new HashMap<>();
        for (E2eRelationTreeNode node : response.get().getOperationsRelations()) {
            if (node.getOrder() != null && node.getRelatedOperationId() != null) {
                byOrder.put(node.getOrder(), node.getRelatedOperationId());
            }
        }
        return byOrder;
    }

    private Map<String, String> indexProductCmdbByOperationUid(JsonNode root) {
        Map<String, String> productCmdbByInterfaceCode = new HashMap<>();
        for (JsonNode iface : arrayOf(root, "interfaces")) {
            String code = text(iface, "code");
            if (code != null) {
                productCmdbByInterfaceCode.put(code, text(iface, "parent_product_cmdb"));
            }
        }
        Map<String, String> byOperationUid = new LinkedHashMap<>();
        for (JsonNode operation : arrayOf(root, "operations")) {
            String uid = text(operation, "uid");
            String cmdb = productCmdbByInterfaceCode.get(text(operation, "interface_code"));
            if (uid != null && cmdb != null) {
                byOperationUid.put(uid, cmdb);
            }
        }
        return byOperationUid;
    }

    private Map<String, Integer> resolveProductIds(Collection<String> cmdbCodes) {
        List<String> aliases = cmdbCodes.stream().filter(Objects::nonNull).distinct().toList();
        if (aliases.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> byCmdb = new HashMap<>();
        for (ProductAliasSummary product : productServiceClient.getByAliases(aliases)) {
            if (product.getAlias() != null && product.getId() != null) {
                byCmdb.put(product.getAlias(), product.getId());
            }
        }
        return byCmdb;
    }

    private void recordFailure(String artifactUid, String biStepCode, Long rawDataRefId, Long pipelineRunId,
            Exception e) {
        log.warn("cx-backend bi step relations sync failed for uid={}, biStepCode={}, pipelineRunId={}: {}",
                artifactUid, biStepCode, pipelineRunId, e.getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uid", artifactUid);
        details.put("biStepCode", biStepCode);
        details.put("relationId", rawDataRefId);
        details.put("pipelineRunId", pipelineRunId);
        details.put("error", e.getMessage());
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception jsonEx) {
            detailsJson = "{}";
        }
        ArtifactNotice notice = new ArtifactNotice(null, null, NOTICE_CODE, "error", "publish",
                rawDataRefId, "e2e_scenario", artifactUid, null, NOTICE_CODE, detailsJson, null, null,
                artifactUid, null);
        artifactNoticeService.saveNoticeInNewTransaction(rawDataRefId, notice);
    }

    private Iterable<JsonNode> arrayOf(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isArray() ? node : List.of();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
