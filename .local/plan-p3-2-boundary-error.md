# План исправления P3-2: Boundary error в BPMN

> Анализ проблемы — [`.local/analysis-boundary-error.md`](.local/analysis-boundary-error.md)

## Целевое состояние

1. **Camunda process instance** при ошибке артефакта завершается как `FAILED`/`INCIDENT` (не `COMPLETED`).
2. **Staging-БД** (`pipeline_runs.status`) и **Camunda history** согласованы.
3. **StuckProcessMonitor** автоматически обнаруживает и лечит повисшую задачу.
4. **Ручной retry** через `/admin/pipeline-runs/{runId}/retry` работает для failed артефактов.

---

## P3-2.1 · Развести успех/ошибку в BPMN-модели

**Файл:** [`artifact-pipeline-process.bpmn`](src/main/resources/bpmn/artifact-pipeline-process.bpmn)

| Подшаг | Действие |
|--------|----------|
| 1.1 | Удалить 4 boundary error event'а с тасков `Adapter`, `Validator`, `Transformer`, `Saver` (строки 60-63, 77-81, 94-98, 111-115) |
| 1.2 | Удалить sequence flow'ы `SubFlow_AdapterError`…`SubFlow_SaverError` → `SubEnd` |
| 1.3 | Убрать ошибочные `incoming` из `SubEnd` (`SubFlow_AdapterError`…`SubFlow_SaverError`) |
| 1.4 | Добавить **boundary error event на subprocess** `SubProcess_PerArtifact` (interrupting, `errorRef="Error_ArtifactFailed"`) |
| 1.5 | Добавить **failed end event** `FailedEnd` на уровне процесса |
| 1.6 | Добавить flow от boundary subprocess → `FailedEnd` |

**Результат:** Ошибка через `handleBpmnError` в любой итерации → cancellation итерации → escalation на уровень процесса → процесс `FAILED`.

---

## P3-2.2 · Заменить handleBpmnError → handleFailure

**Файл:** [`AbstractWorker.java`](src/main/java/ru/beeline/staging/worker/AbstractWorker.java:88)

| Подшаг | Действие |
|--------|----------|
| 2.1 | Удалить `BPMN_ERROR_TOPICS` set и ветку `if (BPMN_ERROR_TOPICS.contains(topic())) { handleBpmnError(...) }` |
| 2.2 | Заменить на единый `handleFailure(task.getId(), workerId(), e.getMessage(), e.toString(), 0, 0L)` |
| 2.3 | Убрать `throw new RuntimeException(e)` после `handleFailure` — задача уже failed с retries=0 |

**Результат:** Permanent error → Camunda incident → `StuckProcessMonitor.healExternalTaskIncidents()` автоматически сбрасывает retries.

---

## P3-2.3 · Убрать дублирование failStage/handleFailure

**Файлы:** [`AbstractWorker.java`](src/main/java/ru/beeline/staging/worker/AbstractWorker.java:86),
[`AdapterWorker.java`](src/main/java/ru/beeline/staging/worker/AdapterWorker.java:103),
[`ValidatorWorker.java`](src/main/java/ru/beeline/staging/worker/ValidatorWorker.java:102),
[`TransformerWorker.java`](src/main/java/ru/beeline/staging/worker/TransformerWorker.java:111),
[`SaverWorker.java`](src/main/java/ru/beeline/staging/worker/SaverWorker.java:99)

| Подшаг | Действие |
|--------|----------|
| 3.1 | Переместить `failStage(...)` из конкретных воркеров в `AbstractWorker.handle()` (после `handleFailure`) |
| 3.2 | Убрать `catch (Exception e) { failStage(...); throw e; }` из `AdapterWorker`/`ValidatorWorker`/`TransformerWorker`/`SaverWorker` |
| 3.3 | Оставить в воркерах только `catch (TransientWorkerException e) { failStageTransient(...); throw e; }` |

**Результат:** `pipeline_runs.status = 'failed'` и Camunda incident записываются атомарно в одном месте.

---

## P3-2.4 · Восстановить ручной retry

**Файлы:** [`PipelineRunService.java`](src/main/java/ru/beeline/staging/service/PipelineRunService.java:179),
[`AdminController.java`](src/main/java/ru/beeline/staging/controller/AdminController.java:72)

| Подшаг | Действие |
|--------|----------|
| 4.1 | В `retryFailedRun()` добавить проверку: если process instance завершён → предлагать `startScan()` вместо `setRetries()` |
| 4.2 | Для артефактного run (`artifactUid != null`) при завершённом процессе запускать новый скан |

**Результат:** `/admin/pipeline-runs/{runId}/retry` работает для всех failed артефактов.

---

## P3-2.5 · Тесты

| Подшаг | Действие |
|--------|----------|
| 5.1 | Unit-тест: permanent error в воркере → `handleFailure` + `failStage` вызваны |
| 5.2 | Интеграционный тест: BPMN процесс с ошибкой → завершается как `FAILED` |
| 5.3 | Проверить `StuckProcessMonitor.healExternalTaskIncidents()` на test-окружении |

---

## Критерии приёмки (Definition of Done)

- [x] Анализ задокументирован в [`.local/analysis-boundary-error.md`](.local/analysis-boundary-error.md)
- [ ] BPMN-процесс при ошибке в итерации multi-instance завершается как `FAILED` в Camunda history (не `COMPLETED`)
- [ ] `pipeline_runs.status` = `failed` согласуется с Camunda history для всех failed артефактов
- [ ] `StuckProcessMonitor` находит и лечит incident после permanent error
- [ ] `/admin/pipeline-runs/{runId}/retry` успешно рестартирует failed run
- [ ] Нет регрессии в работе успешного потока (все стадии → `completed`)
