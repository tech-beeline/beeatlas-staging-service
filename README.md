# Staging Service

Конфигурируемый ETL-пайплайн (Java/Spring + Camunda BPMN):

```
preAdapter → adapter → validator → transformer → saver
```

Набор модулей на каждый этап для конкретной сущности задаётся кодом — одной записью в
`pipeline/PipelineDefinitions.java`. Любой разработчик может добавить поддержку своей
сущности/источника без правок ядра (воркеров, BPMN, `ModuleResolver`).

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

2. **Миграция.** Напиши `db/migration/V000N__<entity>.sql` со своими каноническими таблицами (по образцу `V0004__canonical_model_e2e.sql`). Эти таблицы видны только твоему `ArtifactSaver` — остальной пайплайн про них не знает.

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

4. **Строка в БД.** Добавь строку в `staging.configurations` (`artifact_type`, `data_type_id`, `schedule_interval_seconds`, `is_active`) — она отвечает только за расписание/активность, не за то, какие модули запускать (это теперь решает `PipelineDefinitions`).

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

`staging.source_artefacts`/`source_artefact_types` — identity-учёт артефактов источника по `ext_uid` (отдельно от `pipeline_runs`, который про запуски пайплайна, а не про "что есть в источнике"). `PreAdapterWorker` на каждый найденный сканом артефакт делает upsert через `SourceArtefactService.recordSeen(config, extUid, scanRunId)`: если артефакт новый — создаёт строку (`status=active`), если уже был — обновляет `status=active`/`last_seen_scan_run_id`/`updated_at`. `source_artefact_types` резолвится по паре `(data_type_id, source_system_id)` конфигурации.

`pipeline_stage_logs.input_data`/`output_data` — обычный `TEXT`, не `jsonb`. Каждый воркер логирует туда **только идентификатор**, который реально обработал (`rawDataRefId` у Validator/Transformer/Saver, `artifactUid` у Adapter, список `uid1,uid2,...` у Pre-Adapter) — не весь дамп переменных Camunda и не JSON-обёртку. Остальной контекст (artifactType, configurationId, pipelineRunId) уже есть на родительской строке `pipeline_runs` через `run_id`, дублировать его в каждой стадии незачем.

## Data lineage / notices (отслеживание происхождения данных)

Notice — запись о примечательном факте, который модуль обнаружил на своей стадии (validate/transform/save) для конкретного `raw_data_ref`: предупреждение валидации, встроенная ошибка из исходного JSON, или provenance-заметка "почему эта версия сущности была создана / с какой существующей сматчена".

| Таблица | Что хранит |
|---|---|
| `staging.notice_types` | Справочник различимых кодов (`code` уникален, `level` ∈ info/warning/error, `category` ∈ validation/transform/match). Строка создаётся автоматически при первом появлении кода (`state=pending`); человек может её `confirm` (легитимный, ожидаемый код) или `reject` (шумный/ложный — после этого новые occurrence с этим кодом больше не сохраняются). |
| `staging.artifact_notices` | Сами occurrence — по одной строке на каждый вызов, привязаны к `raw_data_ref_id`, ссылаются на `notice_type_id`, несут `context`/`details`. |

Поведение при обработке: если валидатор вернул хотя бы один notice с `level=error` — стадия падает (`IllegalStateException`), warning-и просто копятся в `noticeCount`/`validationWarningsCount` вывода стадии. Модули не пишут в эти таблицы напрямую — `ValidatorWorker`/`TransformerWorker` сохраняют notices через `PipelineRunService.saveNotices(...)`, а `Saver` — синхронно через `ArtifactNoticeService` (нужен id сразу же).

Provenance-связь: колонка `match_notice_id` на `bi_step_versions`/`interface_versions`/`operation_versions`/`tc_versions`/`sequence_versions` указывает на notice, объясняющую появление этой версии — например коды `match.interface.created`, `match.operation.matched_by_ext_uid`, `match.bi_step.always_new` (у bi_step пока нет правила дедупликации — он всегда "новый").

Admin API для управления справочником кодов:
- `GET /admin/notice-types?state=pending` — список кодов в заданном состоянии (по умолчанию `pending`);
- `POST /admin/notice-types/{code}/confirm?confirmedBy=...` — подтвердить код как ожидаемый;
- `POST /admin/notice-types/{code}/reject?rejectedBy=...` — пометить код как шумный (новые occurrence с ним перестают сохраняться).

