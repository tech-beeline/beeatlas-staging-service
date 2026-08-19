# рефакторинг staging-service

## Контекст

Staging-service — ETL сервис для выгрузки архитектурных артефактов
Ключевые требования
- Обеспечение наблюдаемости прохождения данных
- Обеспечение качества данных
- Надёжность выгрузок
- Наблюдаемость происхождения данных
- Возможность найти причину, почему данные не попали в итоговый результат

- **Исходный код**
  Java 17 + Spring Boot 3.1.1 + Camunda 7.20 (external tasks, BPMN). Один BPMN-процесс на конфигурацию×тик: `Pre-Adapter → Multi-Instance(Adapter → Validator → Transformer → Saver)`. Два конвейера: `e2e-sequence` (Sparx EA) и `structurizr-sequence` (Structurizr).

- **Документация**
  Архитектура и lineage — [`README.md`](README.md:1). Отчёт аудита — `.local/audit-report.md` (43 проблемы, roadmap 3 фаз).
  Анализ boundary error в BPMN — [`.local/analysis-boundary-error.md`](.local/analysis-boundary-error.md) (P3-2).
  План исправления P3-2 — [`.local/plan-p3-2-boundary-error.md`](.local/plan-p3-2-boundary-error.md).

### Зависимости

**Исходящие**
- Sparx EA (datasource, e2e-sequence)
- Structurizr API (`{structurizrApiUrl}/json`, structurizr-sequence)
- fdm-products (мнемоники продуктов для pre-adapter)
- product-service (публикация E2E-сценариев)
- dashboard (base-url в `application.yml`)
- MinIO/S3 (в docker-compose; в коде использование не найдено — статус уточнить)

**Входящие**
- Потребители выгруженных данных (открытый вопрос — список не определён)

## 🎯 Глобальная цель

Развитие staging-service через рефакторинг: обеспечение наблюдаемости прохождения и происхождения данных, качества и надёжности выгрузок. Рефакторинг — средство достижения этих целей, а не самоцель. Аудит (`.local/audit-report.md`) — часть объёма работ.

**Вне миссии (открытые вопросы)**
- Новые источники/сущности (cj, bi_step, e2e_scenario — схема в БД готова, интеграция не начата)
- Интеграция с cx-backend

## 🛠️ Технологический стек и ограничения
* Язык/Фреймворк: Java 17 + Spring Boot 3.1.1 + Camunda 7.20 (external tasks, BPMN)
* База данных: PostgreSQL 15, Flyway (V0001–V0008)
* Мониторинг: Micrometer + Prometheus (зависимость есть, метрики в коде отсутствуют)
* API-документация: springdoc (Swagger UI)
* Ограничения: см. раздел «Ограничения»

## 🧠 Принятые решения (ADR — Architecture Decision Records)

> ADR-файлы хранятся в `/docs/adr`. Ниже — ссылки и краткое описание существующих решений, зафиксированных в коде.

| ADR | Статус | Тема | Где зафиксировано |
|-----|--------|------|-------------------|
| ADR-005 | Существующий | `raw_data_contexts` — минимальная структура контекста навигации в сырых данных | [`V0001__create_core_tables.sql`](src/main/resources/db/migration/V0001__create_core_tables.sql:223), [`RawDataContextService.java`](src/main/java/ru/beeline/staging/service/RawDataContextService.java:17) |
| ADR-011 | Существующий | `json_data` — JSONB-колонка в `*_versions` для неосновных атрибутов | [`V0008__add_json_data_to_version_tables.sql`](src/main/resources/db/migration/V0008__add_json_data_to_version_tables.sql:2), [`JsonDataValidator.java`](src/main/java/ru/beeline/staging/pipeline/saver/JsonDataValidator.java:13) |

Новые ADR будут создаваться в `/docs/adr` в ходе миссии.

## ❌ Отклонённые идеи (Кладбище концепций)

