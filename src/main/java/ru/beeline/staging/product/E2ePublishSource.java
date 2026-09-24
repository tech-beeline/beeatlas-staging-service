/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.product;

public enum E2ePublishSource {

    SPARX,
    PLANTUML;

    public static final String E2E_PLANTUML_ARTIFACT_TYPE = "e2e-plantuml";

    public static E2ePublishSource forArtifactType(String artifactType) {
        return E2E_PLANTUML_ARTIFACT_TYPE.equals(artifactType) ? PLANTUML : SPARX;
    }
}
