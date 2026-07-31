/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.validator;

import ru.beeline.staging.dto.notice.ValidateResult;

public interface ArtifactValidator {

    String moduleCode();

    String description();

    ValidateResult validate(String artifactUid, String rawContent) throws Exception;
}
