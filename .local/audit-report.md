# Отчёт об аудите staging-service

> Дата: 2026-08-12
> Статус: Итоговый отчёт
> Роль: Опытный Java-разработчик

---

## 1. Контекст и методология

**staging-service** — конфигурируемый ETL-сервис для выгрузки архитектурных артефактов из Sparx EA и Structurizr. Архитектура: Spring Boot 3.1.1 + Camunda 7.20 (external tasks, BPMN) + PostgreSQL + Flyway + Hibernate (ddl-auto: none).

**Пайплайн**: `preAdapter → adapter → validator → transformer → saver` — один BPMN-процесс на конфигурацию × тик; multi-instance subprocess (sequential) по артефактам.

**Ключевые требования к сервису (зоны аудита):**
1. Обеспечение наблюдаемости прохождения данных
2. Обеспечение качества данных
3. Надёжность выгрузок
4. Наблюдаемость происхождения данных (lineage)
5. Возможность найти причину, почему данные не попали в итоговый результат

**Методология**: Статический анализ исходного кода (100+ файлов). Изменения кода не вносились.

---

## 2. Сводка проблем

| Критичность | Кол-во | ID |
|---|---|---|
| Высокая | 10 | P1-1, P1-2, P2-2, P3-1, P3-2, P5-3, O1-1, O1-2, O2-1, O4-1 |
| Средняя | 25 | P1-3, P1-5, P2-1, P2-3, P2-4, P2-5, P3-3, P3-4, P3-5, P3-6, P3-7, P4-1, P4-2, P4-3, P5-1, P5-2, O1-3, O2-2, O3-1, O3-2, O3-4, O4-2, O4-5, P5-4(дубль P1-2), O1-4(средняя) |
| Низкая | 8 | P1-4, P4-4, O2-3, O2-4, O3-3, O4-3, O4-4, O1-4(низкая часть) |

---

## 3. Проблемы по ключевым требованиям

### Р1. Наблюдаемость прохождения данных

#### P1-1 · Нет кастомных метрик (Высокая)
- **Локация**: весь проект (0 результатов поиска `MeterRegistry`/`@Timed`/`Counter`)
- **Влияние**: отсутствует количественная наблюдаемость (throughput, latency, error rate). Невозможно настроить алерты и дашборды.
- **Решение**: внедрить Micrometer-метрики:
  - `staging.pipeline.stage.duration` (Timer, tags: stage, config, status)
  - `staging.pipeline.artifacts.total` (Counter, tags: stage, config, direction=published/dropped)
  - `staging.pipeline.notices.total` (Counter, tags: category, level)
  - `staging.worker.poll.attempts` (Timer)
  - `staging.publish.errors.total` (Counter, tags: error_type)
  - `staging.camunda.external_task.retries` (Counter, tags: topic)

#### P1-2 · Тихая потеря данных при 0 найденных артефактов (Высокая)
- **Локация**: [`PreAdapterWorker.java`](src/main/java/ru/beeline/staging/worker/PreAdapterWorker.java:93)
- **Влияние**: когда `found` пуст, воркер вызывает `completeStage(...)` без признака — для потребителя/оператора невозможно отследить, что данные не выгружены, т.к. стадия завершена успешно.
- **Решение**: при `found.isEmpty()` — генерировать notice `extract.zero_artifacts` (warning) и фиксировать в `stageLog.output_data` явный признак; либо завершать стадию с кодом статуса `empty` + boundary error в BPMN.

#### P1-3 · Дублирование статусов pipeline_runs.status vs Camunda (Средняя)
- **Локация**: [`PipelineRunService.java`](src/main/java/ru/beeline/staging/service/PipelineRunService.java), [`PipelineRun.java`](src/main/java/ru/beeline/staging/domain/PipelineRun.java)
- **Влияние**: `pipeline_runs.status` (started/completed/failed) дублирует состояние Camunda-process-instance. Возможны рассинхронизации, т.к. обновления не атомарны.
- **Решение**: считать статус pipeline_runs производным от Camunda-инстанса (view/query), либо сделать Camunda единственным источником правды для статусов, а таблицу — только для метаданных.

