/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product.dto.cx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BiStepContext {

    private BiStepRef biStep;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BiStepRef {
        private Integer id;
        private String uid;
        private String name;
    }
}
