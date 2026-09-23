/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.pipeline.transformer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.dto.notice.TransformResult;
import ru.beeline.staging.pipeline.StageContext;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlantUmlE2ETransformer implements ArtifactTransformer {

    public static final String MODULE_CODE = "e2e-plantuml-transformer";

    private final PlantUmlE2eDecomposer decomposer;

    @Override
    public String moduleCode() { return MODULE_CODE; }

    @Override
    public String description() { return "Maps the PlantUML e2e diagram onto the canonical E2ESequenceSnapshot"; }

    @Override
    public TransformResult transform(String artifactUid, String rawContent, StageContext context) {
        PlantUmlE2eDecomposer.Result result = decomposer.decompose(rawContent, artifactUid,
                context.payloadText("name"), context.payloadText("biStepCode"));

        E2ESequenceSnapshot snapshot = result.snapshot();
        log.info("stage=transformer, module={}, uid={}, products={}, containers={}, interfaces={}, operations={}, relations={}",
                MODULE_CODE, artifactUid, snapshot.getProducts().size(), snapshot.getContainers().size(),
                snapshot.getInterfaces().size(), snapshot.getOperations().size(),
                snapshot.getOperationRelations().size());
        return TransformResult.of(snapshot, result.notices(), result.pauseContext());
    }
}