- **Публикация в MinIO/S3** — зависимость в docker-compose, но в коде использование не найдено. Решение отложено до определения потребностей.
- **Новые источники/сущности** (cj, bi_step, e2e_scenario) — таблицы в БД созданы (V0002–V0008), но Saver'ы отсутствуют. Решение по интеграции отложено.
- **Интеграция с cx-backend** — зафиксирована как вне миссии (V0008, строка 319).

## 🛠️ Изменяемые артефакты (Scope)

### Входит в миссию

#### 1. Ядро (надёжность)
- BPMN-процесс [`artifact-pipeline-process.bpmn`](src/main/resources/bpmn/artifact-pipeline-process.bpmn)
- Воркеры: [`AbstractWorker`](src/main/java/ru/beeline/staging/worker/AbstractWorker.java), [`ValidatorWorker`](src/main/java/ru/beeline/staging/worker/ValidatorWorker.java), [`TransformerWorker`](src/main/java/ru/beeline/staging/worker/TransformerWorker.java), [`SaverWorker`](src/main/java/ru/beeline/staging/worker/SaverWorker.java), [`PreAdapterWorker`](src/main/java/ru/beeline/staging/worker/PreAdapterWorker.java)
- Восстановление: [`StuckProcessMonitor`](src/main/java/ru/beeline/staging/service/StuckProcessMonitor.java), [`PipelineTickScheduler`](src/main/java/ru/beeline/staging/worker/PipelineTickScheduler.java)

#### 2. Наблюдаемость
- Метрики: Micrometer/Prometheus (P1-1)
- Корреляция: MDC/TraceId (P1-5)
- Тайминги стадий (P1-4)
- Статусы пайплайна (P1-3)
- Причины пропусков/отсутствий (P5-1, P5-2, P5-3)

#### 3. Качество данных
- Дедупликация версий в MatchService (P2-3)
- Валидация `canonical_snapshot_json` (P2-4, P2-5)
- Зафиксирование потери операций/интерфейсов (P2-2)
- Зафиксирование агрегации notices (P2-1)

#### 4. Lineage (происхождение данных)
- Entity-поля в notices (P4-3)
- Lineage API (P4-2)
- Lineage SQL view (P4-4)
- Обязательные FK `match_notice_id`/`raw_data_context_id` (P4-1)

#### 5. Безопасность и конфигурация
- Auth на `/admin/**`, `/api/**` (O1-2)
- Secrets (O1-1, O1-3, O1-4)
- Spring profiles (O4-2)
- Обязательные properties (O4-1)
- Тесты (O4-5)

### Не входит (открытые вопросы)
- Новые источники/сущности (cj, bi_step, e2e_scenario)
- Интеграция с cx-backend
- Сжатие в MinIO/S3

## 📝 Текущий To-Do список (Атомарные шаги)

### Фаза 1: Критичные (High)
- [x] P3-1 · Добавить авто-ретраи в воркеры (retries=0 → retries≥3 для временных ошибок)
- [ ] P3-2 · Исправить boundary error в BPMN (проглатывает ошибку) — [план](.local/plan-p3-2-boundary-error.md), [анализ](.local/analysis-boundary-error.md)
  - [ ] P3-2.1 · Развести успех/ошибку в BPMN — boundary error на subprocess → failed end event
  - [ ] P3-2.2 · Заменить handleBpmnError → handleFailure в AbstractWorker (incident + StuckProcessMonitor)
  - [ ] P3-2.3 · Убрать дублирование failStage/handleFailure — единый порядок в AbstractWorker
  - [ ] P3-2.4 · Восстановить ручной retry для failed артефактов с завершённым процессом
  - [ ] P3-2.5 · Unit/Integration тесты: permanent error → FAILED в Camunda + failed в БД + StuckProcessMonitor лечит
  - [ ] P3-2.1 · Развести успех/ошибку в BPMN — boundary error на subprocess → failed end event (BPMN-модель)
  - [ ] P3-2.2 · Заменить handleBpmnError → handleFailure в AbstractWorker (чтобы Camunda создавал incident + срабатывал StuckProcessMonitor)
  - [ ] P3-2.3 · Убрать дублирование failStage/handleFailure — единый порядок: handleFailure → failStage в AbstractWorker
  - [ ] P3-2.4 · Восстановить ручной retry для failed артефактов с завершённым процессом (AdminController + PipelineRunService)
  - [ ] P3-2.5 · Unit/Integration тесты: permanent error → FAILED в Camunda + failed в БД + StuckProcessMonitor лечит incident