#### P1-4 · Нет таймингов/длительностей стадий (Низкая-Средняя)
- **Локация**: [`PipelineStageLog.java`](src/main/java/ru/beeline/staging/domain/PipelineStageLog.java)
- **Влияние**: в `pipeline_stage_logs` нет отдельных полей `started_at`/`finished_at`/`duration_ms`. Возможна постфактум-оценка только по timestamp записи.
- **Решение**: добавить колонки `started_at`, `finished_at` в `pipeline_stage_logs` (миграция V0009); заполнять в `startStage`/`completeStage`.

#### P1-5 · Логи без корреляционных идентификаторов (Средняя)
- **Локация**: весь проект (отсутствует MDC/TraceId)
- **Влияние**: невозможно выделить цепочку логов отдельного артефакта или скана в общем потоке.
- **Решение**: внедрить MDC с `traceId` (UUID per scan) и `artifactUid`; логировать через `logger.info("[{}] Artifact {} stage={} ...", traceId, artifactUid, stage)`.

---

### Р2. Качество данных

#### P2-1 · Потеря деталей при агрегации notices >200k (Средняя)
- **Локация**: [`TransformerWorker.java:113`](src/main/java/ru/beeline/staging/worker/TransformerWorker.java:113)
- **Влияние**: `aggregateByCodeAndReason` сворачивает notices по code+reason, теряя индивидуальные details (конкретные uid, jsonPointer).
- **Решение**: хранить агрегированные notices с `sample_details` (первые N оригинальных details) + `occurrence_count`; либо хранить все notices без агрегации с пагинацией в API.

#### P2-2 · Операции/интерфейсы могут теряться в ScenarioDecomposer без error-notice (Высокая)
- **Локация**: [`ScenarioDecomposer.java`](src/main/java/ru/beeline/staging/pipeline/transformer/ScenarioDecomposer.java) (~1270 строк)
- **Влияние**: при отсутствии операции или интерфейса в Sparx EA (null из `operationsByUid`/`interfacesByUid`) соответствующий шаг может быть пропущен без генерации notice.
- **Решение**: в `registerOperationAndInterface` и `decomposeChildren` добавить явные проверки: если operation/interface не найден → генерировать notice `transform.missing_operation`/`transform.missing_interface` (error).

#### P2-3 · Нет дедупликации версий в MatchService (Средняя)
- **Локация**: все `*MatchService.java` (ProductMatchService, ContainerMatchService и др.)
- **Влияние**: паттерн find-or-create по `uid` + unconditional `versionRepository.save(newVersion)` → при повторных прогонах с одинаковыми данными создаются дубли `*_versions`.
- **Решение**: добавить уникальное ограничение `(uid, raw_data_context_id)` на `*_versions`; перед сохранением проверять существование версии с тем же context_id.

#### P2-4 · canonicalSnapshotJson TEXT без структуры/лимита (Средняя)
- **Локация**: [`RawDataRef.java`](src/main/java/ru/beeline/staging/domain/RawDataRef.java) — колонка `canonical_snapshot_json TEXT`
- **Влияние**: колонка без валидации CHECK/JSON-схемы и без ограничения размера. Риск накопления гигантских Blob'ов.
- **Решение**: добавить CHECK-constraint `json_data IS NULL OR jsonb_typeof(canonical_snapshot_json::jsonb) = 'object'`; установить предупреждение при размере > 1 МБ; рассмотреть хранение в отдельной таблице с versioning.

#### P2-5 · Нет валидации целостности снимка перед save (Средняя)
- **Локация**: [`E2eCanonicalSnapshotSaver.java`](src/main/java/ru/beeline/staging/pipeline/saver/E2eCanonicalSnapshotSaver.java)
- **Влияние**: `saveSnapshot` не проверяет, что все referenced сущности (product/container/TC/interface/operation) существуют в identity-таблицах перед сохранением связей.
- **Решение**: добавить pre-save валидатор: проверить, что все `uid` в snapshot присутствуют в identity-таблицах; при отсутствии — сгенерировать notice `save.orphan_reference`.

---

### Р3. Надёжность выгрузок

