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
   | `pipeline/validator/` | `ArtifactValidator` — проверяет сырые данные |
   | `pipeline/transformer/` | `ArtifactTransformer` — маппит сырые данные в свою сущность |
   | `pipeline/saver/` | `ArtifactSaver` — сохраняет сущность в БД |

   Смотри `pipeline/*/E2E*.java` / `SparxE2EPreAdapter.java` / `DashboardE2EAdapter.java` как живой пример.

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
| Как и когда запускался pre-adapter, что нашёл/с какой ошибкой упал | `staging.pipeline_runs WHERE artifact_uid IS NULL` — это и есть строка скана (конфигурация × тик); найденные id — в `pipeline_stage_logs.output_data.foundArtifactUids` её единственной стадии `pre-adapter` |
| Какие артефакты (и их стадии) породил конкретный запуск pre-adapter'а | `staging.pipeline_runs WHERE parent_run_id = <id строки-скана>` → join `pipeline_stage_logs` |
| Статус каждого запуска целиком (pending/loading/.../completed/failed) | `staging.pipeline_runs` |
| Какие модули планировались для конкретного запуска | `staging.pipeline_runs.pipeline_definition_id` → `staging.pipeline_definitions.modules_sequence` (FK, не дублируется в каждой строке `pipeline_runs`) |
| Что произошло на каждом этапе конкретного запуска — вход и выход | `staging.pipeline_stage_logs` (`input_data`/`output_data`) |

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
