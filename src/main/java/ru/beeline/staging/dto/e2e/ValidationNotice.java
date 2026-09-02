/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.dto.e2e;

import ru.beeline.staging.e2e.Finding;

public record ValidationNotice(
        String code,
        String level,
        String message,
        Integer lineFrom,
        Integer lineTo,
        String elementRef
) {

    public static ValidationNotice from(Finding finding) {
        return new ValidationNotice(
                finding.code(),
                finding.level().name().toLowerCase(),
                finding.message(),
                finding.lineFrom(),
                finding.lineTo(),
                finding.elementRef());
    }
}