#### P3-1 · Основные стадии НЕ имеют авто-ретраев (Высокая)
- **Локация**: [`AbstractWorker.java:49`](src/main/java/ru/beeline/staging/worker/AbstractWorker.java:49)
- **Влияние**: `handleFailure(task.getId(), workerId(), e.getMessage(), e.toString(), 0, 0L)` — `retries=0`. При любой ошибке (сеть, DB, десериализация) задача помечается как failed без попыток повтора.
- **Решение**: установить `retries >= 3` для временных ошибок; для перманентных ошибок — `handleBpmnError` с явной классификацией (Transient vs Permanent).

#### P3-2 · Boundary error в BPMN «проглатывает» ошибку (Высокая)
- **Локация**: [`artifact-pipeline-process.bpmn`](src/main/resources/bpmn/artifact-pipeline-process.bpmn)
- **Влияние**: boundary error event на каждой стадии → SubEnd (конец subprocess) без propagation error на уровень process instance. Ошибка «проглочена» — процесс завершается успешно, хотя артефакт потерян.
- **Решение**: boundary error должен throw BPMN error на уровень процесса → process instance ends with ERROR状态; OR добавить compensate event для rollback.

#### P3-3 · Гонка в PipelineTickScheduler.tick() (Средняя)
- **Локация**: [`PipelineTickScheduler.java:28`](src/main/java/ru/beeline/staging/worker/PipelineTickScheduler.java:28)
- **Влияние**: `isAlreadyRunning` + `intervalElapsed` — между проверкой и `startScan` возможна гонка (2 инстанса сервиса или 2 потока).
- **Решение**: добавить distributed lock (pg_try_advisory_lock или Redis) на `configuration_id`; или уникальное ограничение `(configuration_id, is_current)` + ON CONFLICT DO NOTHING.

#### P3-4 · Gzip отключён (TEMP) (Средняя)
- **Локация**: [`ValidatorWorker.java:77`](src/main/java/ru/beeline/staging/worker/ValidatorWorker.java:77), [`TransformerWorker.java:83`](src/main/java/ru/beeline/staging/worker/TransformerWorker.java:83)
- **Влияние**: `raw_content` хранится без сжатия → в 5-10 раз больше места в БД.
- **Решение**: включить gzip обратно; добавить миграцию для сжатия существующих данных (offline-скрипт).

#### P3-5 · StructurizrSequenceAdapter — не атомарный upsert (Средняя)
- **Локация**: [`StructurizrSequenceAdapter.java:60`](src/main/java/ru/beeline/staging/pipeline/adapter/StructurizrSequenceAdapter.java:60)
- **Влияние**: `findTopByArtifactUidOrderByLoadedAtDesc` + `save` — между find и save возможна вставка другой транзакции → дубли.
- **Решение**: заменить на атомарный upsert (как в SparxE2EAdapter): `ON CONFLICT (artifact_uid) DO UPDATE SET ... WHERE ...` с сравнением `content_hash`.

#### P3-6 · StuckProcessMonitor авто-хилит setRetries(1) без оценки причины (Средняя)
- **Локация**: [`StuckProcessMonitor.java:80`](src/main/java/ru/beeline/staging/service/StuckProcessMonitor.java:80)
- **Влияние**: `healExternalTaskIncidents` устанавливает `retries=1` для всех noRetriesLeft, не различая временные ошибки и перманентные (bad data, config error).
- **Решение**: перед resetRetries проанализировать ошибку из incident-сообщения; при perma-error — помечать `failed` + notice; при transient — resetRetries.

#### P3-7 · Нет транзакционной защиты при публикации после save (Средняя)
- **Локация**: [`E2ECanonicalSaver.java`](src/main/java/ru/beeline/staging/pipeline/saver/E2ECanonicalSaver.java)
- **Влияние**: `saveSnapshot` (своя транзакция) → `publish` (HTTP call). Если publish падает после commit snapshot, данные опубликованы частично.
- **Решение**: реализовать saga-pattern: save snapshot → record pending_publish → publish → mark published. При сбое publish — retry из pending_publish.

---

### Р4. Lineage (происхождение данных)

#### P4-1 · Не все версии имеют match_notice_id / raw_data_context_id (Средняя)
- **Локация**: миграции V0003, V0008; MatchService
- **Влияние**: колонки `match_notice_id`/`raw_data_context_id` в `*_versions` могут быть NULL, т.к. некоторые MatchService не устанавливают эти FK при создании версий.
- **Решение**: сделать `match_notice_id`/`raw_data_context_id` обязательными (NOT NULL) при создании; добавить миграцию для заполнения существующих NULL → dummy notice.

