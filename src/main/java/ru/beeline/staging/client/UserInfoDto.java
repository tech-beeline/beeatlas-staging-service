/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserInfoDto {

    private Integer id;
    private List<String> roles;
}