Эндпоинта для просмотра отдельных occurrence пока нет — только уровень типов; сами occurrence смотрятся прямым SQL-запросом к `staging.artifact_notices` или через `match_notice_id`.

## Каноническая модель Tc/Sequence (в разработке, ещё не подключена к пайплайну)

`V0006__canonical_model_sequence.sql` добавляет схему для будущего `artifactType=sequence`, параллельную существующей e2e-модели:

- `tc`/`tc_versions` — Tc ("Технологическая Цепочка") — продуктовый аналог `bi_steps`/`bi_step_versions`;
- `sequences`/`sequence_versions` — один Tc → один-или-много Sequence (аналога в e2e-модели нет);
- `sequence_relation_versions` — рёбра caller→callee внутри Sequence (ссылаются на существующие `operation_versions` из e2e-модели).

**Осторожно с термином.** `staging.stages` и сущности `Stage`/`StageTc`/`StageTcVersion`/`StageSequence`/`StageSequenceRelation` — это **не** "стадия пайплайна" (pre-adapter/adapter/validator/transformer/saver из разделов выше), а отдельный справочник состояний/окружений, к которому привязываются версии Tc/Sequence: своя колонка `status` (active/inactive/deleted) и у `StageTcVersion` — `next_version_id`, указывающий на версию, которая её сменила (цепочка версий в рамках конкретного Stage).

Статус: пока только миграция + JPA-сущности (`domain/canonical/Tc*`, `Sequence*`, `Stage*`) + Spring Data репозитории. Ни один `ArtifactValidator`/`Transformer`/`Saver` эту модель не читает и не пишет — `SequenceModelSaverService`, упомянутый в комментарии миграции как будущий потребитель, ещё не написан. Не путать `E2ESequenceValidator`/`E2ESequenceTransformer` — они работают со старой e2e-моделью (`artifactType=e2e-sequence`), "Sequence" в их имени про форму исходного JSON, а не про эти таблицы.

### Один Camunda-процесс, один реальный Pre-Adapter

`artifact-pipeline-process` — теперь **один** BPMN-процесс на конфигурацию×тик (не на артефакт): `Pre-Adapter` — настоящий первый Camunda-таск, без дублей и заглушек. Дальше идёт Multi-Instance подпроцесс `Adapter → Validator → Transformer → Saver`, по одной итерации на каждый найденный артефакт (`isSequential=true` — итерации строго друг за другом, без параллелизма):

```
[Pre-Adapter] → [[ Adapter → Validator → Transformer → Saver ]] × N найденных артефактов
```

`PreAdapterWorker` (обработчик таска `Pre-Adapter`) — строго фазами, без перекрытия:
1. `ArtifactPreAdapter.scan(config)` — чистое чтение источника, без побочных эффектов (модуль не знает о `PipelineRunService` вообще);
2. результат скана (найденные uid или ошибка) полностью записывается в `pipeline_runs`/`pipeline_stage_logs` (строка с `artifact_uid IS NULL`);
3. только после этого создаётся по одной строке `pipeline_runs` (`artifact_uid` заполнен, `parent_run_id` = строка-скан) на каждый найденный артефакт, и список `"<runId>|<uid>"` отдаётся в процесс как коллекция `artifactRefs` — именно по ней Camunda гоняет Multi-Instance подпроцесс.

`AdapterWorker` — единственный "особый" воркер: на момент его первого вызова в итерации `pipelineRunId` ещё не существует как переменная процесса (она появляется только после того, как pre-adapter нашёл артефакты), поэтому он сам парсит `artifactRef`, сам ведёт лог своей стадии, и сам прокидывает `pipelineRunId`/`artifactUid` дальше как выходные переменные — после него `Validator/Transformer/Saver` работают как обычно, через стандартный механизм `AbstractWorker`.

Раз один процесс теперь обслуживает сразу много артефактов, для retry используется не `processInstanceId` (общий для всех итераций), а `executionId` конкретной итерации (`pipeline_runs.execution_id`, выставляется `AdapterWorker`).

`pipeline_runs.batch_id` = `processInstanceId` всего процесса (общий и у строки-скана, и у всех найденных ею артефактов) — для привязки конкретного артефакта именно к своему скану используйте `parent_run_id`, не `batch_id`.

Визуально то же самое — в Camunda Cockpit: `http://localhost:8085/camunda` (логин `beeatlas`/`beeatlas`).