#### P4-2 · Почти нет lineage API (Средняя)
- **Локация**: контроллеры
- **Влияние**: нет публичного API для запроса lineage-цепочки `raw_data → context → notice → version → identity`.
- **Решение**: добавить GET `/api/lineage/{entityType}/{entityUid}` → JSON с цепочкой: source → raw_data_ref → context(s) → notice(s) → version(s).

#### P4-3 · saveNotices не сохраняет entityType/entityUid/entityVersionId (Средняя)
- **Локация**: [`ArtifactNoticeService.java:29`](src/main/java/ru/beeline/staging/service/ArtifactNoticeService.java:29)
- **Влияние**: `artifact_notices` содержит только `notice_type_id`, `raw_data_context_id`, `details`. Нет FK на конкретную entity/version → невозможно query notices by entity.
- **Решение**: добавить колонки `entity_type`, `entity_uid`, `entity_version_id` в `artifact_notices`; заполнять из notice details.

#### P4-4 · Нет сквозного отображения raw → context → notice (Низкая)
- **Локация**: весь модуль lineage
- **Влияние**: для ручного исследования цепочки нужно выполнять 3+ SQL-запроса в разных таблицах.
- **Решение**: создать view `staging.lineage_trace` с JOIN raw_data_refs → raw_data_contexts → artifact_notices → *_versions.

---

### Р5. «Почему данные не попали в итоговый результат»

#### P5-1 · В лог не пишется stacktrace, только e.getMessage() (Средняя)
- **Локация**: [`AbstractWorker.java:62`](src/main/java/ru/beeline/staging/worker/AbstractWorker.java:62)
- **Влияние**: `e.getMessage()` может быть null или пустой (например, NullPointerException). В лог попадает только `e.toString()` (class + msg), без stacktrace.
- **Решение**: логировать с `logger.error("...", e)` — с полным stacktrace как 3-й аргумент SLF4J.

#### P5-2 · Нет «почему» для skipped-стадий (Средняя)
- **Локация**: [`SaverWorker.java:48`](src/main/java/ru/beeline/staging/worker/SaverWorker.java:48)
- **Влияние**: `isAlreadyCompleted`/`isAlreadyFullyProcessed` → skip без записи в output_data или notice → оператор не понимает, почему стадия не выполнена.
- **Решение**: при skip записывать notice `stage.skipped_<reason>` с объяснением; в `stageLog.output_data` фиксировать причину пропуска.

#### P5-3 · ValidatorWorker «тихо» пропускает при отсутствии модуля (Высокая)
- **Локация**: [`ValidatorWorker.java:67`](src/main/java/ru/beeline/staging/worker/ValidatorWorker.java:67)
- **Влияние**: при отсутствии валидатора (`validateModule == null`) → `completeStage(stageLogId, "skipped", null)`. Артефакт проходит дальше без валидации, но нет ни warning, ни error-notice.
- **Решение**: при отсутствии модуля валидации — генерировать notice `validate.module_missing` (error) + остановить артефакт через `handleBpmnError`.

#### P5-4 · 0 найденных артефактов — нет признака (Высокая)
- **Локация**: дубль P1-2
- **См.**: P1-2

---

## 4. Прочие проблемы

### O1. Безопасность

#### O1-1 · Учётные данные Camunda в коде/дефолтах (Высокая)
- **Локация**: [`application.yml:34`](src/main/resources/application.yml:34)
- **Влияние**: `camunda.admin-user-password: beeatlas` — пароль в коде. При компрометации репозитория → полный доступ к Camunda Engine.
- **Решение**: вынести в external secrets (Vault, AWS Secrets Manager, docker secrets). Убрать дефолтный пароль из yml.

#### O1-2 · Все /admin/** и /api/** без аутентификации (Высокая)
- **Локация**: [`AdminController.java`](src/main/java/ru/beeline/staging/controller/AdminController.java), все контроллеры
- **Влияние**: все админ-эндпоинты (`/admin/**`) и API (`/api/**`) открыты без авторизации. Любой с сетевым доступом может запускать сканы, трогать данные, читать notices.
- **Решение**: добавить Spring Security с basic auth или mTLS для `/admin/**`; RBAC для `/api/**`.

