package ru.beeline.staging.dto.scan;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Детали одного скан-запуска (parent run) с агрегированной статистикой дочерних запусков.
 * 
 * @param id идентификатор скан-запуска
 * @param code код конфигурации загрузки
 * @param artifactType тип артефакта
 * @param status статус скан-запуска
 * @param sourceName наименование источника данных
 * @param startedAt время начала
 * @param completedAt время завершения (null, если ещё выполняется)
 * @param childStats агрегация дочерних запусков по статусам
 */
public record ScanRunDetails(
        Long id,
        String code,
        String artifactType,
        String status,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ChildStat> childStats
) {}
