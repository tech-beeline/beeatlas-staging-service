package ru.beeline.staging.dto.scan;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Детали одного скан-запуска (parent run) с агрегированной статистикой дочерних запусков.
 * 
 * @param id идентификатор скан-запуска
 * @param code код конфигурации загрузки
 * @param artifactType тип артефакта
 * @param status статус скан-запуска (raw: относится только к discovery/fan-out, не к детям)
 * @param displayStatus статус для отображения — учитывает, все ли дочерние pipelines дошли до
 *                      терминального статуса; "completed" означает, что скан всё ещё выполняется
 *                      только по смыслу discovery-стадии, а не что вся обработка артефактов завершена
 * @param sourceName наименование источника данных
 * @param startedAt время начала
 * @param completedAt время завершения (null, если ещё выполняется)
 * @param childStats агрегация дочерних запусков по статусам (live, через source_artifacts —
 *                   обнуляется, если более новый скан той же конфигурации переоткрыл те же артефакты)
 * @param childStatsSnapshot стабильная агрегация по списку id, зафиксированному в момент fan-out
 *                           скана — не обнуляется задним числом. Предпочтительное поле для фронта.
 */
public record ScanRunDetails(
        Long id,
        String code,
        String artifactType,
        String status,
        String displayStatus,
        String sourceName,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ChildStat> childStats,
        List<ChildStat> childStatsSnapshot
) {}
