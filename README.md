# Staging Service

Конфигурируемый ETL-пайплайн (чистая Java/Spring, без внешнего BPM-движка):

```
preAdapter → adapter → validator → transformer → saver
```

Набор модулей на каждый этап для конкретной сущности задаётся кодом — одной записью в
`pipeline/PipelineDefinitions.java`. Любой разработчик может добавить поддержку своей
сущности/источника без правок ядра (`pipeline/exec/*Stage`, `PipelineExecutionService`,
`ModuleResolver`).

## Как добавить свою сущность

1. **Код.** Создай свои классы-модули в соответствующих папках — каждый как обычный `@Component` со своим `moduleCode()` и `description()`:

   | Папка | Интерфейс |
   |---|---|
   | `pipeline/preadapter/` | `ArtifactPreAdapter` — находит список артефактов в источнике |
   | `pipeline/adapter/` | `ArtifactAdapter` — скачивает сырые данные одного артефакта |
   | `pipeline/validator/` | `ArtifactValidator` — проверяет сырые данные, возвращает `ValidateResult` |
   | `pipeline/transformer/` | `ArtifactTransformer` — маппит сырые данные в свою сущность, возвращает `TransformResult` |
   | `pipeline/saver/` | `ArtifactSaver` — сохраняет сущность в БД, возвращает `SaveResult` |

   Смотри `pipeline/*/E2E*.java` / `SparxE2EPreAdapter.java` / `DashboardE2EAdapter.java` как живой пример.

   `ValidateResult`/`TransformResult`/`SaveResult` (пакет `dto/notice`) кроме основного результата стадии (`TransformResult` — ещё и `snapshot`, `SaveResult` — ещё и `summary`) несут `List<ArtifactNotice> notices` — список примечательных фактов, которые модуль обнаружил на своей стадии (предупреждение валидации, "почему эта версия сущности была создана/сматчена с существующей" и т.п.). Сохранять notices руками не нужно: `ValidatorWorker`/`TransformerWorker` сами прогоняют их через `PipelineRunService.saveNotices(...)`; `Saver`, которому нужен `id` notice синхронно (чтобы проставить `matchNoticeId` на сохраняемую версию сущности), обращается к `ArtifactNoticeService` напрямую внутри своей транзакции — как это делает `E2ECanonicalSaver`. Живые примеры генерации notices: `E2ESequenceValidator` (warning по встроенным `validationError`) и `E2ECanonicalSaver` (provenance матчинга interface/operation/bi_step). Подробнее — раздел "Data lineage / notices" ниже.

2. **Миграция.** Напиши `db/migration/V000N__<entity>.sql` со своими каноническими таблицами (по образцу `V0002__create_canonical_catalogs.sql` + `V0003__create_version_and_relation_tables.sql` + `V0008__add_json_data_to_version_tables.sql`: identity-таблица `id`+`uid`+`created_at`, отдельно `*_versions` со снимком, `raw_data_context_id`/`match_notice_id` и **JSONB-колонкой `json_data`** для неосновных атрибутов — см. ADR-011). Эти таблицы видны только твоему `ArtifactSaver` — остальной пайплайн про них не знает. Заведи JPA-сущность в `domain/canonical/` и репозиторий в `repository/canonical/` **колонка-в-колонку** с миграцией — `hibernate.ddl-auto: none` не подскажет расхождение на старте, оно проявится только первым INSERT/SELECT.

3. **Конфиг пайпа.** Добавь одну запись в `DEFINITIONS` в [`pipeline/PipelineDefinitions.java`](src/main/java/ru/beeline/staging/pipeline/PipelineDefinitions.java) — ключ это `artifactType`, значение — карта `этап → твой moduleCode()`:

   ```java
   "my-entity", Map.of(
           "pre-adapter", MyPreAdapter.MODULE_CODE,
           "adapter",     MyAdapter.MODULE_CODE,
           "validator",   MyValidator.MODULE_CODE,
           "transformer", MyTransformer.MODULE_CODE,
           "saver",       MySaver.MODULE_CODE
   )
   ```