- [ ] P1-2/P5-4 · Зафиксировать тихую потерю при 0 найденных артефактов (notice `extract.zero_artifacts`)
- [ ] P5-3 · ValidatorWorker: генерировать notice при отсутствии модуля валидации
- [ ] O1-1 · Вынести пароль Camunda из `application.yml` в external secrets
- [ ] O1-2 · Добавить auth на `/admin/**` и `/api/**`
- [ ] P1-1 · Внедрить базовый набор Micrometer-метрик
- [ ] P2-2 · Зафиксировать потерю операций/интерфейсов в ScenarioDecomposer (notice `transform.missing_*`)
- [ ] O2-1 · CI-проверка JPA↔SQL (схема vs миграции)
- [ ] O4-1 · Дефолт + ConditionalOnProperty для `integration.product-server-url`

### Фаза 2: Стабилизация (Medium) — после Фазы 1
- [ ] P3-3 · Distributed lock в PipelineTickScheduler.tick()
- [ ] P3-5 · Атомарный upsert в StructurizrSequenceAdapter
- [ ] P3-6 · StuckProcessMonitor: анализ причины перед resetRetries
- [ ] P3-7 · Saga-pattern: save snapshot → pending_publish → publish
- [ ] P3-4 · Включить gzip обратно
- [ ] P1-3 · Статусы pipeline_runs: единый источник или view
- [ ] P1-5 · MDC с traceId + artifactUid
- [ ] P2-1 · Зафиксировать потерю details при агрегации notices >200k
- [ ] P2-3 · Дедупликация версий в *MatchService
- [ ] P2-4 · CHECK-constraint на canonicalSnapshotJson
- [ ] P2-5 · Pre-save валидация snapshot (orphan references)
- [ ] P4-1 · make `match_notice_id`/`raw_data_context_id` NOT NULL
- [ ] P4-2 · Lineage API `GET /api/lineage/{entityType}/{entityUid}`
- [ ] P4-3 · Entity-поля в `artifact_notices`
- [ ] P5-1 · Stacktrace в логах воркеров
- [ ] P5-2 · Notice для skipped стадий
- [ ] O2-2 · Индексы на FK-колонки
- [ ] O3-1 · Параметризовать batch-size
- [ ] O3-2 · Streaming для raw_content
- [ ] O4-2 · Spring profiles (dev/prod)
- [ ] O4-5 · Unit/Integration тесты

### Фаза 3: Оптимизация (Low) — по плану
- [ ] P1-4 · Тайминги стадий (started_at/finished_at в pipeline_stage_logs)
- [ ] P4-4 · SQL view `staging.lineage_trace`
- [ ] O1-4 · Маскирование паролей в логах
- [ ] O2-3 · `@JdbcTypeCode(SqlTypes.JSON)` для jsonb
- [ ] O2-4 · `operation.name` — nullable или дефолт
- [ ] O3-3 · Refactor ScenarioDecomposer (~1270 строк)
- [ ] O4-3 · Настройка logResumedOnStartup
- [ ] O4-4 · CHANGELOG / git tags

## 📍 Текущий статус и точка остановки
* **Остановились на:** P3-1 реализован — авто-ретраи в воркеры (transient/permanent классификация, Camunda-ретраи, идемпотентность createRun, улучшен StuckProcessMonitor)
* **Следующий шаг для новой сессии:** P3-2 (исправить boundary error в BPMN) или другая задача из Фазы 1

## Ограничения
- ЗАПРЕЩЕНО менять исходный код без разрешения пользователя
