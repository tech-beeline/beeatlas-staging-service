package ru.beeline.staging.pipeline.preadapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.sparx.SparxE2ERepository;
import ru.beeline.staging.sparx.dto.E2EScenarioMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SparxE2EPreAdapter implements ArtifactPreAdapter {

    public static final String MODULE_CODE = "sparx-e2e-preadapter";

    private final SparxE2ERepository sparxE2ERepository;

    @Override
    public String moduleCode() {
        return MODULE_CODE;
    }

    @Override
    public String description() {
        return "Scans Sparx EA for e2e diagrams";
    }

    @Override
    public List<FoundArtifact> scan(Configuration config) {
        List<E2EScenarioMeta> scenarios = sparxE2ERepository.findAllScenarios();
        log.info("Sparx scan e2e-sequence: found {} scenarios for configId={}", scenarios.size(), config.getId());

        List<FoundArtifact> found = new ArrayList<>();
        for (E2EScenarioMeta scenario : scenarios) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("name", scenario.getName());
            metadata.put("version", scenario.getVersion());
            metadata.put("notes", scenario.getNotes());
            found.add(new FoundArtifact(scenario.getUid(), metadata));
        }
        return found;
    }
}