4. **Строка в БД.** Добавь строку в `staging.configurations` (`artifact_type`, `data_type_id`, `schedule_interval_seconds`, `is_active`) — она отвечает только за расписание/активность, не за то, какие модули запускать (это теперь решает `PipelineDefinitions`). **Это единственный шаг, который не самосинхронизируется с кодом** — `data_types`/`source_systems`/`configurations` никто не создаёт автоматически (в отличие от `module_catalog`/`pipeline_definitions`, которые `ModuleCatalogPublisher` пересобирает на каждом старте): без активной строки в `configurations` `PipelineTickScheduler` находит ноль кандидатов и пайплайн не запускается вообще, без единой ошибки в логе. Пример seed-миграции — `V0005__seed_pipeline_configurations.sql`.

Готово — воркеры, BPMN-процессы и `ModuleResolver` трогать не нужно.

## Справочники (генерируются автоматически)

На каждом старте `ModuleCatalogPublisher` синхронизирует два справочника с текущим кодом — их никогда не редактируют руками, это просто отражение деплоя:

| Таблица | Что показывает |
|---|---|
| `staging.module_catalog` | Все зарегистрированные модули: `module_code`, `module_type`, `description()`. Пересобирается с нуля (delete+insert) — на эту таблицу никто не ссылается по FK. |
| `staging.pipeline_definitions` | Версионированный список модулей на `artifact_type` (`modules_sequence`, упорядоченный JSON `[{stage, moduleCode}, ...]`). Новая строка (`is_current=true`, старая — `false`) появляется только когда набор модулей реально поменялся; старые версии не трогаются — на них могут ссылаться исторические `pipeline_runs.pipeline_definition_id`. |

## Где смотреть, как идут процессы

