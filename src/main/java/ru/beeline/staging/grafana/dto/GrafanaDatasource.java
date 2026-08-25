package ru.beeline.staging.grafana.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GrafanaDatasource(String uid, String name, String type) {}
