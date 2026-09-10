/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.validator;

import ru.beeline.staging.dto.notice.ValidateResult;
import ru.beeline.staging.pipeline.StageContext;

public interface ArtifactValidator {

    String moduleCode();

    String description();

    ValidateResult validate(String artifactUid, String rawContent, StageContext context) throws Exception;
}