| Вопрос | Таблица |
|---|---|
| Что и откуда скачали, по какому идентификатору | `staging.raw_data_refs` |
| Как и когда запускался pre-adapter, что нашёл/с какой ошибкой упал | `staging.pipeline_runs WHERE artifact_uid IS NULL` — это и есть строка скана (конфигурация × тик); найденные id — в `pipeline_stage_logs.output_data` её единственной стадии `pre-adapter` (просто строка `uid1,uid2,uid3`, не JSON) |
| Какие артефакты (и их стадии) породил конкретный запуск pre-adapter'а | `staging.pipeline_runs WHERE parent_run_id = <id строки-скана>` → join `pipeline_stage_logs` |
| Все стейджи всех артефактов одной выгрузки (без join'а на `pipeline_runs`) | `staging.pipeline_stage_logs WHERE scan_run_id = <id строки-скана>` |
| Стейджи конкретного артефакта в рамках конкретной выгрузки | `pipeline_stage_logs psl JOIN pipeline_runs pr ON pr.id = psl.run_id WHERE psl.scan_run_id = <id скана> AND pr.artifact_uid = '<guid>'` |
| Статус каждого запуска целиком (pending/loading/.../completed/failed) | `staging.pipeline_runs` |
| Какие модули планировались для конкретного запуска | `staging.pipeline_runs.pipeline_definition_id` → `staging.pipeline_definitions.modules_sequence` (FK, не дублируется в каждой строке `pipeline_runs`) |
| Что произошло на каждом этапе конкретного запуска — вход и выход | `staging.pipeline_stage_logs` (`input_data`/`output_data`) |
| Какие артефакты источника вообще существуют (по `ext_uid`), когда их последний раз видели | `staging.source_artefacts` (`last_seen_scan_run_id` → `pipeline_runs`, скан, который его в последний раз нашёл) |

`staging.source_artifacts`/`source_artifact_types` — identity-учёт артефактов источника по `ext_uid` (отдельно от `pipeline_runs`, который про запуски пайплайна, а не про "что есть в источнике"). `PreAdapterWorker` на каждый найденный сканом артефакт делает upsert через `SourceArtefactService.recordSeen(config, extUid, scanRunId, runId, name)`: если артефакт новый — создаёт строку (`status=active`), если уже был — обновляет `status=active`/`last_seen_scan_run_id`/`updated_at`. `source_artifact_types` резолвится по паре `(data_type_id, source_system_id)` конфигурации. Поле `source_artifacts.name` (BLG-002/BLG-003, FR-003-16..19) — читаемое имя артефакта из преадаптера: `PreAdapterWorker` извлекает его из `FoundArtifact.metadata` (ключ `name` у Sparx E2E, ключ `productName` у Structurizr sequence); имя опционально (BR-13), непустое значение не перезаписывается пустым, fallback для отображения — `ext_uid` + тип артефакта.

`pipeline_stage_logs.input_data`/`output_data` — обычный `TEXT`, не `jsonb`. Каждый воркер логирует туда **только идентификатор**, который реально обработал (`rawDataRefId` у Validator/Transformer/Saver, `artifactUid` у Adapter, список `uid1,uid2,...` у Pre-Adapter) — не весь дамп переменных Camunda и не JSON-обёртку. Остальной контекст (artifactType, configurationId, pipelineRunId) уже есть на родительской строке `pipeline_runs` через `run_id`, дублировать его в каждой стадии незачем.

## Data lineage / notices (отслеживание происхождения данных)

Notice — запись о примечательном факте, который модуль обнаружил на своей стадии (validate/transform/save) для конкретного `raw_data_ref`: предупреждение валидации, встроенная ошибка из исходного JSON, или provenance-заметка "почему эта версия сущности была создана / с какой существующей сматчена".

| Таблица | Что хранит |
|---|---|
| `staging.notice_types` | Справочник различимых кодов (`code` уникален, `level` ∈ info/warning/error, `category` ∈ validation/transform/match). Строка создаётся автоматически при первом появлении кода (`state=pending`); человек может её `confirm` (легитимный, ожидаемый код) или `reject` (шумный/ложный — после этого новые occurrence с этим кодом больше не сохраняются). |
| `staging.artifact_notices` | Сами occurrence — по одной строке на каждый вызов, ссылаются на `notice_type_id` и `raw_data_context_id` (обязательная FK на `staging.raw_data_contexts`, точку в сыром JSON — `RawDataContextService` создаёт её на лету из `ArtifactNotice.context()`, если это json-pointer, либо как free-text fallback), несут `details`. |

Поведение при обработке: если валидатор вернул хотя бы один notice с `level=error` — стадия падает (`IllegalStateException`), warning-и просто копятся в `noticeCount`/`validationWarningsCount` вывода стадии. Модули не пишут в эти таблицы напрямую — `ValidatorWorker`/`TransformerWorker` сохраняют notices через `PipelineRunService.saveNotices(...)`, а `Saver` — синхронно через `ArtifactNoticeService` (нужен id сразу же).

Provenance-связь: колонка `match_notice_id` на `product_versions`/`container_versions`/`tech_capability_versions`/`interface_versions`/`operation_versions`/`sequence_versions`/`bi_step_versions` указывает на notice, объясняющую появление этой версии — например коды `match.interface.created`, `match.operation.matched_by_uid`, `match.bi_step.always_new` (у bi_step пока нет правила дедупликации — он всегда "новый").

Admin API для управления справочником кодов:
- `GET /admin/notice-types?state=pending` — список кодов в заданном состоянии (по умолчанию `pending`);
- `POST /admin/notice-types/{code}/confirm?confirmedBy=...` — подтвердить код как ожидаемый;
- `POST /admin/notice-types/{code}/reject?rejectedBy=...` — пометить код как шумный (новые occurrence с ним перестают сохраняться).

Эндпоинта для просмотра отдельных occurrence пока нет — только уровень типов; сами occurrence смотрятся прямым SQL-запросом к `staging.artifact_notices` или через `match_notice_id`.

## Каноническая модель product/container/tc/interface/operation/sequence

`V0001`–`V0004` определяют единую identity+version схему, общую для `artifactType=structurizr-sequence` (и частично переиспользуемую e2e-моделью): каждая сущность — это **identity-таблица** (`id` + `uid` + `created_at`, дедуп по `uid`) плюс отдельная **`*_versions`-таблица** (снимок конкретной выгрузки: поля, FK на предыдущий слой, `raw_data_context_id`, `match_notice_id`, `created_at`). JPA-сущности в `domain/canonical/` и репозитории в `repository/canonical/` следуют этим таблицам колонка-в-колонку — при правке миграции проверяй обе стороны, они расходятся легко и без ошибки схемы на старте (`hibernate.ddl-auto: none` не проверяет соответствие).

| Identity (`id`+`uid`) | Версии (`*_versions`) | Родитель по FK |
|---|---|---|
| `products` / `Product` | `product_versions` / `ProductVersion` | — |
| `containers` / `Container` | `container_versions` / `ContainerVersion` | `product_version_id` |
| `tech_capabilities` / `TechCapability` | `tech_capability_versions` / `TechCapabilityVersion` | — |
| `interfaces` / `InterfaceEntity` | `interface_versions` / `InterfaceVersion` | `container_version_id` |
| `operations` / `OperationEntity` | `operation_versions` / `OperationVersion` | `interface_version_id`, `tech_capability_version_id` |
| `sequences` / `SequenceEntity` | `sequence_versions` / `SequenceVersion` | `tech_capability_version_id` |
| — | `sequence_relation_versions` / `SequenceRelationVersion` | `sequence_version_id` → `operation_version_id` |
| — | `operation_relation_versions` / `OperationRelationVersion` | `operation_version_id` → `related_operation_version_id` |

`StructurizrSequenceCanonicalSaver` записывает всё это строго в этом порядке (каждый следующий слой резолвит FK на предыдущий через in-memory map по `uid`, наполняемую соответствующим `*MatchService`: `ProductMatchService`, `ContainerMatchService`, `TechCapabilityMatchService`, `InterfaceMatchService`, `OperationMatchService`, `SequenceMatchService`).

**Осторожно с термином.** `staging.stages` и сущности `Stage`/`StageTc`/`StageTcVersion`/`StageSequence`/`StageSequenceRelation` — это **не** "стадия пайплайна" (pre-adapter/adapter/validator/transformer/saver из разделов выше), а отдельный справочник состояний/окружений, к которому привязываются версии ТС/Sequence: своя колонка `status` (active/inactive/deleted) и у `StageTcVersion` — `next_version_id`, указывающий на версию, которая её сменила (цепочка версий в рамках конкретного Stage). Эти таблицы (`stage_tech_capabilities`, `stage_tech_capability_versions`, `stage_sequences`, ...) пока не заполняются ни одним `Saver` — только миграция + JPA-сущности + репозитории.

### Пайплайн structurizr-sequence

Источник — `workspace.json` из Structurizr (C4-модель: `softwareSystem` → `container` → `component`). Продукт определяется через `model.properties.workspace_cmdb`, сматченный с `softwareSystem.properties."structurizr.dsl.identifier"` — один workspace может содержать несколько systems, обрабатывается только целевая.

| Стадия | Модуль | Что делает |
|---|---|---|
| Pre-Adapter | `StructurizrSequencePreAdapter` | Опрашивает `fdm-products` и возвращает мнемоники (alias) всех продуктов с непустым `structurizrApiUrl` — то есть продуктов, для которых в Structurizr описана архитектура |
| Adapter | `StructurizrSequenceAdapter` | Скачивает `{structurizrApiUrl}/json` для продукта, дедуп по `content_hash` в `raw_data_refs` |
| Validator | `StructurizrSequenceValidator` | Структурная проверка + бизнес-правило: как минимум один `dynamicView.relationships[].description` должен резолвиться (см. ниже) в операцию, реально объявленную в `properties` одного из `type=api` компонентов продукта — иначе `error`-notice и стадия падает |
| Transformer | `StructurizrDynamicViewDecomposer` (вызывается из `StructurizrSequenceTransformer`) | Раскладывает workspace.json на снимок `StructurizrSequenceSnapshot` (product/containers/techCapabilities/interfaces/operations/sequences/sequenceRelations/operationRelations) строго в порядке зависимостей из `structurizr-sequence-transform-rules.md` |
| Saver | `StructurizrSequenceCanonicalSaver` | См. таблицу выше |

Извлечение по типам элементов C4:

- `container` → `containers`/`container_versions`, identity-`uid` = `properties.external_name`;
- `component(type=capability)` → `tech_capabilities`/`tech_capability_versions`, `uid` = `{cmdb}.{properties.code}`;
- `component(type=api)` → `interfaces`/`interface_versions`, `uid` = `properties.external_name`;
- ключи `properties` такого компонента (кроме служебных — `external_name`, `api_url`, `protocol`, `version`, `tc`, `code`, `parents`, `source`) → `operations`/`operation_versions`, `uid` = `{interface_uid}_{normalized_name}`; значение — SLA-строка `RPS=..;LATENCY=..;ERROR_RATE=..;TC=..` (поддержаны оба разделителя, `:` и `=`);
- `views.dynamicViews[]`, отфильтрованные по `elementId` целевой системы → `sequences`/`sequence_versions`, `uid` = `key`, привязка к ТС через `key` (как `{cmdb}.{key}`, либо `key` уже содержит точку).

Каждый шаг `dynamicView.relationships[]` резолвится в операцию через `StructurizrParsingUtils.canonicalOperationKey(description)` — тот же формат ключа (`"{METHOD} {path}"` для REST, нижний регистр имени метода для SOAP), которым при извлечении `operations` индексируется ключ свойства интерфейса; это тот же resolver, что использует Validator для своей проверки. Первый relationship диаграммы (минимальный `order`) определяет "инициатора" (`relatedCallerId`/`sourceId`): вызовы от инициатора идут в `sequence_relation_versions`, остальные — в `operation_relation_versions` (caller резолвится по элементу-получателю предыдущего шага цепочки).

### Один скан, один реальный Pre-Adapter

`PipelineExecutionService.runScan(config)` — один запуск на конфигурацию×тик (не на артефакт):
`Pre-Adapter` выполняется один раз, без дублей и заглушек, дальше по очереди —
`Adapter → Validator → Transformer → Saver` на каждый найденный артефакт
(`PipelineExecutionService.runArtifactChain`, сейчас последовательно — параллельная
обработка добавляется отдельным этапом):

```
[Pre-Adapter] → [ Adapter → Validator → Transformer → Saver ] × N найденных артефактов
```

`PreAdapterStage.scan(...)` — строго фазами, без перекрытия:
1. `ArtifactPreAdapter.scan(config)` — чистое чтение источника, без побочных эффектов (модуль не знает о `PipelineRunService` вообще);
2. результат скана (найденные uid или ошибка) полностью записывается в `pipeline_runs`/`pipeline_stage_logs` (строка с `artifact_uid IS NULL`);
3. только после этого `PipelineExecutionService` создаёт по одной строке `pipeline_runs` (`artifact_uid` заполнен, `parent_run_id` = строка-скан) на каждый найденный артефакт и сразу прогоняет её через `runArtifactChain`.

Каждый `*Stage` (`pipeline/exec/AdapterStage` и т.д.) сам перечитывает `PipelineRun`/`RawDataRef` по `runId` — отдельного механизма передачи переменных между стадиями не нужно, все нужные поля (`artifactUid`, `artifactType`, `configurationId`, `rawDataRefId`) уже есть на самой строке `pipeline_runs`.

`pipeline_runs.batch_id` — общий идентификатор запуска (UUID, общий и у строки-скана, и у всех найденных ею артефактов) — для привязки конкретного артефакта именно к своему скану используйте `parent_run_id`, не `batch_id`. Колонки `camunda_pid`/`execution_id` оставлены в схеме как исторические (nullable, больше не заполняются).
