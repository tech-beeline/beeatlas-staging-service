# ADR-001: Архитектура Pre-Adapter — один на тип vs. универсальный

**Статус:** Принято  
**Дата:** 2026-06-16  
**Участники:** команда staging-service

---

## Контекст

При добавлении нового типа источника данных (e2e-sequence, business-capability, grafana-metrics, …) нужно определить: создаём ли мы отдельный Camunda-процесс с таймером для каждого типа, или используем один универсальный процесс с диспатчем по типу в Java.

---

## Варианты

### Вариант A: Отдельный Camunda-процесс на каждый тип

```
pre-adapter-e2e-process.bpmn      → E2EPreAdapterWorker
pre-adapter-capability-process.bpmn → CapabilityPreAdapterWorker
pre-adapter-grafana-process.bpmn  → GrafanaPreAdapterWorker
```

**Плюсы:**
- Каждый тип имеет независимый таймер прямо в BPMN (разные интервалы без кода)
- Полная изоляция: падение одного типа не влияет на другой
- BPMN-диаграмма явно показывает расписание каждого типа

**Минусы:**
- При добавлении нового типа нужно деплоить новый BPMN-файл — требует согласования
- N типов = N BPMN-процессов = N таймеров в Cockpit, становится шумно
- Дублирование инфраструктурного кода (retry, fail-handling) в каждом процессе

---

### Вариант B: Универсальный External Task + Java registry ✅ ПРИНЯТО

```
pre-adapter-process.bpmn (один) → PreAdapterWorker → switch(artifactType) → handler
```

Расписание хранится не в BPMN-таймере, а в `configurations.schedule_interval_seconds` (на запись).  
`PreAdapterWorker.intervalElapsed()` проверяет через Camunda History API когда последний раз отработал каждый конфиг.

**Плюсы:**
- Один BPMN = один артефакт деплоя; **добавить новый тип = добавить один Java-класс**
- External Task pattern — это и есть механизм доставки нового кода:  
  новый JAR с новым `@Component` → новый тип автоматически подхватывается без изменений BPMN
- Разные интервалы per-config реализуются через `configurations.schedule_interval_seconds` без BPMN-изменений
- Один таймер в Cockpit, лёгкий мониторинг

**Минусы:**
- Расписание не видно в BPMN визуально (нужно смотреть в таблицу конфигураций)
- Один сбой в обработке одного типа не изолирует остальные (но retry-логика в Camunda это покрывает)

---

## Решение: Вариант B

### Как это работает

```
configurations (is_active=true, schedule_interval_seconds NOT NULL)
         │
         ▼ (каждые 6ч — Camunda timer)
PreAdapterWorker.process()
         │
         ├─ isAlreadyRunning(config)?  → пропуск (через Camunda RuntimeService)
         ├─ intervalElapsed(config)?   → пропуск (через Camunda HistoryService)
         │
         └─ switch(config.artifactType)
              ├─ "e2e-sequence"        → fetchFromDashboard() → publish N events
              ├─ "business-capability" → fetchFromSparx() → publish N events
              └─ "grafana-metrics"     → fetchFromGrafana() → publish N events
```

### Как добавить новый тип источника

1. Добавить запись в `staging.data_types` и `staging.configurations`
2. Добавить `case "new-type" -> handler.fetchAndPublish(config, batchId)` в `PreAdapterWorker`
3. Добавить реализации Loader/Validator/Transformer/Saver воркеров
4. Собрать новый JAR — BPMN не меняется, деплоить заново не нужно

### External Task как механизм доставки кода

Каждая стадия пайплайна (pre-adapter / loader / validator / transformer / saver) — это External Task.  
Реализация стадии живёт в JAR приложения. Новая версия логики = новый релиз JAR.  
BPMN-схема остаётся неизменной — она описывает структуру процесса, не логику.

---

## Связанные решения

- ADR-002 (запланировано): Transformer — один класс на тип vs. plugin-система
- [Конфигурации источников](../../src/main/resources/db/migration/V0002__foundation_tables.sql)