#### O1-3 · Сырые данные без ограничения доступа/размерности (Средняя)
- **Локация**: [`RawDataController.java:33`](src/main/java/ru/beeline/staging/controller/RawDataController.java:33)
- **Влияние**: GET `/raw-data/{id}` отдаёт весь `raw_content` без лимитов/странирования/авторизации. При больших файлах (MB) — DoS через OOM.
- **Решение**: добавить авторизацию + лимит response-size (StreamingResponseBody с Content-Length).

#### O1-4 · Пароли в логах/окружении (Низкая-Средняя)
- **Локация**: `*DataSourceConfig.java`
- **Влияние**: при логировании DataSource-параметров (например, при инициализации) пароль может попасть в логи.
- **Решение**: маскировать password в toString DataSource-конфигурации; использовать `password-encoder` в application.yml.

---

### O2. JPA ↔ SQL

#### O2-1 · Нет валидации соответствия JPA-сущностей и миграций (Высокая)
- **Локация**: весь проект (Hibernate `ddl-auto: none` + ручные миграции)
- **Влияние**: при изменении JPA-сущности без обновления миграции (или наоборот) возможна runtime-ошибка (NoSuchColumnException) или silent data loss.
- **Решение**: CI-проверка: сравнение JPA-мappings с migration-скриптами (SchemaCrawler, flyway-repair validation, liquibase-diff).

#### O2-2 · FK без индекс-поддержки (Средняя)
- **Локация**: миграции V0001, V0003
- **Влияние**: `artifact_notices.match_notice_id`, `raw_data_contexts.raw_data_ref_id` и другие FK не имеют индексов → медленные JOIN/фильтрации при росте данных.
- **Решение**: добавить индексы на все FK-колонки: `CREATE INDEX idx_xxx ON table(fk_column)`.

