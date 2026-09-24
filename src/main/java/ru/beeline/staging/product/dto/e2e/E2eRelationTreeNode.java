/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.e2e;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class E2eRelationTreeNode {
    private Integer order;
    private Integer relatedOperationId;
    private String stereotype;
    private String entityTypeRelatedOperation;
    private List<E2eRelationTreeNode> operationsRelations = new ArrayList<>();
}
