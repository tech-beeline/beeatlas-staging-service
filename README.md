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

На каждом старте `ModuleCatalogPublisher` пересобирает с нуля два справочника из текущего кода — их никогда не редактируют руками, это просто отражение деплоя:

| Таблица | Что показывает |
|---|---|
| `staging.module_catalog` | Все зарегистрированные модули: `module_code`, `module_type`, `description()` |
| `staging.pipeline_definitions` | Какие модули запустятся для каждого `artifact_type` — снэпшот `PipelineDefinitions`, виден до первого запуска пайпа |

## Где смотреть, как идут процессы

| Вопрос | Таблица |
|---|---|
| Что и откуда скачали, по какому идентификатору | `staging.raw_data_refs` |
| Статус каждого запуска целиком (pending/loading/.../completed/failed) | `staging.pipeline_runs` |
| Какие модули планировались для конкретного запуска | `staging.pipeline_runs.modules_sequence` (jsonb-массив moduleCode, снэпшот на момент создания запуска) |
| Что произошло на каждом этапе конкретного запуска — вход и выход | `staging.pipeline_stage_logs` (`input_data`/`output_data`) |

Pre-adapter (сканирование источника) тоже создаёт свой `pipeline_run`/`pipeline_stage_logs` — ошибка скачивания списка артефактов из источника видна там же, а не только в логах/Camunda Incident.

Визуально то же самое — в Camunda Cockpit: `http://localhost:8085/camunda` (логин `beeatlas`/`beeatlas`).