#### O2-3 · json_data jsonb ↔ String в JPA (Низкая)
- **Локация**: все `*_versions` таблицы, JPA-сущности `*Version.java`
- **Влияние**: `json_data` в БД — jsonb, в JPA — String. Нет автоматической валидации JSON-структуры при save.
- **Решение**: использовать `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) или custom UserType с валидацией.

#### O2-4 · operation_versions.name NOT NULL, но может прийти null (Низкая)
- **Локация**: [`OperationVersion.java`](src/main/java/ru/beeline/staging/domain/canonical/OperationVersion.java), V0003
- **Влияние**: `name NOT NULL` в БД, но из Sparx может прийти null → DB constraint violation → failed transaction.
- **Решение**: сделать name optional (NULLABLE) или заполнить дефолтом в transformer.

---

### O3. Производительность

#### O3-1 · fetchAndLock(10) фиксированный батч (Средняя)
- **Локация**: [`AbstractWorker.java:36`](src/main/java/ru/beeline/staging/worker/AbstractWorker.java:36)
- **Влияние**: фиксированный батч 10 задач. При высоких нагрузках — bottleneck; при малых — избыточные round-trips.
- **Решение**: параметризовать `batch-size` через config (Camunda `maxTasks`); адаптировать по workload.

#### O3-2 · raw_content целиком в памяти (Средняя)
- **Локация**: [`RawDataRef.java`](src/main/java/ru/beeline/staging/domain/RawDataRef.java) — `raw_content BYTEA`
- **Влияние**: весь raw-content загружается в heap. При 100MB артефакте → OOM.
- **Решение**: использовать `@Lob` + streaming (InputStream) для чтения; или хранить в S3/GCS с reference в БД.

#### O3-3 · ScenarioDecomposer — гигантский метод (Низкая-Средняя)
- **Локация**: [`ScenarioDecomposer.java:40`](src/main/java/ru/beeline/staging/pipeline/transformer/ScenarioDecomposer.java:40)
- **Влияние**: `decompose()` ~210 строк, класс ~1270 строк. Выше порога cognito-load → высокая вероятность скрытых багов.
- **Решение**: выделить подэтапы в отдельные классы: `DiagramTreeBuilder`, `OperationResolver`, `InterfaceResolver`, `NoticeGenerator`.

#### O3-4 · fetchActualScenarioRaw — тяжёлый CTE (Низкая-Средняя)
- **Локация**: [`ActualE2eScenarioRepository.java:17`](src/main/java/ru/beeline/staging/product/ActualE2eScenarioRepository.java:17)
- **Влияние**: рекурсивный CTE обходит все версии+реляции → экспоненциальный рост при deep hierarchies.
- **Решение**: добавить материализованный view с refresh on-change; или материализованный путь (materialized path / nested sets).

---

### O4. Конфигурация / обслуживание

#### O4-1 · integration.product-server-url без дефолта (Высокая)
- **Локация**: [`ProductServiceClient.java`](src/main/java/ru/beeline/staging/product/ProductServiceClient.java)
- **Влияние**: `@Value("${integration.product-server-url}")` без дефолта. Если property не задана → FailedToStartApplicationContext.
- **Решение**: добавить дефолт `${integration.product-server-url:}` + условная инициализация `@ConditionalOnProperty`; или documentировать mandatory property.

#### O4-2 · Нет spring.profiles (Средняя)
- **Локация**: `application.yml`
- **Влияние**: отсутствует разделение dev/staging/prod (hardcoded Camunda, один datasource).
- **Решение**: создать `application-dev.yml`, `application-prod.yml`; вынести sensitive параметры в external config.

#### O4-3 · logResumedOnStartup шумный (Низкая)
- **Локация**: Camunda auto-configuration
- **Влияние**: при старте логируются все resumed процессы, даже если их сотни.
- **Решение**: настроить Camunda `history-level=audit` + filter long-running instances.

#### O4-4 · Нет CHANGELOG / тегов (Низкая)
- **Локация**: git-репозиторий
- **Влияние**: невозможно отследить, что изменилось между релизами.
- **Решение**: вести CHANGELOG.md; использовать semantic versioning + git tags (bump2version уже настроен).

#### O4-5 · Только 4 теста на 100+ файлов (Средняя)
- **Локация**: `src/test/java/` (JsonDataValidatorTest, ScenarioDecomposerTest, SourceArtefactServiceTest, JsonByteRangeLocatorTest)
- **Влияние**: покрытие ~4%. Критические пути (workers, savers, decomposer) не покрыты.
- **Решение**: добавить интеграционные тесты для каждого воркера (Testcontainers + Postgres + Camunda); unit-тесты для transformer/match-сервисов.

---

## 5. Рекомендуемый набор метрик

### 5.1 Базовые JVM/инфра
- `jvm.gc.live.data.size`, `jvm.gc.memory.promoted`
- `system.cpu.usage`, `process.uptime`
- `http.server.requests` (Micrometer auto-metric)

### 5.2 Pipeline-метрики
| Метрика | Тип | Tags | Описание |
|---|---|---|---|
| `staging.pipeline.stage.duration` | Timer | stage, config_key, status=success\|error | Длительность стадии |
| `staging.pipeline.artifacts.total` | Counter | stage, config_key, direction=published\|dropped | Счётчик артефактов по стадиям |
| `staging.pipeline.notices.total` | Counter | category=validate\|transform\|save, level=error\|warning\|info | Счётчик notices |
| `staging.pipeline.scan.started` | Timer | config_key | Длительность полного скана |
| `staging.pipeline.child_runs{status}` | Gauge | status=active\|completed\|failed | Активные child-run'ы |

### 5.3 Worker-метрики
| Метрика | Тип | Tags | Описание |
|---|---|---|---|
| `staging.worker.poll.duration` | Timer | worker=adapter\|validator\|... | Длительность poll+handle |
| `staging.worker.poll.attempts` | Counter | worker, result=success\|error\|retry | Результаты poll |
| `staging.worker.fetch.batch_size` | Gauge | worker | Фактический размер батча |

### 5.4 Публикация
| Метрика | Тип | Tags | Описание |
|---|---|---|---|
| `staging.publish.requests` | Timer | product, status=2xx\|4xx\|5xx | HTTP-запросы к product-service |
| `staging.publish.errors.total` | Counter | error_type=timeout\|conflict\|not_found | Счётчик ошибок публикации |
| `staging.publish.retries` | Counter | artifact_uid | Ретраи публикации |

### 5.5 Camunda
| Метрика | Тип | Tags | Описание |
|---|---|---|---|
| `staging.camunda.external_task.locked` | Gauge | topic | Заблокированные задачи |
| `staging.camunda.external_task.retries` | Counter | topic, result=retried\|failed | Ретраи external tasks |
| `staging.camunda.incidents.active` | Gauge | kind=task\|job | Активные инциденты |

---

## 6. Приоритизированный Roadmap

### Фаза 1: Критичные (High) — 2-4 недели

| ID | Проблема | Оценка | Приоритет |
|---|---|---|---|
| P3-1 | Нет авто-ретраев | 2 дни | 1 |
| P3-2 | BPMN boundary error проглатывает ошибку | 2 дни | 2 |
| P1-2 / P5-4 | Тихая потеря при 0 артефактов | 1 день | 3 |
| P5-3 | Validator пропускает без модуля | 1 день | 4 |
| O1-1 | Пароль Camunda в коде | 1 день | 5 |
| O1-2 | Нет auth на /admin/** | 3 дни | 6 |
| P1-1 | Нет метрик (базовый набор) | 3 дни | 7 |
| P2-2 | Операции теряются без notice | 2 дни | 8 |
| O2-1 | Нет валидации JPA↔SQL | 2 дни | 9 |
| O4-1 | product-server-url без дефолта | 0.5 дня | 10 |

### Фаза 2: Стабилизация (Medium) — 4-8 недель

| ID | Проблема | Оценка |
|---|---|---|
| P3-3 | Гонка в tick scheduler | 2 дни |
| P3-5 | Не атомарный upsert Structurizr | 1 день |
| P3-6 | StuckProcessMonitor без оценки причины | 2 дни |
| P3-7 | Нет транзакции при publish | 3 дни |
| P3-4 | Gzip отключён | 3 дни |
| P1-3 | Дублирование статусов | 2 дни |
| P1-5 | Нет MDC/TraceId | 2 дни |
| P2-1 | Потеря notices при >200k | 2 дни |
| P2-3 | Нет дедупликаций версий | 2 дни |
| P2-4 | canonicalSnapshotJson без валидации | 1 день |
| P2-5 | Нет валидации snapshot | 2 дни |
| P4-1 | match_notice_id может быть NULL | 2 дни |
| P4-2 | Нет lineage API | 3 дни |
| P4-3 | saveNotices не сохраняет entity info | 2 дни |
| P5-1 | Нет stacktrace в логах | 1 день |
| P5-2 | Нет причины для skipped | 1 день |
| O2-2 | FK без индексов | 1 день |
| O3-1 | Фиксированный batch size | 1 день |
| O3-2 | raw_content в памяти | 3 дни |
| O3-4 | Тяжёлый CTE | 3 дни |
| O4-2 | Нет spring.profiles | 2 дни |
| O4-5 | Мало тестов | 5 дни |

### Фаза 3: Оптимизация (Low) — по плану

| ID | Проблема | Оценка |
|---|---|---|
| P1-4 | Нет таймингов стадий | 1 день |
| P4-4 | Нет lineage view | 1 день |
| O1-4 | Пароли в логах | 0.5 дня |
| O2-3 | jsonb ↔ String | 1 день |
| O2-4 | operation.name nullable | 0.5 дня |
| O3-3 | Refactor ScenarioDecomposer | 5 дни |
| O4-3 | logResumedOnStartup | 0.5 дня |
| O4-4 | CHANGELOG / tags | 1 день |

---

## 7. Таблица статусов проблем

> Шаблон для заполнения после обсуждения с командой.

| ID | Проблема | Критичность | Статус | Владелец | Дедлайн | Комментарий |
|---|---|---|---|---|---|---|
| P1-1 | Нет кастомных метрик | Высокая | Новое | | | |
| P1-2 | Тихая потеря при 0 артефактов | Высокая | Новое | | | |
| P1-3 | Дублирование статусов | Средняя | Новое | | | |
| P1-4 | Нет таймингов стадий | Низкая | Новое | | | |
| P1-5 | Нет MDC/TraceId | Средняя | Новое | | | |
| P2-1 | Потеря notices >200k | Средняя | Новое | | | |
| P2-2 | Операции теряются без notice | Высокая | Новое | | | |
| P2-3 | Нет дедупликаций версий | Средняя | Новое | | | |
| P2-4 | canonicalSnapshotJson без валидации | Средняя | Новое | | | |
| P2-5 | Нет валидации snapshot | Средняя | Новое | | | |
| P3-1 | Нет авто-ретраев | Высокая | Новое | | | |
| P3-2 | BPMN boundary error | Высокая | Новое | | | |
| P3-3 | Гонка в tick scheduler | Средняя | Новое | | | |
| P3-4 | Gzip отключён | Средняя | Новое | | | |
| P3-5 | Не атомарный upsert | Средняя | Новое | | | |
| P3-6 | StuckProcessMonitor без причины | Средняя | Новое | | | |
| P3-7 | Нет tx при publish | Средняя | Новое | | | |
| P4-1 | match_notice_id NULL | Средняя | Новое | | | |
| P4-2 | Нет lineage API | Средняя | Новое | | | |
| P4-3 | saveNotices без entity | Средняя | Новое | | | |
| P4-4 | Нет lineage view | Низкая | Новое | | | |
| P5-1 | Нет stacktrace | Средняя | Новое | | | |
| P5-2 | Нет причины для skipped | Средняя | Новое | | | |
| P5-3 | Validator пропускает | Высокая | Новое | | | |
| O1-1 | Пароль Camunda в коде | Высокая | Новое | | | |
| O1-2 | Нет auth | Высокая | Новое | | | |
| O1-3 | Сырые данные без лимитов | Средняя | Новое | | | |
| O1-4 | Пароли в логах | Низкая | Новое | | | |
| O2-1 | Нет валидации JPA↔SQL | Высокая | Новое | | | |
| O2-2 | FK без индексов | Средняя | Новое | | | |
| O2-3 | jsonb ↔ String | Низкая | Новое | | | |
| O2-4 | operation.name nullable | Низкая | Новое | | | |
| O3-1 | Фиксированный batch | Средняя | Новое | | | |
| O3-2 | raw_content в памяти | Средняя | Новое | | | |
| O3-3 | Refactor Decomposer | Низкая | Новое | | | |
| O3-4 | Тяжёлый CTE | Средняя | Новое | | | |
| O4-1 | product-server-url | Высокая | Новое | | | |
| O4-2 | Нет spring.profiles | Средняя | Новое | | | |
| O4-3 | logResumedOnStartup | Низкая | Новое | | | |
| O4-4 | CHANGELOG / tags | Низкая | Новое | | | |
| O4-5 | Мало тестов | Средняя | Новое | | | |

---

## 8. Ответы на уточняющие вопросы

| Вопрос | Ответ |
|---|---|
| Статус сервиса? | Прод-сервис в эксплуатации, есть реальные пользователи/потребители данных |
| Метрики/мониторинг? | Пока нет метрик — нужен оптимальный набор в рекомендациях |
| Источники данных? | Все источники (Sparx E2E + Structurizr) критичны; планируется расширение |
| TEMP-меры (gzip off, SPARX_DATASOURCE_DIAG)? | Осознанные временные меры, сроков нет — включить в отчёт как проблемы |
| Формат отчёта? | Файл `.local/audit-report.md` (вне git, служебный) |

---

## 9. Рекомендации по внедрению

### Поэтапный подход
1. **Фаза 1 (2-4 нед.)**: Исправить P3-1/P3-2 (надёжность), O1-1/O1-2 (безопасность), P1-2/P5-3 (наблюдаемость критических путей).
2. **Фаза 2 (4-8 нед.)**: Метрики P1-1, MDC P1-5, дедупликация P2-3, lineage P4-1..P4-3, тесты O4-5.
3. **Фаза 3 (по плану)**: Refactoring, оптимизация, документация.

### Риск-менеджмент
- **Наибольший риск**: P3-1 (retries=0) + P3-2 (BPMN error swallow) → потеря данных в продакшене без возможности автоматического восстановления.
- **Средний риск**: O1-1/O1-2 → компрометация системы; P1-1 → слепая эксплуатация.
- **Низкий риск**: косметические проблемы, документация.

---

*Отчёт сформирован автоматически в результате аудита. Обновлять по результатам обсуждения с командой.*
