package ru.beeline.staging.dto.notice;

import java.util.List;

public record ValidateResult(List<ArtifactNotice> notices) {

    public static ValidateResult empty() {
        return new ValidateResult(List.of());
    }

    public static ValidateResult of(List<ArtifactNotice> notices) {
        return new ValidateResult(notices);
    }
}
