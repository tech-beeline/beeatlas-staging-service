# Staging Service

Конфигурируемый ETL-пайплайн (Java/Spring + Camunda BPMN):

```
preAdapter → adapter → validator → transformer → saver
```

Конфигурация кода на каждом этапе — определяет JSON в `staging.configurations.config`
Любой разработчик может добавить поддержку своей сущности/источника без правок ядра.

## Как добавить свою сущность

1. **Код.** Создай свои классы-модули в соответствующих папках — каждый как обычный `@Component` со своим `moduleCode()`:

   | Папка | Интерфейс |
   |---|---|
   | `pipeline/preadapter/` | `ArtifactPreAdapter` — находит список артефактов в источнике |
   | `pipeline/adapter/` | `ArtifactAdapter` — скачивает сырые данные одного артефакта |
   | `pipeline/validator/` | `ArtifactValidator` — проверяет сырые данные |
   | `pipeline/transformer/` | `ArtifactTransformer` — маппит сырые данные в свою сущность |
   | `pipeline/saver/` | `ArtifactSaver` — сохраняет сущность в БД |

   Смотри `pipeline/*/E2E*.java` / `SparxE2EPreAdapter.java` / `DashboardE2EAdapter.java` как живой пример.

2. **Миграция.** Напиши `db/migration/V000N__<entity>.sql` со своими канонической таблицами (по образцу `V0004__canonical_model_e2e.sql`). Эти таблицы видны только твоему `ArtifactSaver` — остальной пайплайн про них не знает.

3. **Конфиг.** Добавь строку в `staging.configurations` с JSON, где ключ — имя этапа, значение — твой `moduleCode()`:

   ```json
   {
     "pre-adapter": "my-preadapter",
     "adapter": "my-adapter",
     "validator": "my-validator",
     "transformer": "my-transformer",
     "saver": "my-saver"
   }
   ```

Готово — воркеры, BPMN-процессы и `ModuleResolver` трогать не нужно.

## Где смотреть, как идут процессы

| Вопрос | Таблица |
|---|---|
| Что и откуда скачали, по какому идентификатору | `staging.raw_data_refs` |
| Статус каждого запуска целиком (pending/loading/.../completed/failed) | `staging.pipeline_runs` |
| Что произошло на каждом этапе конкретного запуска — вход и выход | `staging.pipeline_stage_logs` (`input_data`/`output_data`) |

Визуально то же самое — в Camunda Cockpit: `http://localhost:8085/camunda` (логин `beeatlas`/`beeatlas`).
